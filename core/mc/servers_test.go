package mc

import (
	"bufio"
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"io"
	"net"
	"strconv"
	"testing"
)

func TestServersRoundTrip(t *testing.T) {
	dir := t.TempDir()
	if err := AddServer(dir, "樱梨服", "play.example.com"); err != nil {
		t.Fatal(err)
	}
	if err := AddServer(dir, "", "1.2.3.4:25570"); err != nil {
		t.Fatal(err)
	}
	if err := AddServer(dir, "x", "bad host!"); err == nil {
		t.Fatal("bad address accepted")
	}
	// rename keeps one entry
	if err := AddServer(dir, "樱梨服务器", "PLAY.example.com"); err != nil {
		t.Fatal(err)
	}
	l, err := ListServers(dir)
	if err != nil || len(l) != 2 || l[0].Name != "樱梨服务器" || l[1].IP != "1.2.3.4:25570" {
		t.Fatalf("%v %+v", err, l)
	}
	if v, _ := l[0].raw["acceptTextures"].(int8); v != 0 || l[0].raw["acceptTextures"] == nil {
		t.Fatalf("lost field: %#v", l[0].raw)
	}
	if err := RemoveServer(dir, "play.example.com"); err != nil {
		t.Fatal(err)
	}
	l, _ = ListServers(dir)
	if len(l) != 1 {
		t.Fatalf("%+v", l)
	}
}

func fakeServer(t *testing.T, status string) string {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				br := bufio.NewReader(c)
				for i := 0; i < 3; i++ {
					n, err := readVarInt(br)
					if err != nil {
						return
					}
					b := make([]byte, n)
					if _, err := io.ReadFull(br, b); err != nil {
						return
					}
					id, _ := readVarInt(bytes.NewReader(b))
					switch {
					case i == 1 && id == 0:
						var body bytes.Buffer
						writeVarInt(&body, int32(len(status)))
						body.WriteString(status)
						c.Write(packet(0, body.Bytes()))
					case i == 2 && id == 1:
						c.Write(packet(1, b[1:]))
					}
				}
			}(c)
		}
	}()
	return ln.Addr().String()
}

func TestPingServer(t *testing.T) {
	js, _ := json.Marshal(map[string]any{
		"version": map[string]any{"name": "1.21.1", "protocol": 767},
		"players": map[string]any{"max": 100, "online": 7, "sample": []any{map[string]any{"name": "D0cK"}}},
		"description": map[string]any{"text": "", "extra": []any{
			map[string]any{"text": "樱梨", "color": "light_purple", "bold": true}, " 欢迎"}},
	})
	addr := fakeServer(t, string(js))
	st := PingServer(context.Background(), addr)
	if !st.Online || st.Players != 7 || st.Max != 100 || st.Version != "1.21.1" || len(st.Sample) != 1 {
		t.Fatalf("%+v", st)
	}
	if st.MOTD != "§d§l樱梨§r 欢迎" {
		t.Fatalf("motd %q", st.MOTD)
	}
	// nothing listening
	l, _ := net.Listen("tcp", "127.0.0.1:0")
	port := l.Addr().(*net.TCPAddr).Port
	l.Close()
	st = PingServer(context.Background(), "127.0.0.1:"+strconv.Itoa(port))
	if st.Online || st.Error == "" {
		t.Fatalf("%+v", st)
	}
	_ = binary.BigEndian
}
