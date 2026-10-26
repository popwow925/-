package mc

import (
	"context"
	"crypto/sha1"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var UserAgent = AppName + "Launcher/" + AppVersion

// HTTP/1.1 with many parallel connections. HTTP/2 would squeeze every worker
// into one TCP connection per host, which is much slower on long-distance or
// lossy links — the main reason game downloads used to crawl.
var client = &http.Client{
	Transport: &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		DialContext:           (&net.Dialer{Timeout: 8 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
		MaxIdleConns:          512,
		MaxIdleConnsPerHost:   128,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   8 * time.Second,
		ForceAttemptHTTP2:     false,
		TLSNextProto:          map[string]func(string, *tls.Conn) http.RoundTripper{},
		ReadBufferSize:        64 << 10,
		ResponseHeaderTimeout: 15 * time.Second,
	},
}

// Tunables (variables so tests can shorten them).
var (
	StallTimeout   = 8 * time.Second // no bytes for this long: abandon the source
	SlowAfter      = 6 * time.Second // judge throughput after this long…
	SlowBytesPerS  = int64(24 << 10) // …and give up below this rate when another source exists
	RangeThreshold = int64(3 << 20)  // files larger than this are fetched in parallel segments
	RangeParts     = 6
)

// ---------------------------------------------------------------- mirrors

const bmcl = "https://bmclapi2.bangbang93.com/"

var bmclMap = [][2]string{
	{"https://launchermeta.mojang.com/", bmcl},
	{"https://launcher.mojang.com/", bmcl},
	{"https://piston-meta.mojang.com/", bmcl},
	{"https://piston-data.mojang.com/", bmcl},
	{"https://resources.download.minecraft.net/", bmcl + "assets/"},
	{"https://libraries.minecraft.net/", bmcl + "maven/"},
	{"https://maven.fabricmc.net/", bmcl + "maven/"},
	{"https://meta.fabricmc.net/", bmcl + "fabric-meta/"},
	{"https://maven.quiltmc.org/repository/release/", bmcl + "maven/"},
	{"https://meta.quiltmc.org/", bmcl + "quilt-meta/"},
	{"https://maven.minecraftforge.net/", bmcl + "maven/"},
	{"https://files.minecraftforge.net/maven/", bmcl + "maven/"},
	{"https://maven.neoforged.net/releases/", bmcl + "maven/"},
	// MCIM (mcimirror.top): mainland-China mirror of the Modrinth API and file CDN
	{"https://api.modrinth.com/", mcim + "modrinth/"},
	{"https://cdn.modrinth.com/", mcim},
}

const mcim = "https://mod.mcimirror.top/"

// MirrorURL rewrites a URL to its mirror when the mirror is preferred (for images the UI loads).
func MirrorURL(u string) string {
	if c := Candidates(u); len(c) > 0 {
		return c[0]
	}
	return u
}

// Source is the configured download source: auto | official | bmclapi.
var Source atomic.Value

// autoPick is what "auto" resolved to after measuring both sources.
var autoPick atomic.Value

func init() {
	Source.Store("auto")
	autoPick.Store("")
}

func mirrorOf(u string) string {
	for _, m := range bmclMap {
		if strings.HasPrefix(u, m[0]) {
			return m[1] + strings.TrimPrefix(u, m[0])
		}
	}
	return ""
}

// ---------------------------------------------------------------- host health

type hostStat struct {
	fails    int32 // consecutive failures
	lastFail int64 // unix nano
	speed    int64 // smoothed bytes/s of recent transfers (0 = unknown)
}

func markSpeed(u string, n int64, d time.Duration) {
	if n < 128<<10 || d <= 0 {
		return
	}
	s := stat(host(u))
	v := int64(float64(n) / d.Seconds())
	old := atomic.LoadInt64(&s.speed)
	if old > 0 {
		v = (old*2 + v) / 3
	}
	atomic.StoreInt64(&s.speed, v)
}

func hostSpeed(u string) int64 { return atomic.LoadInt64(&stat(host(u)).speed) }

var health sync.Map // host -> *hostStat

func stat(h string) *hostStat {
	v, _ := health.LoadOrStore(h, &hostStat{})
	return v.(*hostStat)
}

func markOK(u string) { atomic.StoreInt32(&stat(host(u)).fails, 0) }
func markFail(u string) {
	s := stat(host(u))
	atomic.AddInt32(&s.fails, 1)
	atomic.StoreInt64(&s.lastFail, time.Now().UnixNano())
}

// sick: several failures in a row recently -> try this host last.
func sick(u string) bool {
	s := stat(host(u))
	return atomic.LoadInt32(&s.fails) >= 6 && time.Since(time.Unix(0, atomic.LoadInt64(&s.lastFail))) < 2*time.Minute
}

// Candidates returns the URLs to try, in order, for an official URL.
func Candidates(u string) []string {
	m := mirrorOf(u)
	if m == "" {
		return []string{u}
	}
	var list []string
	switch Source.Load().(string) {
	case "official":
		list = []string{u, m}
	case "bmclapi":
		list = []string{m, u}
	default:
		if autoPick.Load().(string) == "official" {
			list = []string{u, m}
		} else {
			list = []string{m, u}
		}
	}
	// learned order: a source that has proven clearly faster on this network goes first
	if a, b := hostSpeed(list[0]), hostSpeed(list[1]); a > 0 && b > 0 && b > a*3/2 {
		list[0], list[1] = list[1], list[0]
	}
	sort.SliceStable(list, func(i, j int) bool { return !sick(list[i]) && sick(list[j]) })
	return list
}

var probeOnce sync.Once
var probeDone = make(chan struct{})

// ProbeSources measures the official servers and BMCLAPI once and remembers the faster.
// Users in mainland China usually get BMCLAPI; users abroad get the official CDN.
func ProbeSources(ctx context.Context) string {
	probeOnce.Do(func() {
		go func() {
			defer close(probeDone)
			test := "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
			type res struct {
				name string
				d    time.Duration
			}
			ch := make(chan res, 2)
			for _, c := range []struct{ name, u string }{{"official", test}, {"bmclapi", mirrorOf(test)}} {
				go func(name, u string) {
					t0 := time.Now()
					cctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
					defer cancel()
					req, _ := newReq(cctx, "GET", u, nil)
					resp, err := client.Do(req)
					if err != nil {
						ch <- res{name, -1}
						return
					}
					_, err = io.Copy(io.Discard, resp.Body)
					resp.Body.Close()
					if err != nil || resp.StatusCode != 200 {
						ch <- res{name, -1}
						return
					}
					ch <- res{name, time.Since(t0)}
				}(c.name, c.u)
			}
			best, bestD := "bmclapi", time.Duration(-1)
			for i := 0; i < 2; i++ {
				r := <-ch
				if r.d >= 0 && (bestD < 0 || r.d < bestD) {
					best, bestD = r.name, r.d
				}
			}
			autoPick.Store(best)
		}()
	})
	select {
	case <-probeDone:
	case <-ctx.Done():
	case <-time.After(7 * time.Second):
	}
	return autoPick.Load().(string)
}

// AutoSource reports what "auto" currently resolves to ("" while unknown).
func AutoSource() string { return autoPick.Load().(string) }

func ensureProbe(ctx context.Context) {
	if Source.Load().(string) == "auto" && autoPick.Load().(string) == "" {
		ProbeSources(ctx)
	}
}

// ---------------------------------------------------------------- requests

func newReq(ctx context.Context, method, u string, body io.Reader) (*http.Request, error) {
	r, err := http.NewRequestWithContext(ctx, method, u, body)
	if err != nil {
		return nil, err
	}
	r.Header.Set("User-Agent", UserAgent)
	return r, nil
}

// GetBytes fetches a URL and returns the body. Requests are hedged: if the
// first source has not answered within 1.2 s, the next one is asked too and
// whichever answers first wins, so a slow mirror can no longer stall a step.
func GetBytes(ctx context.Context, u string) ([]byte, error) {
	ensureProbe(ctx)
	cands := Candidates(u)
	var last error
	for round := 0; round < 2; round++ {
		b, err := hedgedGet(ctx, cands)
		if err == nil {
			return b, nil
		}
		last = err
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
	}
	return nil, last
}

func hedgedGet(ctx context.Context, cands []string) ([]byte, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	type res struct {
		b   []byte
		err error
	}
	ch := make(chan res, len(cands))
	next, running := 0, 0
	start := func() {
		if next >= len(cands) {
			return
		}
		c := cands[next]
		next++
		running++
		go func() {
			t0 := time.Now()
			b, err := getOnce(ctx, c, 20*time.Second)
			if err == nil {
				markOK(c)
				markSpeed(c, int64(len(b)), time.Since(t0))
			} else if ctx.Err() == nil {
				markFail(c)
			}
			ch <- res{b, err}
		}()
	}
	start()
	hedge := time.NewTimer(1200 * time.Millisecond)
	defer hedge.Stop()
	var last error
	for running > 0 {
		select {
		case r := <-ch:
			running--
			if r.err == nil {
				return r.b, nil
			}
			last = r.err
			start() // failed fast: try the next source now
		case <-hedge.C:
			start()
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	return nil, last
}

func getOnce(ctx context.Context, u string, timeout time.Duration) ([]byte, error) {
	cctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	req, err := newReq(cctx, "GET", u, nil)
	if err != nil {
		return nil, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, netErr(u, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("%s 返回 HTTP %d", host(u), resp.StatusCode)
	}
	return io.ReadAll(resp.Body)
}

func GetJSON(ctx context.Context, u string, v any) error {
	b, err := GetBytes(ctx, u)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, v)
}

func host(u string) string {
	if p, err := url.Parse(u); err == nil && p.Host != "" {
		return p.Host
	}
	return shortURL(u)
}

// netErr turns Go's verbose *url.Error into a short Chinese message.
func netErr(u string, err error) error {
	var ue *url.Error
	if errors.As(err, &ue) {
		err = ue.Err
	}
	if errors.Is(err, errStall) || errors.Is(err, errSlow) {
		return fmt.Errorf("%s %v", host(u), err)
	}
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return fmt.Errorf("连接 %s 超时", host(u))
	}
	msg := err.Error()
	if len(msg) > 80 {
		msg = msg[:80] + "…"
	}
	return fmt.Errorf("无法连接 %s（%s）", host(u), msg)
}

func shortURL(u string) string {
	if i := strings.Index(u, "://"); i >= 0 {
		u = u[i+3:]
	}
	if len(u) > 80 {
		u = u[:40] + "…" + u[len(u)-30:]
	}
	return u
}

// ---------------------------------------------------------------- file downloads

// File is one download job.
type File struct {
	URL  string // official URL; mirrors derived automatically
	Path string
	SHA1 string
	Size int64
	Alt  []string // extra fallback URLs
}

// Present reports whether the file already exists with the right size
// (and hash, when deep is true).
func (f File) Present(deep bool) bool {
	st, err := os.Stat(f.Path)
	if err != nil || st.IsDir() {
		return false
	}
	if f.Size > 0 && st.Size() != f.Size {
		return false
	}
	if f.Size == 0 && st.Size() == 0 {
		return false
	}
	if deep && f.SHA1 != "" {
		h, err := fileSHA1(f.Path)
		return err == nil && strings.EqualFold(h, f.SHA1)
	}
	return true
}

func fileSHA1(p string) (string, error) {
	fh, err := os.Open(p)
	if err != nil {
		return "", err
	}
	defer fh.Close()
	h := sha1.New()
	if _, err := io.Copy(h, fh); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

var (
	errHash  = errors.New("校验失败")
	errStall = errors.New("长时间没有数据")
	errSlow  = errors.New("速度过慢")
)

type httpErr struct{ code int }

func (e httpErr) Error() string { return fmt.Sprintf("HTTP %d", e.code) }

func (f File) fetch(ctx context.Context, onBytes func(int64)) error {
	urls := append(Candidates(f.URL), f.Alt...)
	if err := os.MkdirAll(filepath.Dir(f.Path), 0o755); err != nil {
		return err
	}
	var last error
	// Each source gets one try per round: a failing or crawling source is
	// skipped straight away instead of being retried while others sit idle.
	for round := 0; round < 3; round++ {
		skip := 0
		if round == 0 && len(urls) >= 2 && f.Size <= RangeThreshold {
			err := f.hedged(ctx, urls[0], urls[1], onBytes)
			if err == nil {
				return nil
			}
			last = err
			if ctx.Err() != nil {
				return ctx.Err()
			}
			skip = 2
		}
		for i, u := range urls {
			if i < skip {
				continue
			}
			hasNext := len(urls) > 1
			var err error
			if f.Size > RangeThreshold {
				err = f.fetchRanged(ctx, u, onBytes)
			} else {
				err = f.fetchFrom(ctx, u, onBytes, hasNext && round == 0)
			}
			if err == nil {
				markOK(u)
				return nil
			}
			last = err
			if ctx.Err() != nil {
				return ctx.Err()
			}
			var he httpErr
			if !(errors.As(err, &he) && he.code == 404) {
				markFail(u)
			}
			_ = i
		}
		time.Sleep(time.Duration(400*(round+1)) * time.Millisecond)
	}
	return fmt.Errorf("下载失败 %s：%v", filepath.Base(f.Path), last)
}

// hedged downloads from a and, if a has not delivered much after 2 s (or
// fails), from b as well; the first complete, verified copy wins.
func (f File) hedged(ctx context.Context, a, b string, onBytes func(int64)) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var gotA int64
	ch := make(chan error, 2)
	go func() {
		ch <- f.fetchFrom(ctx, a, func(n int64) { atomic.AddInt64(&gotA, n); onBytes(n) }, false)
	}()
	startedB := false
	pending := 1
	startB := func() {
		if !startedB {
			startedB = true
			pending++
			go func() { ch <- f.fetchFrom(ctx, b, onBytes, false) }()
		}
	}
	timer := time.NewTimer(2 * time.Second)
	defer timer.Stop()
	var last error
	for pending > 0 {
		select {
		case err := <-ch:
			pending--
			if err == nil {
				cancel()
				for ; pending > 0; pending-- {
					<-ch // let the loser clean up (and take back its progress bytes)
				}
				return nil
			}
			last = err
			startB()
		case <-timer.C:
			// already most of the way there? then don't double the traffic
			if f.Size == 0 || atomic.LoadInt64(&gotA) < min(f.Size/2, 256<<10) {
				startB()
			}
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	return last
}

// watchdog cancels a transfer that stalls or crawls.
type watchdog struct {
	cancel   context.CancelFunc
	got      int64
	last     int64 // unix nano of the last read
	start    time.Time
	slowKill bool
	reason   atomic.Value
	stop     chan struct{}
}

func newWatchdog(cancel context.CancelFunc, slowKill bool) *watchdog {
	w := &watchdog{cancel: cancel, start: time.Now(), slowKill: slowKill, stop: make(chan struct{})}
	w.last = time.Now().UnixNano()
	go func() {
		t := time.NewTicker(250 * time.Millisecond)
		defer t.Stop()
		for {
			select {
			case <-w.stop:
				return
			case now := <-t.C:
				if now.Sub(time.Unix(0, atomic.LoadInt64(&w.last))) > StallTimeout {
					w.reason.Store(errStall)
					w.cancel()
					return
				}
				if w.slowKill {
					el := now.Sub(w.start)
					if el > SlowAfter && atomic.LoadInt64(&w.got)*int64(time.Second)/int64(el) < SlowBytesPerS {
						w.reason.Store(errSlow)
						w.cancel()
						return
					}
				}
			}
		}
	}()
	return w
}

func (w *watchdog) add(n int) {
	atomic.AddInt64(&w.got, int64(n))
	atomic.StoreInt64(&w.last, time.Now().UnixNano())
}

func (w *watchdog) done() { close(w.stop) }

func (w *watchdog) err(e error) error {
	if r, ok := w.reason.Load().(error); ok {
		return r
	}
	return e
}

func (f File) fetchFrom(ctx context.Context, u string, onBytes func(int64), slowKill bool) error {
	t0 := time.Now()
	cctx, cancel := context.WithCancel(ctx)
	defer cancel()
	wd := newWatchdog(cancel, slowKill && f.Size > 256<<10)
	defer wd.done()
	req, err := newReq(cctx, "GET", u, nil)
	if err != nil {
		return err
	}
	resp, err := client.Do(req)
	if err != nil {
		return netErr(u, wd.err(err))
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return httpErr{resp.StatusCode}
	}
	tmp := f.Path + ".part-" + partTag(u)
	out, err := os.Create(tmp)
	if err != nil {
		return err
	}
	h := sha1.New()
	var n int64
	buf := make([]byte, 64<<10)
	fail := func(e error) error {
		out.Close()
		os.Remove(tmp)
		if onBytes != nil && n > 0 {
			onBytes(-n)
		}
		return e
	}
	for {
		k, rerr := resp.Body.Read(buf)
		if k > 0 {
			wd.add(k)
			if _, werr := out.Write(buf[:k]); werr != nil {
				return fail(werr)
			}
			h.Write(buf[:k])
			n += int64(k)
			if onBytes != nil {
				onBytes(int64(k))
			}
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			return fail(netErr(u, wd.err(rerr)))
		}
	}
	out.Close()
	if (f.SHA1 != "" && !strings.EqualFold(hex.EncodeToString(h.Sum(nil)), f.SHA1)) || (f.Size > 0 && n != f.Size) {
		return fail(errHash)
	}
	markSpeed(u, n, time.Since(t0))
	_ = os.Remove(f.Path)
	return os.Rename(tmp, f.Path)
}

func partTag(u string) string {
	h := sha1.Sum([]byte(host(u)))
	return hex.EncodeToString(h[:3])
}

// fetchRanged downloads a big file in RangeParts parallel segments.
// Falls back to a single stream when the server ignores Range.
func (f File) fetchRanged(ctx context.Context, u string, onBytes func(int64)) error {
	tmp := f.Path + ".part"
	out, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if err := out.Truncate(f.Size); err != nil {
		out.Close()
		return err
	}
	cctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var total int64
	var mu sync.Mutex
	var firstErr error
	noRange := false
	part := (f.Size + int64(RangeParts) - 1) / int64(RangeParts)
	var wg sync.WaitGroup
	for i := 0; i < RangeParts; i++ {
		from := int64(i) * part
		to := min(from+part, f.Size) - 1
		if from > to {
			break
		}
		wg.Add(1)
		go func(from, to int64) {
			defer wg.Done()
			err := rangePart(cctx, u, out, from, to, func(n int64) {
				atomic.AddInt64(&total, n)
				if onBytes != nil {
					onBytes(n)
				}
			})
			if err != nil {
				mu.Lock()
				if firstErr == nil {
					firstErr = err
					if errors.Is(err, errNoRange) {
						noRange = true
					}
					cancel()
				}
				mu.Unlock()
			}
		}(from, to)
	}
	wg.Wait()
	out.Close()
	if firstErr != nil {
		os.Remove(tmp)
		if onBytes != nil {
			onBytes(-atomic.LoadInt64(&total))
		}
		if noRange {
			return f.fetchFrom(ctx, u, onBytes, false)
		}
		return firstErr
	}
	if f.SHA1 != "" {
		if h, err := fileSHA1(tmp); err != nil || !strings.EqualFold(h, f.SHA1) {
			os.Remove(tmp)
			if onBytes != nil {
				onBytes(-total)
			}
			return errHash
		}
	}
	_ = os.Remove(f.Path)
	return os.Rename(tmp, f.Path)
}

var errNoRange = errors.New("服务器不支持分段下载")

func rangePart(ctx context.Context, u string, out *os.File, from, to int64, onBytes func(int64)) error {
	cctx, cancel := context.WithCancel(ctx)
	defer cancel()
	wd := newWatchdog(cancel, false)
	defer wd.done()
	req, err := newReq(cctx, "GET", u, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", from, to))
	resp, err := client.Do(req)
	if err != nil {
		return netErr(u, wd.err(err))
	}
	defer resp.Body.Close()
	switch resp.StatusCode {
	case 206:
	case 200:
		return errNoRange
	default:
		return httpErr{resp.StatusCode}
	}
	buf := make([]byte, 64<<10)
	off := from
	for off <= to {
		k, rerr := resp.Body.Read(buf)
		if k > 0 {
			if off+int64(k) > to+1 {
				k = int(to + 1 - off)
			}
			wd.add(k)
			if _, err := out.WriteAt(buf[:k], off); err != nil {
				return err
			}
			off += int64(k)
			onBytes(int64(k))
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			return netErr(u, wd.err(rerr))
		}
	}
	if off != to+1 {
		return fmt.Errorf("分段数据不完整")
	}
	return nil
}

// Progress is reported during batches.
type Progress struct {
	Stage string `json:"stage"`
	Done  int    `json:"done"`
	Total int    `json:"total"`
	Bytes int64  `json:"bytes"`
	Speed int64  `json:"speed"` // bytes per second
}

// missing de-duplicates the list and returns the files not yet on disk. The
// existence checks run in parallel: on Windows each one is a filesystem call
// that Defender may inspect, and a modern version checks ~4,000 files per launch.
func missing(files []File) []File {
	uniq := make([]File, 0, len(files))
	seen := make(map[string]struct{}, len(files))
	for _, f := range files {
		key := f.Path
		if runtime.GOOS == "windows" {
			key = strings.ToLower(key)
		}
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		uniq = append(uniq, f)
	}
	ok := make([]bool, len(uniq))
	workers := min(16, max(1, len(uniq)/64))
	var wg sync.WaitGroup
	var next int64 = -1
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				i := int(atomic.AddInt64(&next, 1))
				if i >= len(uniq) {
					return
				}
				ok[i] = uniq[i].Present(false)
			}
		}()
	}
	wg.Wait()
	var todo []File
	for i, f := range uniq {
		if !ok[i] {
			todo = append(todo, f)
		}
	}
	return todo
}

// Reporter receives progress updates (throttled by the caller of Batch).
type Reporter func(Progress)

// Batch downloads every missing file with `threads` workers.
func Batch(ctx context.Context, stage string, files []File, threads int, rep Reporter) error {
	todo := missing(files)
	total := len(todo)
	if total == 0 {
		return nil
	}
	ensureProbe(ctx)
	if threads < 1 {
		threads = 16
	}
	// big files first, so they are not the long tail at the end
	sort.SliceStable(todo, func(i, j int) bool { return todo[i].Size > todo[j].Size })
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	var done int32
	var bytes int64
	jobs := make(chan File)
	var wg sync.WaitGroup
	var firstErr error
	var errOnce sync.Once

	stop := make(chan struct{})
	repDone := make(chan struct{})
	if rep != nil {
		go func() {
			defer close(repDone)
			t := time.NewTicker(150 * time.Millisecond)
			defer t.Stop()
			type sample struct {
				t time.Time
				b int64
			}
			var win []sample // 2-second sliding window for a steady speed figure
			for {
				select {
				case <-stop:
					return
				case now := <-t.C:
					b := atomic.LoadInt64(&bytes)
					win = append(win, sample{now, b})
					for len(win) > 1 && now.Sub(win[0].t) > 2*time.Second {
						win = win[1:]
					}
					var speed int64
					if dt := now.Sub(win[0].t).Seconds(); dt > .3 {
						speed = int64(float64(b-win[0].b) / dt)
					}
					rep(Progress{Stage: stage, Done: int(atomic.LoadInt32(&done)), Total: total, Bytes: b, Speed: speed})
				}
			}
		}()
	}

	for i := 0; i < threads && i < total; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for f := range jobs {
				err := f.fetch(ctx, func(n int64) { atomic.AddInt64(&bytes, n) })
				if err != nil && ctx.Err() == nil {
					errOnce.Do(func() { firstErr = err; cancel() })
				}
				atomic.AddInt32(&done, 1)
			}
		}()
	}
feed:
	for _, f := range todo {
		select {
		case jobs <- f:
		case <-ctx.Done():
			break feed
		}
	}
	close(jobs)
	wg.Wait()
	close(stop)
	if rep != nil {
		<-repDone // the ticker goroutine has stopped: no two reports run at once
		rep(Progress{Stage: stage, Done: int(atomic.LoadInt32(&done)), Total: total, Bytes: atomic.LoadInt64(&bytes)})
	}
	if firstErr != nil {
		return firstErr
	}
	return ctx.Err()
}
