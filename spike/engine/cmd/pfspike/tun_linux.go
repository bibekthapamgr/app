//go:build linux

package main

import (
	"fmt"
	"unsafe"

	"golang.org/x/sys/unix"
)

// openTUN attaches to (or creates) a TUN interface with IFF_TUN|IFF_NO_PI —
// the same packet format as Android's VpnService file descriptor.
func openTUN(name string) (int, error) {
	fd, err := unix.Open("/dev/net/tun", unix.O_RDWR|unix.O_CLOEXEC, 0)
	if err != nil {
		return -1, err
	}
	var ifr [unix.IFNAMSIZ + 64]byte
	if len(name) >= unix.IFNAMSIZ {
		unix.Close(fd)
		return -1, fmt.Errorf("interface name too long")
	}
	copy(ifr[:], name)
	*(*uint16)(unsafe.Pointer(&ifr[unix.IFNAMSIZ])) = unix.IFF_TUN | unix.IFF_NO_PI
	if _, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(fd), uintptr(unix.TUNSETIFF), uintptr(unsafe.Pointer(&ifr[0]))); errno != 0 {
		unix.Close(fd)
		return -1, fmt.Errorf("TUNSETIFF: %w", errno)
	}
	if err := unix.SetNonblock(fd, true); err != nil {
		unix.Close(fd)
		return -1, err
	}
	return fd, nil
}
