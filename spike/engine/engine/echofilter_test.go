package engine

import (
	"encoding/binary"
	"net/netip"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/stack"

	"proxyflow/spike/engine/fakedns"
)

// Raw-packet tests: the test writes IP packets straight into the engine's
// "TUN" (one end of a datagram socketpair) and reads what the engine writes
// back, so nothing (no client stack) can hide or invent a reply.

func csum16(b []byte, initial uint32) uint16 {
	s := initial
	for i := 0; i+1 < len(b); i += 2 {
		s += uint32(binary.BigEndian.Uint16(b[i:]))
	}
	if len(b)%2 == 1 {
		s += uint32(b[len(b)-1]) << 8
	}
	for s>>16 != 0 {
		s = (s & 0xffff) + (s >> 16)
	}
	return ^uint16(s)
}

// ipv4Header builds an IPv4 header; fragOff is in bytes (a multiple of 8).
func ipv4Header(src, dst netip.Addr, proto byte, payloadLen, id, fragOff int, more bool) []byte {
	h := make([]byte, 20)
	h[0] = 0x45
	binary.BigEndian.PutUint16(h[2:], uint16(20+payloadLen))
	binary.BigEndian.PutUint16(h[4:], uint16(id))
	ff := uint16(fragOff / 8)
	if more {
		ff |= 0x2000
	}
	binary.BigEndian.PutUint16(h[6:], ff)
	h[8], h[9] = 64, proto
	s4, d4 := src.As4(), dst.As4()
	copy(h[12:], s4[:])
	copy(h[16:], d4[:])
	binary.BigEndian.PutUint16(h[10:], csum16(h, 0))
	return h
}

func ipv4Packet(src, dst netip.Addr, proto byte, payload []byte) []byte {
	return append(ipv4Header(src, dst, proto, len(payload), 1, 0, false), payload...)
}

// payload returns n bytes of filler, so large pings look like `ping -s n`.
func payload(n int) []byte {
	b := make([]byte, n)
	for i := range b {
		b[i] = byte(i)
	}
	return b
}

// icmpv4Echo builds an ICMPv4 message of type typ (8 request, 0 reply).
func icmpv4Echo(typ byte, data []byte) []byte {
	m := append([]byte{typ, 0, 0, 0, 0x12, 0x34, 0, 1}, data...)
	binary.BigEndian.PutUint16(m[2:], csum16(m, 0))
	return m
}

// ipv4Fragments splits an IPv4 payload into fragments of at most
// firstLen bytes (a multiple of 8), as the kernel does for `ping -s 2000`.
func ipv4Fragments(src, dst netip.Addr, proto byte, upper []byte, firstLen, id int) [][]byte {
	var out [][]byte
	for off := 0; off < len(upper); off += firstLen {
		end := min(off+firstLen, len(upper))
		h := ipv4Header(src, dst, proto, end-off, id, off, end < len(upper))
		out = append(out, append(h, upper[off:end]...))
	}
	return out
}

// icmpv6Echo builds an ICMPv6 message of type typ (128 request, 129 reply)
// with a valid checksum.
func icmpv6Echo(src, dst netip.Addr, typ byte, data []byte) []byte {
	icmp := append([]byte{typ, 0, 0, 0, 0x56, 0x78, 0, 1}, data...)
	s16, d16 := src.As16(), dst.As16()
	pseudo := make([]byte, 0, 40)
	pseudo = append(pseudo, s16[:]...)
	pseudo = append(pseudo, d16[:]...)
	pseudo = binary.BigEndian.AppendUint32(pseudo, uint32(len(icmp)))
	pseudo = append(pseudo, 0, 0, 0, 58)
	var init uint32
	for i := 0; i < len(pseudo); i += 2 {
		init += uint32(binary.BigEndian.Uint16(pseudo[i:]))
	}
	binary.BigEndian.PutUint16(icmp[2:], csum16(icmp, init))
	return icmp
}

func ipv6Packet(src, dst netip.Addr, next byte, upper []byte) []byte {
	h := make([]byte, 40)
	h[0] = 0x60
	binary.BigEndian.PutUint16(h[4:], uint16(len(upper)))
	h[6], h[7] = next, 64
	s16, d16 := src.As16(), dst.As16()
	copy(h[8:], s16[:])
	copy(h[24:], d16[:])
	return append(h, upper...)
}

// destOpts is a Destination Options header (one PadN option) followed by next.
func destOpts(next byte) []byte { return []byte{next, 0, 1, 4, 0, 0, 0, 0} }

// ipv6Echo builds an ICMPv6 Echo Request, optionally behind a Destination
// Options header.
func ipv6Echo(src, dst netip.Addr, withExt bool) []byte {
	icmp := icmpv6Echo(src, dst, 128, []byte("pf"))
	if withExt {
		return ipv6Packet(src, dst, 60, append(destOpts(58), icmp...))
	}
	return ipv6Packet(src, dst, 58, icmp)
}

// ipv6Fragments splits a fragmentable part (first header type `first`) into
// fragments carrying at most firstLen bytes (a multiple of 8), as the kernel
// does for `ping -6 -s 2048` on a 1500-byte link.
func ipv6Fragments(src, dst netip.Addr, first byte, part []byte, firstLen int, id uint32) [][]byte {
	var out [][]byte
	for off := 0; off < len(part); off += firstLen {
		end := min(off+firstLen, len(part))
		fh := make([]byte, 8)
		fh[0] = first
		fo := uint16(off/8) << 3
		if end < len(part) {
			fo |= 1
		}
		binary.BigEndian.PutUint16(fh[2:], fo)
		binary.BigEndian.PutUint32(fh[4:], id)
		out = append(out, ipv6Packet(src, dst, 44, append(fh, part[off:end]...)))
	}
	return out
}

func dnsQueryUDP4(src, dst netip.Addr, name string) []byte {
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: 7, RecursionDesired: true})
	b.StartQuestions()
	b.Question(dnsmessage.Question{Name: dnsmessage.MustNewName(name + "."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET})
	q, _ := b.Finish()
	udp := make([]byte, 8)
	binary.BigEndian.PutUint16(udp[0:], 40000)
	binary.BigEndian.PutUint16(udp[2:], 53)
	binary.BigEndian.PutUint16(udp[4:], uint16(8+len(q)))
	return ipv4Packet(src, dst, 17, append(udp, q...)) // UDP checksum 0 = none (IPv4)
}

type seen struct{ echoReplies, dnsAnswers, other int }

// classify counts what the engine wrote to the TUN. Any echo reply, or any
// fragment of an ICMP packet (the engine never sends one legitimately),
// counts as an answered ping.
func classify(p []byte, s *seen) {
	if len(p) < 1 {
		return
	}
	switch p[0] >> 4 {
	case 4:
		ihl := int(p[0]&0xf) * 4
		if len(p) <= ihl {
			return
		}
		frag := binary.BigEndian.Uint16(p[6:8])
		switch {
		case p[9] == 1 && (frag&0x3fff != 0 || p[ihl] == 0):
			s.echoReplies++
		case p[9] == 17 && len(p) >= ihl+8 && binary.BigEndian.Uint16(p[ihl:]) == 53:
			s.dnsAnswers++
		default:
			s.other++
		}
	case 6:
		off, next, fragmented := 40, p[6], false
		for next == 0 || next == 43 || next == 44 || next == 60 {
			if len(p) < off+8 {
				return
			}
			if next == 44 {
				fragmented = true
				later := binary.BigEndian.Uint16(p[off+2:])>>3 != 0
				next, off = p[off], off+8
				if later {
					break
				}
				continue
			}
			next, off = p[off], off+(int(p[off+1])+1)*8
		}
		if next == 58 && (fragmented || (len(p) > off && p[off] == 129)) {
			s.echoReplies++
		} else {
			s.other++
		}
	}
}

func TestPingIsNeverAnswered(t *testing.T) {
	efd, tfd := socketpair(t)
	pool, _ := fakedns.NewPool(netip.MustParsePrefix("198.18.0.0/15"), 256, 0)
	e, err := New(Config{FD: efd, Dialer: &fakeDialer{}, FakeDNS: &fakedns.Responder{Pool: pool, TTL: 5}})
	if err != nil {
		t.Fatal(err)
	}
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { e.Stop(); unix.Close(efd); unix.Close(tfd) })

	app4, app6 := netip.MustParseAddr("198.18.0.1"), netip.MustParseAddr("fd00:66::1")
	web4 := netip.MustParseAddr("10.30.0.2")
	dst := func(i int) netip.Addr { return netip.AddrFrom16([16]byte{0x20, 0x01, 0x0d, 0xb8, 15: byte(i)}) }
	big6 := icmpv6Echo(app6, dst(4), 128, payload(2048))  // 2056-byte message
	big6b := icmpv6Echo(app6, dst(5), 128, payload(2048)) // same, behind dest opts
	probes := []struct {
		name string
		pkts [][]byte
	}{
		{"ipv4 echo", [][]byte{ipv4Packet(app4, web4, 1, icmpv4Echo(8, []byte("pf")))}},
		{"ipv6 echo", [][]byte{ipv6Echo(app6, dst(1), false)}},
		{"ipv6 echo behind dest opts", [][]byte{ipv6Echo(app6, dst(2), true)}},
		{"ipv6 atomic fragment", ipv6Fragments(app6, dst(3), 58, icmpv6Echo(app6, dst(3), 128, []byte("pf")), 1448, 0x103)},
		{"ipv6 echo, 2 KB in two fragments", ipv6Fragments(app6, dst(4), 58, big6, 1448, 0x104)},
		{"ipv6 echo, 2 KB fragmented after dest opts", ipv6Fragments(app6, dst(5), 60, append(destOpts(58), big6b...), 1448, 0x105)},
		{"ipv4 echo, 2 KB in two fragments", ipv4Fragments(app4, web4, 1, icmpv4Echo(8, payload(2048)), 1480, 0x106)},
		{"dns control", [][]byte{dnsQueryUDP4(app4, netip.MustParseAddr("198.18.0.2"), "ping.test")}},
	}
	for _, p := range probes {
		for _, pkt := range p.pkts {
			if _, err := unix.Write(tfd, pkt); err != nil {
				t.Fatalf("%s: write: %v", p.name, err)
			}
		}
	}

	var s seen
	buf := make([]byte, 65536)
	deadline := time.Now().Add(1500 * time.Millisecond)
	for time.Now().Before(deadline) {
		fds := []unix.PollFd{{Fd: int32(tfd), Events: unix.POLLIN}}
		if n, _ := unix.Poll(fds, 100); n <= 0 {
			continue
		}
		n, err := unix.Read(tfd, buf)
		if err != nil {
			continue
		}
		classify(buf[:n], &s)
	}

	if s.dnsAnswers != 1 {
		t.Fatalf("control: want 1 DNS answer from the engine, got %d (reader or filter broken)", s.dnsAnswers)
	}
	if s.echoReplies != 0 {
		t.Fatalf("engine answered %d ping(s); pings must never be answered (ADR-0004)", s.echoReplies)
	}
	st := e.Stats()
	if st.ICMPEchoRepliesSent != 0 {
		t.Fatalf("icmp_echo_replies_sent = %d, want 0", st.ICMPEchoRepliesSent)
	}
	// One per request: 7 echo requests (whole or first fragment) were sent.
	if st.ICMPEchoRequestsDrop != 7 {
		t.Fatalf("icmp_echo_requests_dropped = %d, want 7 (every request, whole or its first fragment)", st.ICMPEchoRequestsDrop)
	}
	// The later fragments of the two plain fragmented pings; the later
	// fragment behind dest opts cannot be identified and waits in vain for
	// its dropped first fragment.
	if st.ICMPFragmentsDrop != 2 {
		t.Fatalf("icmp_fragments_dropped = %d, want 2", st.ICMPFragmentsDrop)
	}
	t.Logf("requests dropped %d, fragments dropped %d, replies blocked %d, replies sent %d",
		st.ICMPEchoRequestsDrop, st.ICMPFragmentsDrop, st.ICMPEchoRepliesBlock, st.ICMPEchoRepliesSent)
}

// TestEchoFilterBlocksOutboundReplies checks the second line of defence on
// its own: whatever the stack writes, no echo reply and no ICMP fragment
// reaches the TUN, while ICMP errors and ordinary traffic pass.
func TestEchoFilterBlocksOutboundReplies(t *testing.T) {
	ch := channel.New(32, 1500, "")
	f := newEchoFilter(ch)
	eng4, app4 := netip.MustParseAddr("10.30.0.2"), netip.MustParseAddr("198.18.0.1")
	eng6, app6 := netip.MustParseAddr("2001:db8::7"), netip.MustParseAddr("fd00:66::1")

	unreach4 := append([]byte{3, 3, 0, 0, 0, 0, 0, 0}, ipv4Packet(app4, eng4, 17, make([]byte, 8))...)
	binary.BigEndian.PutUint16(unreach4[2:], csum16(unreach4, 0))
	unreach6 := icmpv6Echo(eng6, app6, 1, make([]byte, 48)) // type 1 = destination unreachable
	tcp4 := ipv4Packet(eng4, app4, 6, make([]byte, 20))

	cases := []struct {
		name string
		pkts [][]byte
		pass bool
	}{
		{"ipv4 echo reply", [][]byte{ipv4Packet(eng4, app4, 1, icmpv4Echo(0, []byte("pf")))}, false},
		{"ipv6 echo reply", [][]byte{ipv6Packet(eng6, app6, 58, icmpv6Echo(eng6, app6, 129, []byte("pf")))}, false},
		{"ipv6 echo reply, 2 KB in two fragments", ipv6Fragments(eng6, app6, 58, icmpv6Echo(eng6, app6, 129, payload(2048)), 1448, 9), false},
		{"ipv4 echo reply, 2 KB in two fragments", ipv4Fragments(eng4, app4, 1, icmpv4Echo(0, payload(2048)), 1480, 9), false},
		{"ipv4 port unreachable", [][]byte{ipv4Packet(eng4, app4, 1, unreach4)}, true},
		{"ipv6 destination unreachable", [][]byte{ipv6Packet(eng6, app6, 58, unreach6)}, true},
		{"ipv4 tcp", [][]byte{tcp4}, true},
	}
	var list stack.PacketBufferList
	var owned []*stack.PacketBuffer
	want := 0
	for _, c := range cases {
		for _, raw := range c.pkts {
			proto := header.IPv4ProtocolNumber
			nh := int(raw[0]&0xf) * 4
			if raw[0]>>4 == 6 {
				proto, nh = header.IPv6ProtocolNumber, header.IPv6MinimumSize
			}
			pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
				ReserveHeaderBytes: nh,
				Payload:            buffer.MakeWithData(append([]byte(nil), raw[nh:]...)),
			})
			copy(pkt.NetworkHeader().Push(nh), raw[:nh])
			pkt.NetworkProtocolNumber = proto
			list.PushBack(pkt)
			owned = append(owned, pkt)
			if c.pass {
				want++
			}
		}
	}
	n, err := f.WritePackets(list)
	for _, p := range owned {
		p.DecRef()
	}
	if err != nil || n != len(owned) {
		t.Fatalf("WritePackets = %d, %v; want %d, nil", n, err, len(owned))
	}
	var s seen
	got := 0
	for p := ch.Read(); p != nil; p = ch.Read() {
		v := p.ToView()
		classify(v.AsSlice(), &s)
		v.Release()
		p.DecRef()
		got++
	}
	if s.echoReplies != 0 || got != want {
		t.Fatalf("passed %d packets (%d echo replies or ICMP fragments); want %d packets, 0 replies", got, s.echoReplies, want)
	}
	if r, fr := f.repliesBlocked.Load(), f.fragmentsDropped.Load(); r != 4 || fr != 2 {
		t.Fatalf("replies blocked %d, fragments dropped %d; want 4 replies (one per reply) and 2 later fragments", r, fr)
	}
}

func TestClassifyICMP(t *testing.T) {
	a6, b6 := netip.MustParseAddr("fd00:66::1"), netip.MustParseAddr("2001:db8::1")
	req := icmpv6Echo(a6, b6, 128, []byte("pf"))
	ah := []byte{58, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 2} // Authentication header: 12 bytes, next = ICMPv6
	later := ipv6Fragments(a6, b6, 60, append(destOpts(58), icmpv6Echo(a6, b6, 128, payload(2048))...), 1448, 1)[1]
	for _, c := range []struct {
		name string
		pkt  []byte
		want icmpClass
	}{
		{"behind authentication header", ipv6Packet(a6, b6, 51, append(ah, req...)), icmpEchoRequest},
		{"behind hop-by-hop and dest opts", ipv6Packet(a6, b6, 0, append(destOpts(60), append(destOpts(58), req...)...)), icmpEchoRequest},
		{"later fragment whose first header is dest opts", later, icmpNone},
		{"tcp", ipv6Packet(a6, b6, 6, make([]byte, 20)), icmpNone},
		{"truncated extension header", ipv6Packet(a6, b6, 60, []byte{58}), icmpNone},
	} {
		if got := classifyICMP(c.pkt); got != c.want {
			t.Errorf("%s: classifyICMP = %d, want %d", c.name, got, c.want)
		}
	}
}
