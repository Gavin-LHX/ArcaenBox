//go:build linux || android

package protect

import (
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"
)

func TestDelayedAndConcurrentTransfer(t *testing.T) {
	path := filepath.Join(t.TempDir(), "protect")
	var calls atomic.Int32
	l, err := Serve(path, func(fd int) error {
		_, err := syscall.GetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_TYPE)
		if err == nil {
			calls.Add(1)
		}
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	fd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_STREAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer syscall.Close(fd)
	c, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	// Reproduce the old server's accept/Recvmsg race deterministically.
	time.Sleep(100 * time.Millisecond)
	if _, _, err = c.WriteMsgUnix([]byte{1}, syscall.UnixRights(fd), nil); err != nil {
		t.Fatal(err)
	}
	ack := []byte{0}
	if _, err = io.ReadFull(c, ack); err != nil || ack[0] != 1 {
		t.Fatalf("ack %v: %v", ack, err)
	}
	var wg sync.WaitGroup
	for i := 0; i < 64; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := Send(path, fd); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if calls.Load() != 65 {
		t.Fatalf("protected %d sockets", calls.Load())
	}
	// Receiver only closes the duplicated descriptor, never the caller's socket.
	if _, err := syscall.GetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_TYPE); err != nil {
		t.Fatal(err)
	}
	if info, err := os.Stat(path); err != nil || info.Mode().Perm() != 0600 {
		t.Fatalf("socket permissions: %v %v", info, err)
	}
}

func TestProtectionFailureIsNotIgnored(t *testing.T) {
	path := filepath.Join(t.TempDir(), "protect")
	l, err := Serve(path, func(int) error { return errors.New("VPN revoked") })
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	fd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer syscall.Close(fd)
	if err := Send(path, fd); err == nil {
		t.Fatal("rejected protection accepted")
	}
	if err := Send(path, -1); err == nil {
		t.Fatal("invalid descriptor accepted")
	}
}

func TestNoVPNListener(t *testing.T) {
	if err := Send(filepath.Join(t.TempDir(), "absent"), -1); err != nil {
		t.Fatal(err)
	}
}
