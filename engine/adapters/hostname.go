package adapters

// ValidHostname reports whether s is a safe, normalised DNS hostname to place
// in a SOCKS5 or HTTP CONNECT request: lower-case LDH labels (underscore
// allowed), 1-63 bytes per label, at most 253 bytes, no trailing dot.
//
// DNS labels are binary-safe, so a local app can ask the fake-DNS resolver
// about a name containing spaces, CR/LF or ':' — which would become an HTTP
// header injection in "CONNECT <name>:443 HTTP/1.1". Every name is validated
// before it is mapped and again before it is sent upstream.
func ValidHostname(s string) bool {
	if len(s) == 0 || len(s) > 253 {
		return false
	}
	labelLen := 0
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c >= 'a' && c <= 'z', c >= '0' && c <= '9', c == '_':
			labelLen++
		case c == '-':
			if labelLen == 0 { // label may not start with '-'
				return false
			}
			labelLen++
		case c == '.':
			if labelLen == 0 || s[i-1] == '-' {
				return false
			}
			labelLen = 0
			continue
		default:
			return false
		}
		if labelLen > 63 {
			return false
		}
	}
	return labelLen > 0 && s[len(s)-1] != '-'
}
