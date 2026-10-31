package tunnel

import (
	"encoding/binary"
	"sync/atomic"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/nested"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// echoFilter sits between the TUN link endpoint and the NIC and makes sure
// ProxyFlow never answers a ping (ADR-0004).
//
// Registering no ICMP transport protocol is not enough: gVisor's network
// layer answers ICMP echo requests itself. For IPv4 it skips the reply for
// the temporary addresses that promiscuous mode creates, but for IPv6 it
// replies from any destination address — also after reassembling a large,
// fragmented request (both found by the M1 reviews). The filter therefore:
//
//   - inbound, drops ICMPv4/ICMPv6 Echo Requests before the stack sees them
//     (walking IPv6 extension headers), and drops every fragment of an ICMP
//     packet, so a large ping is never reassembled;
//   - outbound, discards any Echo Reply and any fragment of an ICMP packet
//     the stack still produces. ProxyFlow never sends either legitimately:
//     its only ICMP output is small error messages (port unreachable), which
//     never need fragmenting. This second line holds even for input the
//     inbound parser gives up on.
type echoFilter struct {
	nested.Endpoint
	requestsDropped  atomic.Uint64 // inbound echo requests, one per request
	repliesBlocked   atomic.Uint64 // outbound echo replies, one per reply
	fragmentsDropped atomic.Uint64 // other fragments of ICMP packets, either direction
}

var _ stack.LinkEndpoint = (*echoFilter)(nil)
var _ stack.NetworkDispatcher = (*echoFilter)(nil)

func newEchoFilter(child stack.LinkEndpoint) *echoFilter {
	f := &echoFilter{}
	f.Endpoint.Init(child, f)
	return f
}

// DeliverNetworkPacket implements stack.NetworkDispatcher (inbound, from the TUN).
func (f *echoFilter) DeliverNetworkPacket(protocol tcpip.NetworkProtocolNumber, pkt *stack.PacketBuffer) {
	if b := peekInbound(protocol, pkt); b != nil {
		switch classifyICMP(b) {
		case icmpEchoRequest:
			f.requestsDropped.Add(1)
			return // the dispatcher that called us still owns and releases pkt
		case icmpFragment:
			f.fragmentsDropped.Add(1)
			return
		}
	}
	f.Endpoint.DeliverNetworkPacket(protocol, pkt)
}

// WritePackets implements stack.LinkEndpoint (outbound, towards the TUN).
func (f *echoFilter) WritePackets(pkts stack.PacketBufferList) (int, tcpip.Error) {
	all := pkts.AsSlice()
	var drop []bool
	for i, pkt := range all {
		switch outboundClass(pkt) {
		case icmpEchoReply:
			f.repliesBlocked.Add(1)
		case icmpFragment:
			f.fragmentsDropped.Add(1)
		default:
			continue
		}
		if drop == nil {
			drop = make([]bool, len(all))
		}
		drop[i] = true
	}
	if drop == nil {
		return f.Endpoint.WritePackets(pkts)
	}
	// The caller owns the references of every packet in pkts and releases
	// them after this call, so the subset list is passed on without IncRef
	// and is never Reset here.
	var keep stack.PacketBufferList
	dropped := 0
	for i, pkt := range all {
		if drop[i] {
			dropped++
			continue
		}
		keep.PushBack(pkt)
	}
	if keep.Len() == 0 {
		return dropped, nil
	}
	n, err := f.Endpoint.WritePackets(keep)
	return n + dropped, err
}

type icmpClass uint8

const (
	icmpNone        icmpClass = iota // not ICMP, or ICMP the filter leaves alone
	icmpEchoRequest                  // an echo request: whole, or its first fragment
	icmpEchoReply                    // an echo reply: whole, or its first fragment
	icmpFragment                     // any other fragment of an ICMP packet
)

// maxInboundParse bounds how much of an inbound packet is read to walk IPv6
// extension headers. Anything longer is left to the stack; the outbound check
// still stops any reply.
const maxInboundParse = 4096

// peekInbound returns the start of an unparsed inbound packet when it needs a
// closer look (ICMP, or IPv6 with extension headers), and nil otherwise, so
// ordinary TCP and UDP packets cost one small read.
func peekInbound(protocol tcpip.NetworkProtocolNumber, pkt *stack.PacketBuffer) []byte {
	size := pkt.Data().Size()
	switch protocol {
	case header.IPv4ProtocolNumber:
		b, ok := pkt.Data().PullUp(min(size, header.IPv4MinimumSize))
		if !ok || len(b) < header.IPv4MinimumSize || b[9] != uint8(header.ICMPv4ProtocolNumber) {
			return nil
		}
	case header.IPv6ProtocolNumber:
		b, ok := pkt.Data().PullUp(min(size, header.IPv6MinimumSize))
		if !ok || len(b) < header.IPv6MinimumSize {
			return nil
		}
		if next := b[6]; next == uint8(header.TCPProtocolNumber) || next == uint8(header.UDPProtocolNumber) {
			return nil
		}
	default:
		return nil
	}
	b, ok := pkt.Data().PullUp(min(size, maxInboundParse))
	if !ok {
		return nil
	}
	return b
}

// outboundClass classifies a packet the stack is about to write. Only ICMP
// packets and IPv6 packets with extension headers are copied for the check.
func outboundClass(pkt *stack.PacketBuffer) icmpClass {
	nh := pkt.NetworkHeader().Slice()
	switch pkt.NetworkProtocolNumber {
	case header.IPv4ProtocolNumber:
		if len(nh) < header.IPv4MinimumSize || nh[9] != uint8(header.ICMPv4ProtocolNumber) {
			return icmpNone
		}
	case header.IPv6ProtocolNumber:
		if len(nh) < header.IPv6MinimumSize {
			return icmpNone
		}
		if next := nh[6]; next == uint8(header.TCPProtocolNumber) || next == uint8(header.UDPProtocolNumber) {
			return icmpNone
		}
	default:
		return icmpNone
	}
	v := pkt.ToView()
	defer v.Release()
	return classifyICMP(v.AsSlice())
}

// classifyICMP inspects a raw IPv4 or IPv6 packet (starting at the IP header).
func classifyICMP(b []byte) icmpClass {
	if len(b) == 0 {
		return icmpNone
	}
	switch b[0] >> 4 {
	case 4:
		if len(b) < header.IPv4MinimumSize {
			return icmpNone
		}
		ihl := int(b[0]&0x0f) * 4
		if ihl < header.IPv4MinimumSize || b[9] != uint8(header.ICMPv4ProtocolNumber) {
			return icmpNone
		}
		frag := binary.BigEndian.Uint16(b[6:8])
		more, offset := frag&0x2000 != 0, frag&0x1fff
		if offset != 0 {
			return icmpFragment
		}
		if len(b) > ihl {
			switch header.ICMPv4Type(b[ihl]) {
			case header.ICMPv4Echo:
				return icmpEchoRequest
			case header.ICMPv4EchoReply:
				return icmpEchoReply
			}
		}
		if more {
			return icmpFragment
		}
		return icmpNone
	case 6:
		if len(b) < header.IPv6MinimumSize {
			return icmpNone
		}
		next, off, fragmented := b[6], header.IPv6MinimumSize, false
		for {
			switch header.IPv6ExtensionHeaderIdentifier(next) {
			case header.IPv6HopByHopOptionsExtHdrIdentifier, header.IPv6RoutingExtHdrIdentifier,
				header.IPv6DestinationOptionsExtHdrIdentifier:
				if len(b) < off+2 {
					return icmpNone
				}
				next, off = b[off], off+(int(b[off+1])+1)*8
				continue
			case header.IPv6AuthenticationExtHdrIdentifier:
				if len(b) < off+2 {
					return icmpNone
				}
				next, off = b[off], off+(int(b[off+1])+2)*4
				continue
			case header.IPv6FragmentExtHdrIdentifier:
				if len(b) < off+8 {
					return icmpNone
				}
				fragOffset := binary.BigEndian.Uint16(b[off+2:off+4]) >> 3
				next, off, fragmented = b[off], off+8, true
				if fragOffset != 0 {
					// A later fragment names only the first header of the
					// fragmented part. When that is not ICMPv6, the first
					// fragment decides: without it nothing is reassembled.
					if next == uint8(header.ICMPv6ProtocolNumber) {
						return icmpFragment
					}
					return icmpNone
				}
				continue
			}
			break
		}
		if next != uint8(header.ICMPv6ProtocolNumber) {
			return icmpNone
		}
		if len(b) > off {
			switch header.ICMPv6Type(b[off]) {
			case header.ICMPv6EchoRequest:
				return icmpEchoRequest
			case header.ICMPv6EchoReply:
				return icmpEchoReply
			}
		}
		if fragmented {
			return icmpFragment
		}
	}
	return icmpNone
}
