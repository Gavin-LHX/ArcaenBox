//go:build linux || android

// Package protect transfers sockets to the VPN process before they connect.
package protect

import (
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"syscall"
	"time"
)

const timeout = 2 * time.Second

// Send waits for the VPN process to confirm that it protected the transferred fd.
// No listener means the application's VPN is not running.
func Send(path string, fd int) error {
	c, err := net.DialTimeout("unix", path, timeout)
	if err != nil {
		if errors.Is(err, syscall.ENOENT) || errors.Is(err, syscall.ECONNREFUSED) {
			return nil
		}
		return fmt.Errorf("connect VPN socket protector: %w", err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(timeout))
	u := c.(*net.UnixConn)
	if _, _, err = u.WriteMsgUnix([]byte{1}, syscall.UnixRights(fd), nil); err != nil {
		return fmt.Errorf("send VPN socket: %w", err)
	}
	ack := []byte{0}
	if _, err = io.ReadFull(u, ack); err != nil {
		return fmt.Errorf("receive VPN protection result: %w", err)
	}
	if ack[0] != 1 {
		return errors.New("VPN socket protection was rejected")
	}
	return nil
}

func Serve(path string, control func(int) error) (io.Closer, error) {
	// The application owns this socket in its private no_backup directory.
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return nil, err
	}
	l, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return nil, err
	}
	if err = os.Chmod(path, 0600); err != nil {
		l.Close()
		return nil, err
	}
	go func() {
		for {
			c, err := l.AcceptUnix()
			if err != nil {
				return
			}
			go handle(c, control)
		}
	}()
	return l, nil
}

func handle(c *net.UnixConn, control func(int) error) {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(timeout))
	payload, oob := make([]byte, 1), make([]byte, syscall.CmsgSpace(4))
	// ReadMsgUnix uses Go's network poller. A raw Recvmsg on the accepted
	// nonblocking socket can return EAGAIN before the sender supplies the fd.
	n, oobn, flags, _, err := c.ReadMsgUnix(payload, oob)
	if err != nil {
		return
	}
	msgs, err := syscall.ParseSocketControlMessage(oob[:oobn])
	if err != nil {
		return
	}
	var descriptors []int
	for _, msg := range msgs {
		fds, parseErr := syscall.ParseUnixRights(&msg)
		if parseErr != nil {
			err = parseErr
		}
		descriptors = append(descriptors, fds...)
	}
	defer func() {
		for _, fd := range descriptors {
			syscall.Close(fd)
		}
	}()
	ack := byte(0)
	if err == nil && n == 1 && flags&syscall.MSG_CTRUNC == 0 && len(descriptors) == 1 {
		if control(descriptors[0]) == nil {
			ack = 1
		}
	}
	_, _ = c.Write([]byte{ack})
}
