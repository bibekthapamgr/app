package adapters

import (
	"fmt"
	"io"
)

// SOCKS5 client handshake (RFC 1928) with optional username/password
// sub-negotiation (RFC 1929). Only the CONNECT command is implemented: BIND
// and UDP ASSOCIATE are out of V1 scope (ADR-0004), and GSSAPI is never offered.

const (
	socksVersion        = 0x05
	socksAuthVersion    = 0x01
	methodNoAuth        = 0x00
	methodUserPass      = 0x02
	methodNoAcceptable  = 0xFF
	socksCmdConnect     = 0x01
	atypIPv4            = 0x01
	atypDomain          = 0x03
	atypIPv6            = 0x04
	maxSOCKSDomainBytes = 255
)

func socks5Handshake(rw io.ReadWriter, creds *Credentials, t Target) error {
	// 1. Method negotiation. Username/password is offered only when configured,
	//    so credentials are never sent to a proxy that did not ask for them.
	signIn := creds.Present()
	greeting := []byte{socksVersion, 1, methodNoAuth}
	if signIn {
		greeting = []byte{socksVersion, 2, methodNoAuth, methodUserPass}
	}
	if _, err := rw.Write(greeting); err != nil {
		return err
	}
	var sel [2]byte
	if _, err := io.ReadFull(rw, sel[:]); err != nil {
		return err
	}
	if sel[0] != socksVersion {
		return newErr(CodeProtocol, fmt.Sprintf("not a SOCKS5 server (method reply version 0x%02x)", sel[0]), nil)
	}
	switch sel[1] {
	case methodNoAuth:
		// The proxy needs no credentials; none are sent.
	case methodUserPass:
		if !signIn {
			return newErr(CodeProtocol, "proxy selected an authentication method that was not offered", nil)
		}
		if err := socks5UserPass(rw, creds); err != nil {
			return err
		}
	case methodNoAcceptable:
		if !signIn {
			return newErr(CodeAuthRequired, "proxy requires authentication", nil)
		}
		return newErr(CodeAuthFailed, "proxy accepted none of the offered authentication methods", nil)
	default:
		return newErr(CodeProtocol, fmt.Sprintf("proxy selected unsupported method 0x%02x", sel[1]), nil)
	}

	// 2. CONNECT request.
	req, err := socks5ConnectRequest(t)
	if err != nil {
		return err
	}
	if _, err := rw.Write(req); err != nil {
		return err
	}
	// 3. Reply.
	return readSOCKS5Reply(rw)
}

// socks5UserPass sends VER ULEN UNAME PLEN PASSWD from one exactly-sized
// buffer (so no reallocation leaves a copy behind) and zeroes it after writing.
func socks5UserPass(rw io.ReadWriter, creds *Credentials) error {
	err := creds.use(func(user, pass []byte) error {
		if len(user) == 0 || len(user) > MaxSOCKS5CredentialBytes || len(pass) > MaxSOCKS5CredentialBytes {
			return newErr(CodeAuthFailed, "username or password outside RFC 1929 limits", nil)
		}
		msg := make([]byte, 0, 3+len(user)+len(pass))
		msg = append(msg, socksAuthVersion, byte(len(user)))
		msg = append(msg, user...)
		msg = append(msg, byte(len(pass)))
		msg = append(msg, pass...)
		_, werr := rw.Write(msg)
		wipe(msg)
		return werr
	})
	if err == errWiped {
		return newErr(CodeCanceled, "the engine stopped", nil)
	}
	if err != nil {
		return err
	}
	var st [2]byte
	if _, err := io.ReadFull(rw, st[:]); err != nil {
		return err
	}
	// RFC 1929 says version 0x01; a few servers answer 0x05. Accept both.
	if st[0] != socksAuthVersion && st[0] != socksVersion {
		return newErr(CodeProtocol, fmt.Sprintf("bad auth reply version 0x%02x", st[0]), nil)
	}
	if st[1] != 0x00 {
		return newErr(CodeAuthFailed, "proxy rejected the username or password", nil)
	}
	return nil
}

func socks5ConnectRequest(t Target) ([]byte, error) {
	req := []byte{socksVersion, socksCmdConnect, 0x00}
	switch {
	case t.Host != "":
		if len(t.Host) > maxSOCKSDomainBytes || !ValidHostname(t.Host) {
			return nil, newErr(CodeTargetUnsupported, "invalid or over-long hostname", nil)
		}
		req = append(req, atypDomain, byte(len(t.Host)))
		req = append(req, t.Host...)
	case t.Addr.Unmap().Is4():
		a := t.Addr.Unmap().As4()
		req = append(req, atypIPv4)
		req = append(req, a[:]...)
	case t.Addr.Is6():
		a := t.Addr.As16()
		req = append(req, atypIPv6)
		req = append(req, a[:]...)
	default:
		return nil, newErr(CodeInternal, "target has neither hostname nor address", nil)
	}
	req = append(req, byte(t.Port>>8), byte(t.Port))
	return req, nil
}

// readSOCKS5Reply parses VER REP RSV ATYP BND.ADDR BND.PORT. A non-zero REP is
// reported immediately (many servers close without sending the BND fields).
func readSOCKS5Reply(r io.Reader) error {
	var hdr [4]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return err
	}
	if hdr[0] != socksVersion {
		return newErr(CodeProtocol, fmt.Sprintf("bad reply version 0x%02x", hdr[0]), nil)
	}
	if hdr[1] != 0x00 {
		return socks5ReplyError(hdr[1])
	}
	var skip int
	switch hdr[3] {
	case atypIPv4:
		skip = 4
	case atypIPv6:
		skip = 16
	case atypDomain:
		var l [1]byte
		if _, err := io.ReadFull(r, l[:]); err != nil {
			return err
		}
		skip = int(l[0])
	default:
		return newErr(CodeProtocol, fmt.Sprintf("bad reply address type 0x%02x", hdr[3]), nil)
	}
	buf := make([]byte, skip+2) // BND.ADDR + BND.PORT, discarded
	if _, err := io.ReadFull(r, buf); err != nil {
		return err
	}
	return nil
}

func socks5ReplyError(rep byte) error {
	switch rep {
	case 0x01:
		return newErr(CodeProxyGeneralFailed, "SOCKS5 reply 0x01 general failure", nil)
	case 0x02:
		return newErr(CodeTargetNotAllowed, "SOCKS5 reply 0x02 connection not allowed by ruleset", nil)
	case 0x03:
		return newErr(CodeTargetUnreachable, "SOCKS5 reply 0x03 network unreachable", nil)
	case 0x04:
		return newErr(CodeTargetUnreachable, "SOCKS5 reply 0x04 host unreachable", nil)
	case 0x05:
		return newErr(CodeTargetRefused, "SOCKS5 reply 0x05 connection refused", nil)
	case 0x06:
		return newErr(CodeTargetUnreachable, "SOCKS5 reply 0x06 TTL expired", nil)
	case 0x07:
		return newErr(CodeTargetUnsupported, "SOCKS5 reply 0x07 command not supported", nil)
	case 0x08:
		return newErr(CodeTargetUnsupported, "SOCKS5 reply 0x08 address type not supported", nil)
	}
	return newErr(CodeProxyGeneralFailed, fmt.Sprintf("SOCKS5 reply 0x%02x (unassigned)", rep), nil)
}
