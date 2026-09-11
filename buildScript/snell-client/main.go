// ArcaenBox's loopback-only Snell adapter. Routing and socket protection stay in sing-box.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"time"

	snell "github.com/sagernet/sing-snell"
	"github.com/sagernet/sing-snell/snellv4"
	"github.com/sagernet/sing-snell/snellv6"
	SB "github.com/sagernet/sing/common/bufio"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/protocol/socks"
)

var version = "sing-snell bc5a12ac736f / adapter 1"

// Accept the same restricted configuration and arguments as the stable Snell adapter.
type config struct {
	Port    int     `json:"socks-port"`
	Host    string  `json:"bind-address"`
	LAN     bool    `json:"allow-lan"`
	Proxies []proxy `json:"proxies"`
}
type proxy struct {
	Server  string `json:"server"`
	Port    int    `json:"port"`
	PSK     string `json:"psk"`
	Version int    `json:"version"`
	Mode    string `json:"snell-mode"`
	UDP     bool   `json:"udp"`
	Reuse   bool   `json:"reuse"`
	Obfs    struct {
		Mode string `json:"mode"`
		Host string `json:"host"`
	} `json:"obfs-opts"`
}
type client interface {
	snell.Method
	DialContext(context.Context, M.Socksaddr) (net.Conn, error)
	Close() error
}
type handler struct {
	client client
	server M.Socksaddr
	dialer N.Dialer
	udp    bool
}

func newHandler(p proxy) (*handler, error) {
	// App-generated endpoints must go through sing-box's protected loopback mapping.
	ip := net.ParseIP(p.Server)
	if ip == nil || !ip.IsLoopback() || p.Port < 1 || p.Port > 65535 || p.PSK == "" {
		return nil, errors.New("invalid mapped Snell endpoint")
	}
	d := &N.DefaultDialer{Dialer: net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}}
	h := &handler{server: M.ParseSocksaddr(net.JoinHostPort(p.Server, fmt.Sprint(p.Port))), dialer: d, udp: p.UDP}
	var err error
	switch p.Version {
	case 4, 5:
		if p.Mode != "" && p.Mode != "default" {
			return nil, errors.New("mode requires Snell 6")
		}
		obfs, e := snell.ParseObfsMode(p.Obfs.Mode)
		if e != nil {
			return nil, e
		}
		h.client, err = snellv4.NewClient(snellv4.ClientOptions{PSK: []byte(p.PSK), Reuse: p.Reuse, ObfsMode: obfs, ObfsHost: p.Obfs.Host, Dialer: d, Server: h.server})
	case 6:
		if len([]byte(p.PSK)) < 12 || len([]byte(p.PSK)) > 255 {
			return nil, errors.New("Snell 6 PSK must be 12 to 255 bytes")
		}
		if p.Obfs.Mode != "" && p.Obfs.Mode != "none" {
			return nil, errors.New("Snell 6 does not support obfs")
		}
		mode, e := snellv6.ParseMode(p.Mode)
		if e != nil {
			return nil, e
		}
		h.client, err = snellv6.NewClient(snellv6.ClientOptions{PSK: []byte(p.PSK), Mode: mode, Reuse: p.Reuse, Dialer: d, Server: h.server})
	default:
		return nil, errors.New("Snell version must be 4, 5 or 6")
	}
	return h, err
}

func (h *handler) NewConnectionEx(ctx context.Context, conn net.Conn, source, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	defer conn.Close()
	conn.SetReadDeadline(time.Time{})
	upstream, err := h.client.DialContext(ctx, destination)
	if err == nil {
		defer upstream.Close()
		err = SB.CopyConn(ctx, conn, upstream)
	}
	if onClose != nil {
		onClose(err)
	}
}
func (h *handler) NewPacketConnectionEx(ctx context.Context, conn N.PacketConn, source, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	defer conn.Close()
	conn.SetReadDeadline(time.Time{})
	var err error
	if !h.udp {
		err = errors.New("UDP disabled")
	} else {
		var stream net.Conn
		stream, err = h.dialer.DialContext(ctx, N.NetworkTCP, h.server)
		if err == nil {
			defer stream.Close()
			var upstream N.NetPacketConn
			upstream, err = h.client.DialPacketConn(stream)
			if err == nil {
				defer upstream.Close()
				err = SB.CopyPacketConn(ctx, conn, upstream)
			}
		}
	}
	if onClose != nil {
		onClose(err)
	}
}

func run(c config) error {
	if c.Host != "127.0.0.1" || c.LAN || c.Port < 1 || c.Port > 65535 || len(c.Proxies) != 1 {
		return errors.New("invalid loopback listener")
	}
	h, err := newHandler(c.Proxies[0])
	if err != nil {
		return err
	}
	defer h.client.Close()
	listener, err := net.Listen("tcp", net.JoinHostPort(c.Host, fmt.Sprint(c.Port)))
	if err != nil {
		return err
	}
	defer listener.Close()
	for {
		conn, err := listener.Accept()
		if err != nil {
			return err
		}
		go func() {
			err := socks.HandleConnectionEx(context.Background(), conn, bufio.NewReader(conn), nil, h, nil, 5*time.Minute, M.SocksaddrFromNet(conn.RemoteAddr()), nil)
			if err != nil {
				conn.Close()
				log.Printf("SOCKS handshake: %v", err)
			}
		}()
	}
}
func main() {
	file := flag.String("f", "", "configuration path")
	flag.String("d", "", "reserved working directory")
	show := flag.Bool("version", false, "show component version")
	flag.Parse()
	if *show {
		fmt.Println(version)
		return
	}
	data, err := os.ReadFile(*file)
	if err != nil {
		log.Fatal(err)
	}
	var c config
	if err = json.Unmarshal(data, &c); err != nil {
		log.Fatal(err)
	}
	if err = run(c); err != nil {
		log.Fatal(err)
	}
}
