package mc

import (
	"bufio"
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"math"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

// ---------------------------------------------------------------- servers.dat

// Server is one entry of the game's multiplayer list (servers.dat).
type Server struct {
	Name string `json:"name"`
	IP   string `json:"ip"`
	Icon string `json:"icon,omitempty"` // base64 PNG cached by the game
	raw  map[string]any
}

func serversPath(runDir string) string { return filepath.Join(runDir, "servers.dat") }

// ListServers reads the instance's multiplayer list.
func ListServers(runDir string) ([]Server, error) {
	m, err := ReadNBT(serversPath(runDir))
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, nil
		}
		return nil, err
	}
	list, _ := m["servers"].([]any)
	var out []Server
	for _, it := range list {
		c, ok := it.(map[string]any)
		if !ok {
			continue
		}
		s := Server{raw: c}
		s.Name, _ = c["name"].(string)
		s.IP, _ = c["ip"].(string)
		s.Icon, _ = c["icon"].(string)
		if s.IP != "" {
			out = append(out, s)
		}
	}
	return out, nil
}

// SaveServers writes the list back (fields the launcher doesn't know are kept).
func SaveServers(runDir string, list []Server) error {
	var items []any
	for _, s := range list {
		c := map[string]any{}
		for k, v := range s.raw {
			if v != nil {
				c[k] = v
			}
		}
		c["name"], c["ip"] = s.Name, s.IP
		if s.Icon != "" {
			c["icon"] = s.Icon
		}
		items = append(items, c)
	}
	var buf bytes.Buffer
	w := &nbtWriter{w: &buf}
	w.u8(10)
	w.str("")
	w.payload(map[string]any{"servers": items})
	if w.err != nil {
		return w.err
	}
	p := serversPath(runDir)
	if err := os.MkdirAll(runDir, 0o755); err != nil {
		return err
	}
	if _, err := os.Stat(p); err == nil {
		_ = copyFile(p, p+"_old") // the game keeps the same kind of backup
	}
	tmp := p + ".tmp"
	if err := os.WriteFile(tmp, buf.Bytes(), 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, p)
}

// AddServer appends (or renames) an entry.
func AddServer(runDir, name, ip string) error {
	ip = strings.TrimSpace(ip)
	if !ValidServer(ip) {
		return errors.New("服务器地址不正确，例如 mc.example.com 或 1.2.3.4:25565")
	}
	if name = strings.TrimSpace(name); name == "" {
		name = "Minecraft 服务器"
	}
	list, err := ListServers(runDir)
	if err != nil {
		return err
	}
	for i := range list {
		if strings.EqualFold(list[i].IP, ip) {
			list[i].Name = name
			return SaveServers(runDir, list)
		}
	}
	list = append(list, Server{Name: name, IP: ip, raw: map[string]any{"acceptTextures": int8(0)}})
	return SaveServers(runDir, list)
}

func RemoveServer(runDir, ip string) error {
	list, err := ListServers(runDir)
	if err != nil {
		return err
	}
	out := list[:0]
	for _, s := range list {
		if !strings.EqualFold(s.IP, ip) {
			out = append(out, s)
		}
	}
	return SaveServers(runDir, out)
}

// ValidServer accepts host, host:port, IPv4 and [IPv6]:port.
func ValidServer(s string) bool {
	if reServer.MatchString(s) {
		return true
	}
	h, p, err := net.SplitHostPort(s)
	if err != nil {
		return net.ParseIP(strings.Trim(s, "[]")) != nil
	}
	n, err := strconv.Atoi(p)
	return err == nil && n > 0 && n < 65536 && net.ParseIP(h) != nil
}

// ---------------------------------------------------------------- NBT writer

type nbtWriter struct {
	w   io.Writer
	err error
}

func (n *nbtWriter) write(b []byte) {
	if n.err == nil {
		_, n.err = n.w.Write(b)
	}
}
func (n *nbtWriter) u8(b byte) { n.write([]byte{b}) }
func (n *nbtWriter) i16(v int16) {
	var b [2]byte
	binary.BigEndian.PutUint16(b[:], uint16(v))
	n.write(b[:])
}
func (n *nbtWriter) i32(v int32) {
	var b [4]byte
	binary.BigEndian.PutUint32(b[:], uint32(v))
	n.write(b[:])
}
func (n *nbtWriter) i64(v int64) {
	var b [8]byte
	binary.BigEndian.PutUint64(b[:], uint64(v))
	n.write(b[:])
}
func (n *nbtWriter) str(s string) {
	if len(s) > math.MaxUint16 {
		s = s[:math.MaxUint16]
	}
	n.i16(int16(uint16(len(s))))
	n.write([]byte(s))
}

func nbtType(v any) byte {
	switch v.(type) {
	case int8:
		return 1
	case int16:
		return 2
	case int32:
		return 3
	case int64:
		return 4
	case float32:
		return 5
	case float64:
		return 6
	case string:
		return 8
	case []any:
		return 9
	case map[string]any:
		return 10
	case []int32:
		return 11
	case []int64:
		return 12
	}
	return 0
}

func (n *nbtWriter) payload(v any) {
	switch x := v.(type) {
	case int8:
		n.u8(byte(x))
	case int16:
		n.i16(x)
	case int32:
		n.i32(x)
	case int64:
		n.i64(x)
	case float32:
		n.i32(int32(math.Float32bits(x)))
	case float64:
		n.i64(int64(math.Float64bits(x)))
	case string:
		n.str(x)
	case []any:
		et := byte(0)
		if len(x) > 0 {
			et = nbtType(x[0])
		}
		n.u8(et)
		n.i32(int32(len(x)))
		for _, e := range x {
			n.payload(e)
		}
	case map[string]any:
		keys := make([]string, 0, len(x))
		for k := range x {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			t := nbtType(x[k])
			if t == 0 {
				continue
			}
			n.u8(t)
			n.str(k)
			n.payload(x[k])
		}
		n.u8(0)
	case []int32:
		n.i32(int32(len(x)))
		for _, e := range x {
			n.i32(e)
		}
	case []int64:
		n.i32(int32(len(x)))
		for _, e := range x {
			n.i64(e)
		}
	}
}

// ---------------------------------------------------------------- server list ping

// ServerStatus is what a server answers to the multiplayer screen's ping.
type ServerStatus struct {
	Online    bool     `json:"online"`
	MOTD      string   `json:"motd"` // with § formatting codes
	Version   string   `json:"version,omitempty"`
	Protocol  int      `json:"protocol,omitempty"`
	Players   int      `json:"players"`
	Max       int      `json:"max"`
	Sample    []string `json:"sample,omitempty"`
	Favicon   string   `json:"favicon,omitempty"` // data:image/png;base64,…
	Ping      int      `json:"ping"`              // ms
	Error     string   `json:"error,omitempty"`
	Resolved  string   `json:"resolved,omitempty"`
	CheckedAt int64    `json:"checkedAt"`
}

func writeVarInt(w *bytes.Buffer, v int32) {
	u := uint32(v)
	for {
		if u&^0x7f == 0 {
			w.WriteByte(byte(u))
			return
		}
		w.WriteByte(byte(u&0x7f | 0x80))
		u >>= 7
	}
}

func readVarInt(r io.ByteReader) (int32, error) {
	var out uint32
	for i := 0; i < 5; i++ {
		b, err := r.ReadByte()
		if err != nil {
			return 0, err
		}
		out |= uint32(b&0x7f) << (7 * i)
		if b&0x80 == 0 {
			return int32(out), nil
		}
	}
	return 0, errors.New("varint too long")
}

func packet(id int32, body []byte) []byte {
	var p bytes.Buffer
	writeVarInt(&p, id)
	p.Write(body)
	var out bytes.Buffer
	writeVarInt(&out, int32(p.Len()))
	out.Write(p.Bytes())
	return out.Bytes()
}

// resolveServer applies the default port and the _minecraft._tcp SRV record.
func resolveServer(ctx context.Context, addr string) (host string, port int) {
	host, port = addr, 25565
	if h, p, err := net.SplitHostPort(addr); err == nil {
		n, _ := strconv.Atoi(p)
		return h, n
	}
	host = strings.Trim(host, "[]")
	if net.ParseIP(host) == nil {
		var r net.Resolver
		sctx, cancel := context.WithTimeout(ctx, 2*time.Second)
		defer cancel()
		if _, srvs, err := r.LookupSRV(sctx, "minecraft", "tcp", host); err == nil && len(srvs) > 0 {
			return strings.TrimSuffix(srvs[0].Target, "."), int(srvs[0].Port)
		}
	}
	return host, port
}

// PingServer performs the 1.7+ status handshake and measures the round trip.
func PingServer(ctx context.Context, addr string) ServerStatus {
	st := ServerStatus{CheckedAt: time.Now().Unix()}
	host, port := resolveServer(ctx, addr)
	st.Resolved = net.JoinHostPort(host, strconv.Itoa(port))
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	var d net.Dialer
	conn, err := d.DialContext(ctx, "tcp", st.Resolved)
	if err != nil {
		st.Error = "无法连接"
		var ne net.Error
		if errors.As(err, &ne) && ne.Timeout() {
			st.Error = "连接超时"
		}
		return st
	}
	defer conn.Close()
	if dl, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(dl)
	}
	// handshake: protocol -1 (status), host, port, next state 1
	var hs bytes.Buffer
	writeVarInt(&hs, -1)
	writeVarInt(&hs, int32(len(host)))
	hs.WriteString(host)
	_ = binary.Write(&hs, binary.BigEndian, uint16(port))
	writeVarInt(&hs, 1)
	if _, err := conn.Write(append(packet(0, hs.Bytes()), packet(0, nil)...)); err != nil {
		st.Error = "连接被拒绝"
		return st
	}
	br := bufio.NewReader(conn)
	if _, err := readVarInt(br); err != nil { // packet length
		st.Error = "服务器没有回应"
		return st
	}
	if id, err := readVarInt(br); err != nil || id != 0 {
		st.Error = "不是 Minecraft 服务器"
		return st
	}
	n, err := readVarInt(br)
	if err != nil || n <= 0 || n > 1<<21 {
		st.Error = "回应格式错误"
		return st
	}
	js := make([]byte, n)
	if _, err := io.ReadFull(br, js); err != nil {
		st.Error = "回应不完整"
		return st
	}
	var resp struct {
		Version struct {
			Name     string `json:"name"`
			Protocol int    `json:"protocol"`
		} `json:"version"`
		Players struct {
			Max    int `json:"max"`
			Online int `json:"online"`
			Sample []struct {
				Name string `json:"name"`
			} `json:"sample"`
		} `json:"players"`
		Description json.RawMessage `json:"description"`
		Favicon     string          `json:"favicon"`
	}
	if err := json.Unmarshal(js, &resp); err != nil {
		st.Error = "回应格式错误"
		return st
	}
	st.Online = true
	st.Version, st.Protocol = resp.Version.Name, resp.Version.Protocol
	st.Players, st.Max = resp.Players.Online, resp.Players.Max
	for _, s := range resp.Players.Sample {
		if len(st.Sample) < 12 && s.Name != "" {
			st.Sample = append(st.Sample, s.Name)
		}
	}
	if strings.HasPrefix(resp.Favicon, "data:image/png;base64,") && len(resp.Favicon) < 200<<10 {
		st.Favicon = resp.Favicon
	}
	st.MOTD = chatText(resp.Description)
	// latency: ping packet with a timestamp
	var pb bytes.Buffer
	_ = binary.Write(&pb, binary.BigEndian, time.Now().UnixMilli())
	t0 := time.Now()
	if _, err := conn.Write(packet(1, pb.Bytes())); err == nil {
		if _, err := readVarInt(br); err == nil {
			if id, err := readVarInt(br); err == nil && id == 1 {
				var b [8]byte
				if _, err := io.ReadFull(br, b[:]); err == nil {
					st.Ping = int(time.Since(t0).Milliseconds())
				}
			}
		}
	}
	if st.Ping == 0 {
		st.Ping = max(1, int(time.Since(t0).Milliseconds()))
	}
	return st
}

var chatColors = map[string]string{"black": "0", "dark_blue": "1", "dark_green": "2", "dark_aqua": "3", "dark_red": "4",
	"dark_purple": "5", "gold": "6", "gray": "7", "dark_gray": "8", "blue": "9", "green": "a", "aqua": "b",
	"red": "c", "light_purple": "d", "yellow": "e", "white": "f"}

// chatText flattens a chat component into legacy §-formatted text.
func chatText(raw json.RawMessage) string {
	var s string
	if json.Unmarshal(raw, &s) == nil {
		return s
	}
	var b strings.Builder
	var walk func(raw json.RawMessage, depth int)
	walk = func(raw json.RawMessage, depth int) {
		if depth > 16 || b.Len() > 4096 {
			return
		}
		var s string
		if json.Unmarshal(raw, &s) == nil {
			b.WriteString(s)
			return
		}
		var arr []json.RawMessage
		if json.Unmarshal(raw, &arr) == nil {
			for _, x := range arr {
				walk(x, depth+1)
			}
			return
		}
		var c struct {
			Text          string            `json:"text"`
			Translate     string            `json:"translate"`
			Color         string            `json:"color"`
			Bold          bool              `json:"bold"`
			Italic        bool              `json:"italic"`
			Underlined    bool              `json:"underlined"`
			Strikethrough bool              `json:"strikethrough"`
			Extra         []json.RawMessage `json:"extra"`
		}
		if json.Unmarshal(raw, &c) != nil {
			return
		}
		code := chatColors[c.Color]
		if strings.HasPrefix(c.Color, "#") && len(c.Color) == 7 {
			code = "#" + c.Color[1:] // hex colour, rendered by the UI
		}
		if code != "" {
			b.WriteString("§" + code)
		}
		if c.Bold {
			b.WriteString("§l")
		}
		if c.Italic {
			b.WriteString("§o")
		}
		if c.Underlined {
			b.WriteString("§n")
		}
		if c.Strikethrough {
			b.WriteString("§m")
		}
		if c.Text != "" {
			b.WriteString(c.Text)
		} else {
			b.WriteString(c.Translate)
		}
		for _, x := range c.Extra {
			walk(x, depth+1)
		}
		if code != "" || c.Bold || c.Italic || c.Underlined || c.Strikethrough {
			b.WriteString("§r")
		}
	}
	walk(raw, 0)
	return b.String()
}

// PingAll pings every address concurrently.
func PingAll(ctx context.Context, addrs []string) map[string]ServerStatus {
	out := make(map[string]ServerStatus, len(addrs))
	type r struct {
		a string
		s ServerStatus
	}
	ch := make(chan r, len(addrs))
	sem := make(chan struct{}, 8)
	for _, a := range addrs {
		go func(a string) {
			sem <- struct{}{}
			defer func() { <-sem }()
			ch <- r{a, PingServer(ctx, a)}
		}(a)
	}
	for range addrs {
		x := <-ch
		out[x.a] = x.s
	}
	return out
}
