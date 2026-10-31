package proxytest

import (
	"bytes"
	"fmt"
	"net"
	"os"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"testing"
	"time"
)

// CheckLeaks fails the test if goroutines or file descriptors opened during it
// are still alive when it ends. Call it first: its check runs after every
// Cleanup registered later (fixtures close themselves first). Tests using it
// must not run in parallel with other tests.
func CheckLeaks(t testing.TB) {
	t.Helper()
	base := takeSnapshot()
	t.Cleanup(func() {
		deadline := time.Now().Add(5 * time.Second)
		for {
			leakedG, leakedFD := base.leaks()
			if len(leakedG) == 0 && len(leakedFD) == 0 {
				return
			}
			if time.Now().After(deadline) {
				for _, g := range leakedG {
					t.Errorf("leaked goroutine:\n%s", g)
				}
				for _, f := range leakedFD {
					t.Errorf("leaked file descriptor: %s", f)
				}
				return
			}
			time.Sleep(20 * time.Millisecond)
		}
	})
}

// snapshot is the goroutines and descriptors alive at one moment.
type snapshot struct {
	g  []string
	fd map[int]string
}

func takeSnapshot() snapshot {
	warmUpNetpoll()
	return snapshot{g: goroutines(), fd: openFDs()}
}

// leaks returns goroutines and descriptors alive now that were not in s.
func (s snapshot) leaks() (g, fd []string) {
	return newGoroutines(s.g, goroutines()), newFDs(s.fd, openFDs())
}

// LiveGoroutines returns the stacks of goroutines that belong to the code
// under test (not the runtime or the testing package).
func LiveGoroutines() []string {
	var out []string
	for _, g := range goroutines() {
		if !ignorable(g) {
			out = append(out, g)
		}
	}
	return out
}

// FDCount returns the number of open file descriptors, or -1 when the
// platform has no /proc/self/fd.
func FDCount() int {
	fds := openFDs()
	if fds == nil {
		return -1
	}
	return len(fds)
}

// warmUpNetpoll makes the runtime create its netpoller descriptors before the
// baseline is taken, so the first network use in a test is not a "leak".
func warmUpNetpoll() {
	if ln, err := net.Listen("tcp4", "127.0.0.1:0"); err == nil {
		ln.Close()
	}
}

// goroutines returns one stack per goroutine, keyed implicitly by its header.
func goroutines() []string {
	buf := make([]byte, 1<<20)
	for {
		n := runtime.Stack(buf, true)
		if n < len(buf) {
			buf = buf[:n]
			break
		}
		buf = make([]byte, 2*len(buf))
	}
	var out []string
	for _, g := range bytes.Split(buf, []byte("\n\n")) {
		if s := strings.TrimSpace(string(g)); s != "" {
			out = append(out, s)
		}
	}
	return out
}

func goroutineID(stack string) string {
	// "goroutine 42 [running]:"
	f := strings.Fields(stack)
	if len(f) >= 2 {
		return f[1]
	}
	return ""
}

var ignoredFrames = []string{
	"testing.(*T).Run",
	"testing.tRunner",
	"testing.(*M).",
	"testing.runTests",
	"testing.runFuzzing",
	"testing.(*F).",
	"os/signal.",
	"runtime.ensureSigM",
	"runtime.goexit0",
}

func ignorable(stack string) bool {
	if strings.Contains(stack, "proxytest.goroutines") {
		return true // the goroutine taking the snapshot
	}
	for _, f := range ignoredFrames {
		if strings.Contains(stack, f) {
			return true
		}
	}
	return false
}

func newGoroutines(base, now []string) []string {
	seen := map[string]bool{}
	for _, g := range base {
		seen[goroutineID(g)] = true
	}
	var out []string
	for _, g := range now {
		if !seen[goroutineID(g)] && !ignorable(g) {
			out = append(out, g)
		}
	}
	return out
}

// openFDs maps descriptor number to its target, or nil without /proc.
func openFDs() map[int]string {
	entries, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		return nil
	}
	out := map[int]string{}
	for _, e := range entries {
		n, err := strconv.Atoi(e.Name())
		if err != nil {
			continue
		}
		target, err := os.Readlink("/proc/self/fd/" + e.Name())
		if err != nil {
			continue // closed between ReadDir and Readlink: the directory's own fd
		}
		if strings.HasPrefix(target, "/proc/") && strings.HasSuffix(target, "/fd") {
			continue // the descriptor ReadDir itself used
		}
		out[n] = target
	}
	return out
}

func newFDs(base, now map[int]string) []string {
	if base == nil || now == nil {
		return nil
	}
	var out []string
	for n, target := range now {
		if base[n] != target {
			out = append(out, fmt.Sprintf("%d -> %s", n, target))
		}
	}
	sort.Strings(out)
	return out
}
