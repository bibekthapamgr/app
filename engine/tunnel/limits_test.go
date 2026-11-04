package tunnel

// M8: the engine's bounds under load and malformed input. Apps on the phone can open connections faster than any
// proxy answers, and anything on the phone can write any bytes into the TUN: the engine must keep to MaxConns and
// MaxInFlight, drop what it can't parse, and go on serving — never panic, never grow without bound, never stop.

import (
	"context"
	"encoding/binary"
	"errors"
	"math/rand"
	"net"
	"net/netip"
	"runtime"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/sys/unix"

	"github.com/bibekthapamgr/app/engine/adapters"
	"github.com/bibekthapamgr/app/engine/internal/proxytest"
)

// countingDialer tells how many tunnels are being set up at once.
type countingDialer struct {
	next      TunnelDialer
	now, most atomic.Int64
	dials     atomic.Int64
}

func (d *countingDialer) Dial(ctx context.Context, t adapters.Target) (net.Conn, error) {
	n := d.now.Add(1)
	defer d.now.Add(-1)
	d.dials.Add(1)
	for {
		most := d.most.Load()
		if n <= most || d.most.CompareAndSwap(most, n) {
			break
		}
	}
	return d.next.Dial(ctx, t)
}

// liveDialer counts the tunnels open at once: dialled through the proxy and not yet closed by the engine.
type liveDialer struct {
	next      TunnelDialer
	now, most atomic.Int64
}

func (d *liveDialer) Dial(ctx context.Context, t adapters.Target) (net.Conn, error) {
	c, err := d.next.Dial(ctx, t)
	if err != nil {
		return nil, err
	}
	n := d.now.Add(1)
	for {
		most := d.most.Load()
		if n <= most || d.most.CompareAndSwap(most, n) {
			break
		}
	}
	return &countedConn{Conn: c, done: func() { d.now.Add(-1) }}, nil
}

type countedConn struct {
	net.Conn
	once sync.Once
	done func()
}

func (c *countedConn) Close() error {
	c.once.Do(c.done)
	return c.Conn.Close()
}

// Many more connections at once than MaxConns: never more tunnels open than the bound, the rest refused and counted,
// every tunnel closed when the apps' connections close, and the engine goes on serving.
func TestManyConnectionsAtOnceStayWithinMaxConns(t *testing.T) {
	proxytest.CheckLeaks(t)
	const limit, attempts = 24, 120
	fake := &fakeDialer{}
	t.Cleanup(fake.closeQuiet)
	dialer := &liveDialer{next: fake}
	e, _ := startEngine(t, dialer, func(c *Config) { c.MaxConns = limit })
	ip := resolve(t, e.cs, "quiet.test")

	var (
		mu    sync.Mutex
		open  []net.Conn
		wg    sync.WaitGroup
		other atomic.Int64
	)
	for i := 0; i < attempts; i++ {
		wg.Add(1)
		go func(port uint16) {
			defer wg.Done()
			c, err := dialTCP(e.cs, ip, port)
			if err != nil {
				if !isRefused(err) {
					other.Add(1) // a timeout of the test's own client stack: not the engine's answer
				}
				return
			}
			mu.Lock()
			open = append(open, c)
			mu.Unlock()
		}(uint16(10_000 + i))
	}
	wg.Wait()
	if n := len(open); n > limit {
		t.Fatalf("%d connections open at MaxConns=%d", n, limit)
	}
	if m := dialer.most.Load(); m > limit {
		t.Fatalf("%d tunnels open at once at MaxConns=%d", m, limit)
	}
	if got := e.Stats().Failures["CONNECTION_LIMIT"]; got < 1 {
		t.Fatalf("connections over the limit were not refused as CONNECTION_LIMIT: %v", e.Stats().Failures)
	}
	t.Logf("%d of %d attempts open (limit %d), %d refused at the limit, %d timed out on the test's client, at most %d tunnels at once",
		len(open), attempts, limit, e.Stats().Failures["CONNECTION_LIMIT"], other.Load(), dialer.most.Load())

	for _, c := range open {
		_ = c.Close()
	}
	fake.closeQuiet()
	waitFor(t, func() bool { st := e.Stats(); return st.TCPActive == 0 && st.TCPConnecting == 0 }, "every slot to be freed")
	waitFor(t, func() bool { return dialer.now.Load() == 0 }, "every tunnel to be closed")
	c := dialOK(t, e, resolve(t, e.cs, "ok.test"), 80)
	defer c.Close()
	roundTrip(t, c, "after the burst")
}

// A flood of handshakes to a proxy that never answers: at most MaxInFlight tunnels are set up at once, the SYNs over
// it are dropped and counted (the apps retry), each set-up ends at the dial timeout, and the engine serves again.
func TestAHandshakeFloodIsHeldToMaxInFlight(t *testing.T) {
	proxytest.CheckLeaks(t)
	const inFlight, attempts = 8, 48
	dialer := &countingDialer{next: &fakeDialer{}}
	e, _ := startEngine(t, dialer, func(c *Config) {
		c.MaxInFlight = inFlight
		c.DialTimeout = 1500 * time.Millisecond
	})
	ip := resolve(t, e.cs, "block.test")
	var wg sync.WaitGroup
	for i := 0; i < attempts; i++ {
		wg.Add(1)
		go func(port uint16) {
			defer wg.Done()
			if c, err := dialTCP(e.cs, ip, port); err == nil {
				_ = c.Close()
				t.Errorf("a connection to a proxy that never answers was accepted")
			}
		}(uint16(20_000 + i))
	}
	wg.Wait()
	if most := dialer.most.Load(); most > inFlight {
		t.Fatalf("%d tunnels set up at once at MaxInFlight=%d", most, inFlight)
	}
	st := e.Stats()
	if st.InFlightDrops == 0 {
		t.Fatalf("no SYN was dropped at MaxInFlight=%d with %d at once", inFlight, attempts)
	}
	t.Logf("%d dials, at most %d at once, %d SYNs dropped at the bound, timeouts %d", dialer.dials.Load(), dialer.most.Load(), st.InFlightDrops, st.ProxyTimeouts)
	waitFor(t, func() bool { return e.Stats().TCPConnecting == 0 }, "every set-up to end at its dial timeout")
	c := dialOK(t, e, resolve(t, e.cs, "ok.test"), 80)
	defer c.Close()
	roundTrip(t, c, "after the flood")
}

// Malformed packets of every kind written into the TUN, as anything on the phone could: each is dropped, the engine
// keeps running and serving, and its memory and goroutines stay where they were.
func TestMalformedPacketsAreDroppedAndTheEngineGoesOn(t *testing.T) {
	proxytest.CheckLeaks(t)
	e, _ := startEngine(t, nil)
	ip := resolve(t, e.cs, "ok.test")
	before := runtime.NumGoroutine()
	r := rand.New(rand.NewSource(8))
	written := 0
	for _, packet := range malformedPackets(r, ip, 4000) {
		if err := writePacket(e.cfd, packet); err != nil {
			t.Fatalf("write: %v", err)
		}
		written++
	}
	time.Sleep(200 * time.Millisecond) // the engine reads them asynchronously
	if e.State() != StateRunning {
		t.Fatalf("state %s after %d malformed packets", e.State(), written)
	}
	st := e.Stats()
	if st.MalformedIn == 0 {
		t.Fatalf("none of %d malformed packets was counted as malformed (packets in %d)", written, st.PacketsIn)
	}
	if st.RecoveredPanics != 0 {
		t.Fatalf("%d panics recovered", st.RecoveredPanics)
	}
	c := dialOK(t, e, ip, 80)
	defer c.Close()
	roundTrip(t, c, "after the garbage")
	if after := runtime.NumGoroutine(); after > before+32 {
		t.Fatalf("goroutines went from %d to %d", before, after)
	}
	t.Logf("%d malformed packets written, %d counted malformed, %d in, UDP rejected %d, TCP failed %d", written, st.MalformedIn, st.PacketsIn, st.UDPRejected, st.TCPFailed)
}

// FuzzPacketLoop writes any bytes into the TUN as one packet: the engine must survive each (a panic in its goroutines
// ends the fuzzing process) and keep running.
func FuzzPacketLoop(f *testing.F) {
	e, _ := startEngine(f, nil)
	ip := resolve(f, e.cs, "ok.test")
	for _, seed := range malformedPackets(rand.New(rand.NewSource(1)), ip, 64) {
		f.Add(seed)
	}
	f.Fuzz(func(t *testing.T, packet []byte) {
		if err := writePacket(e.cfd, packet); err != nil {
			t.Fatalf("write: %v", err)
		}
		if s := e.State(); s != StateRunning {
			t.Fatalf("state %s", s)
		}
	})
}

// writePacket writes one datagram, waiting while the socket's buffer is full. An empty one is not written: a TUN
// never delivers an empty packet (each read returns one whole packet), and on the test's socketpair a zero-length
// datagram reads as end of file, which ends the packet loop as a TUN that went away does.
func writePacket(fd int, packet []byte) error {
	if len(packet) == 0 {
		return nil
	}
	for i := 0; ; i++ {
		_, err := unix.Write(fd, packet)
		if err == nil || (!errors.Is(err, unix.EAGAIN) && !errors.Is(err, unix.ENOBUFS)) {
			if errors.Is(err, unix.EMSGSIZE) {
				return nil // larger than the socket takes: no TUN delivers it either
			}
			return err
		}
		if i > 2000 {
			return err
		}
		time.Sleep(time.Millisecond)
	}
}

// malformedPackets: broken IPv4 and IPv6 headers, TCP and UDP headers that lie about their size, fragments, extension
// header chains, DNS to the engine's resolver with garbage, and random bytes; to ip (one of the engine's placeholder
// addresses) and elsewhere.
func malformedPackets(r *rand.Rand, ip netip.Addr, n int) [][]byte {
	dst := ip.As4()
	dns := netip.MustParseAddr("198.18.0.2").As4()
	src := [4]byte{10, 0, 0, 2}
	ipv4 := func(proto byte, payload []byte, mutate func([]byte)) []byte {
		p := make([]byte, 20+len(payload))
		p[0] = 0x45
		binary.BigEndian.PutUint16(p[2:], uint16(len(p)))
		p[8] = 64
		p[9] = proto
		copy(p[12:16], src[:])
		copy(p[16:20], dst[:])
		copy(p[20:], payload)
		binary.BigEndian.PutUint16(p[10:], ^checksum(p[:20]))
		if mutate != nil {
			mutate(p)
		}
		return p
	}
	tcpHeader := func(flags byte, offset byte) []byte {
		h := make([]byte, 20)
		binary.BigEndian.PutUint16(h[0:], 40000)
		binary.BigEndian.PutUint16(h[2:], 80)
		h[12] = offset << 4
		h[13] = flags
		binary.BigEndian.PutUint16(h[14:], 65535)
		return h
	}
	random := func(size int) []byte {
		b := make([]byte, size)
		r.Read(b)
		return b
	}
	templates := []func() []byte{
		func() []byte { return []byte{0x45} },
		func() []byte { return random(19) },
		func() []byte { return ipv4(6, tcpHeader(0x02, 5), func(p []byte) { p[0] = 0x43 }) }, // IHL below 5
		func() []byte { return ipv4(6, tcpHeader(0x02, 5), func(p []byte) { p[0] = 0x4f }) }, // IHL past the packet
		func() []byte {
			return ipv4(6, tcpHeader(0x02, 5), func(p []byte) { binary.BigEndian.PutUint16(p[2:], 1400) })
		}, // longer than sent
		func() []byte {
			return ipv4(6, tcpHeader(0x02, 5), func(p []byte) { binary.BigEndian.PutUint16(p[2:], 8) })
		}, // shorter than its header
		func() []byte { return ipv4(6, tcpHeader(0x02, 5), func(p []byte) { p[10] ^= 0xff }) }, // bad checksum
		func() []byte { return ipv4(6, tcpHeader(0x02, 1), nil) },                              // TCP offset below 5
		func() []byte { return ipv4(6, tcpHeader(0x02, 15), nil) },                             // TCP offset past the segment
		func() []byte { return ipv4(6, tcpHeader(0x02, 5)[:7], nil) },                          // truncated TCP
		func() []byte { return ipv4(6, tcpHeader(0x3f, 5), nil) },                              // every flag at once
		func() []byte { return ipv4(6, tcpHeader(0x02, 5), func(p []byte) { p[6] = 0x20 }) },   // first fragment of a SYN
		func() []byte {
			return ipv4(6, random(64), func(p []byte) { binary.BigEndian.PutUint16(p[6:], 0x1fff) })
		}, // last fragment, far offset
		func() []byte {
			return ipv4(17, []byte{0x9c, 0x40, 0, 53, 0xff, 0xff, 0, 0}, func(p []byte) { copy(p[16:], dns[:]) })
		}, // UDP length past the packet
		func() []byte {
			return ipv4(17, append([]byte{0x9c, 0x40, 0, 53, 0, 48, 0, 0}, random(40)...), func(p []byte) { copy(p[16:], dns[:]) })
		}, // DNS garbage to the engine's resolver
		func() []byte { return ipv4(1, []byte{8, 0}, nil) },       // truncated echo request
		func() []byte { return ipv4(1, []byte{3, 4, 0, 0}, nil) }, // truncated "fragmentation needed"
		func() []byte { return ipv4(byte(r.Intn(256)), random(r.Intn(200)), nil) },
		func() []byte { return append([]byte{0x60}, random(39)...) }, // IPv6 header with nothing after it
		func() []byte { // IPv6 claiming more payload than sent
			p := make([]byte, 40+8)
			p[0] = 0x60
			binary.BigEndian.PutUint16(p[4:], 1200)
			p[6] = 17
			p[7] = 64
			return p
		},
		func() []byte { // a long IPv6 extension header chain (hop-by-hop, routing, fragment, destination), then TCP
			p := make([]byte, 40)
			p[0] = 0x60
			p[6] = 0
			p[7] = 64
			chain := []byte{}
			for _, next := range []byte{43, 44, 60, 0, 43, 60, 6} {
				chain = append(chain, next, 0, 0, 0, 0, 0, 0, 0)
			}
			chain = append(chain, tcpHeader(0x02, 5)...)
			binary.BigEndian.PutUint16(p[4:], uint16(len(chain)))
			return append(p, chain...)
		},
		func() []byte { return random(1 + r.Intn(1500)) },
		func() []byte { return append([]byte{byte(r.Intn(16) << 4)}, random(r.Intn(100))...) }, // any IP version
	}
	out := make([][]byte, 0, n)
	for i := 0; i < n; i++ {
		out = append(out, templates[i%len(templates)]())
	}
	return out
}

func checksum(b []byte) uint16 {
	var sum uint32
	for i := 0; i+1 < len(b); i += 2 {
		sum += uint32(binary.BigEndian.Uint16(b[i:]))
	}
	if len(b)%2 == 1 {
		sum += uint32(b[len(b)-1]) << 8
	}
	for sum > 0xffff {
		sum = sum>>16 + sum&0xffff
	}
	return uint16(sum)
}
