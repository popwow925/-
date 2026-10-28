// Package app is the local service behind the EririLily window: a tiny JSON-RPC
// over HTTP on 127.0.0.1 plus a server-sent-event stream for progress and logs.
package app

import (
	"bytes"
	"context"
	"crypto/rand"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"eririlily/mc"
)

//go:embed web mobile glass
var webFS embed.FS

// webRoot is the desktop UI, or the phone UI on Android (ERIRILILY_UI=glass to preview it on desktop).
var webRoot = func() string {
	switch os.Getenv("ERIRILILY_UI") {
	case "mobile":
		return "mobile"
	case "glass":
		return "glass"
	}
	if mc.Android {
		return "glass"
	}
	return "web"
}()

type App struct {
	DataDir  string
	store    *mc.Store
	accounts *mc.Accounts
	token    string

	mu      sync.Mutex
	game    *mc.Game
	busy    string // current long task, "" when idle
	cancel  context.CancelFunc
	lastDx  *mc.Diagnosis
	lastLog []string
	subs    map[chan []byte]bool
	msMu    sync.Mutex
	msStop  context.CancelFunc

	routesOnce sync.Once
	routes     map[string]handler

	logMu    sync.Mutex
	logPend  []string
	logArmed bool

	OnLaunched func(closeLauncher bool) // window shell hook
}

func New(dataDir, base string) *App {
	_ = os.MkdirAll(dataDir, 0o755)
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	a := &App{DataDir: dataDir, store: mc.OpenStore(dataDir, base), accounts: mc.OpenAccounts(dataDir),
		token: hex.EncodeToString(b), subs: map[chan []byte]bool{}}
	mc.Source.Store(a.store.Get().Source)
	mc.RuntimeMirror = a.store.Get().RuntimeMirror
	mc.CacheDir = filepath.Join(dataDir, "cache")
	go mc.ProbeSources(context.Background()) // decide BMCLAPI vs official before the first download
	// v0.7 dropped the EririLily.cn integration: remove what it left behind
	// (the saved site login, the announcement cache and the site's browser profile)
	for _, f := range []string{"site_login.json", "announcements.json", "site"} {
		_ = os.RemoveAll(filepath.Join(dataDir, f))
	}
	go func() {
		// warm what the first launch reads while the user is still on the home page
		time.Sleep(1200 * time.Millisecond)
		cfg := a.store.Get()
		mc.DetectJava(cfg.GameDir, a.DataDir, false)
		if cfg.Selected != "" {
			mc.Prewarm(cfg.GameDir, cfg.Selected)
		}
		// caches must not grow forever
		time.Sleep(20 * time.Second)
		mc.TrimCache(filepath.Join(a.DataDir, "cache", "img"), 150<<20, 60*24*time.Hour)
		mc.TrimCache(filepath.Join(a.DataDir, "cache", "map"), 400<<20, 90*24*time.Hour)
	}()
	return a
}

// Serve starts the HTTP server and returns the URL the window should open.
func (a *App) Serve(addr string) (string, error) {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return "", err
	}
	mux := http.NewServeMux()
	sub, _ := fs.Sub(webFS, webRoot)
	static := http.FileServer(http.FS(sub))
	mux.Handle("/", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		if r.URL.Path == "/" || r.URL.Path == "/index.html" {
			// bake the theme into the page so it never flashes the wrong colours
			b, _ := webFS.ReadFile(webRoot + "/index.html")
			theme := a.store.Get().Theme
			if theme != "white" && theme != "black" {
				theme = "sakura"
			}
			b = bytes.Replace(b, []byte(`<html lang="zh-CN">`), []byte(`<html lang="zh-CN" data-theme="`+theme+`">`), 1)
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = w.Write(b)
			return
		}
		static.ServeHTTP(w, r)
	}))
	mux.HandleFunc("/api/events", a.events)
	mux.HandleFunc("/api/upload", a.upload)
	mux.HandleFunc("/api/import", a.importFile)
	mux.HandleFunc("/api/skin", a.skinImage)
	mux.HandleFunc("/api/skinupload", a.skinUpload)
	mux.HandleFunc("/api/worldicon", a.worldIcon)
	mux.HandleFunc("/api/cover", a.cover)
	mux.HandleFunc("/api/coverupload", a.coverUpload)
	mux.HandleFunc("/api/shot", a.screenshot)
	mux.HandleFunc("/api/img", a.img)
	mux.HandleFunc("/api/maptile", a.mapTile)
	mux.HandleFunc("/api/", a.rpc)
	go func() { _ = http.Serve(ln, mux) }()
	return fmt.Sprintf("http://%s/#%s", ln.Addr().String(), a.token), nil
}

// ---------------------------------------------------------------- events

func (a *App) emit(kind string, data any) {
	b, _ := json.Marshal(map[string]any{"type": kind, "data": data})
	a.mu.Lock()
	for c := range a.subs {
		select {
		case c <- b:
		default: // slow client: drop
		}
	}
	a.mu.Unlock()
}

// emitWait is emit for events that must not be dropped: it waits briefly for a
// busy client instead of discarding the message.
func (a *App) emitWait(kind string, data any) {
	b, _ := json.Marshal(map[string]any{"type": kind, "data": data})
	a.mu.Lock()
	subs := make([]chan []byte, 0, len(a.subs))
	for c := range a.subs {
		subs = append(subs, c)
	}
	a.mu.Unlock()
	for _, c := range subs {
		func() {
			defer func() { _ = recover() }() // the client went away and its channel closed
			select {
			case c <- b:
			case <-time.After(2 * time.Second):
			}
		}()
	}
}

func (a *App) authed(r *http.Request) bool {
	t := r.Header.Get("X-Token")
	if t == "" {
		t = r.URL.Query().Get("t")
	}
	return t == a.token
}

func (a *App) events(w http.ResponseWriter, r *http.Request) {
	if !a.authed(r) {
		http.Error(w, "forbidden", 403)
		return
	}
	fl, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "no stream", 500)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	c := make(chan []byte, 512)
	a.mu.Lock()
	a.subs[c] = true
	a.mu.Unlock()
	defer func() {
		a.mu.Lock()
		delete(a.subs, c)
		a.mu.Unlock()
	}()
	fmt.Fprint(w, ": hi\n\n")
	fl.Flush()
	ping := time.NewTicker(20 * time.Second)
	defer ping.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case b := <-c:
			fmt.Fprintf(w, "data: %s\n\n", b)
			fl.Flush()
		case <-ping.C:
			fmt.Fprint(w, ": ping\n\n")
			fl.Flush()
		}
	}
}

// ---------------------------------------------------------------- rpc

type handler func(ctx context.Context, p json.RawMessage) (any, error)

func (a *App) rpc(w http.ResponseWriter, r *http.Request) {
	if !a.authed(r) || r.Method != "POST" {
		http.Error(w, "forbidden", 403)
		return
	}
	name := strings.TrimPrefix(r.URL.Path, "/api/")
	a.routesOnce.Do(func() {
		a.routes = map[string]handler{}
		for _, m := range []map[string]handler{a.methods(), a.extraMethods(), a.featureMethods(), a.androidMethods()} {
			for k, v := range m {
				a.routes[k] = v
			}
		}
	})
	h, ok := a.routes[name]
	w.Header().Set("Content-Type", "application/json")
	if !ok {
		w.WriteHeader(404)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": "未知方法 " + name})
		return
	}
	body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if len(body) == 0 {
		body = []byte("{}")
	}
	res, err := h(r.Context(), body)
	if err != nil {
		w.WriteHeader(400)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}
	_ = json.NewEncoder(w).Encode(map[string]any{"result": res})
}

func bind[T any](p json.RawMessage) T {
	var v T
	_ = json.Unmarshal(p, &v)
	return v
}

// background runs a long task (install / launch / download) one at a time.
func (a *App) background(label string, f func(ctx context.Context) error) error {
	a.mu.Lock()
	if a.busy != "" {
		b := a.busy
		a.mu.Unlock()
		return fmt.Errorf("正在%s，请稍候", b)
	}
	ctx, cancel := context.WithCancel(context.Background())
	a.busy, a.cancel = label, cancel
	a.mu.Unlock()
	a.emit("task", map[string]any{"label": label, "state": "start"})
	go func() {
		err := f(ctx)
		a.mu.Lock()
		a.busy, a.cancel = "", nil
		a.mu.Unlock()
		cancel()
		ev := map[string]any{"label": label, "state": "done"}
		if err != nil {
			ev["state"] = "error"
			ev["error"] = err.Error()
			if errors.Is(err, context.Canceled) {
				ev["state"] = "cancelled"
			}
		}
		a.emit("task", ev)
	}()
	return nil
}

func (a *App) progress(p mc.Progress) { a.emit("progress", p) }

type instanceView struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Game     string `json:"game"`
	Loader   string `json:"loader"`
	LoaderV  string `json:"loaderVersion"`
	Pack     string `json:"pack"`
	Icon     string `json:"icon"`
	LastPlay int64  `json:"lastPlayed"`
	PlayTime int64  `json:"playTime"`
	Created  int64  `json:"created"`
	Java     int    `json:"java"`
	Mods     int    `json:"mods"`
	Cover    int64  `json:"cover"` // custom background version, 0 = none
}

func (a *App) instances() []instanceView {
	cfg := a.store.Get()
	var out []instanceView
	for _, id := range mc.InstanceIDs(cfg.GameDir) {
		in, err := mc.Info(cfg.GameDir, id)
		if err != nil {
			continue
		}
		iv := instanceView{ID: id, Name: id, Game: in.Game, Loader: in.Loader, Java: in.Java}
		if in.EL != nil {
			if in.EL.Name != "" {
				iv.Name = in.EL.Name
			}
			iv.LoaderV, iv.Pack, iv.Icon = in.EL.LoaderVer, in.EL.Pack, in.EL.Icon
			iv.LastPlay, iv.PlayTime, iv.Created = in.EL.LastPlay, in.EL.PlayTime, in.EL.Created
		}
		iv.Cover = mc.CoverVersion(a.DataDir, "inst", id, "")
		iv.Mods = mc.CountContent(mc.RunDir(cfg.GameDir, id, cfg.Isolation || in.Isolated), "mod")
		out = append(out, iv)
	}
	sort.SliceStable(out, func(i, j int) bool {
		ti, tj := max(out[i].LastPlay, out[i].Created), max(out[j].LastPlay, out[j].Created)
		return ti > tj
	})
	return out
}

func (a *App) runDir(id string) string {
	cfg := a.store.Get()
	in, _ := mc.Info(cfg.GameDir, id)
	return mc.RunDir(cfg.GameDir, id, cfg.Isolation || in.Isolated)
}

func (a *App) state() map[string]any {
	if mc.Android {
		mc.MergeGameSettings(a.DataDir, a.store)
	}
	cfg := a.store.Get()
	var accs []map[string]any
	for _, x := range a.accounts.All() {
		accs = append(accs, x.Public())
	}
	insts := a.instances()
	if cfg.Selected == "" && len(insts) > 0 {
		cfg = a.store.Update(func(c *mc.Config) { c.Selected = insts[0].ID })
	}
	if cfg.Account == "" && len(accs) > 0 {
		cfg = a.store.Update(func(c *mc.Config) { c.Account = accs[0]["id"].(string) })
	}
	a.mu.Lock()
	running := a.game != nil
	busy := a.busy
	dx := a.lastDx
	a.mu.Unlock()
	return map[string]any{
		"version": mc.AppVersion, "config": cfg, "accounts": accs, "instances": insts,
		"running": running, "busy": busy, "memTotal": mc.TotalMemoryMB(), "memFree": mc.FreeMemoryMB(),
		"autoMem": mc.AutoMemory(false), "autoMemModded": mc.AutoMemory(true), "diagnosis": dx,
		"dataDir": a.DataDir, "autoSource": mc.AutoSource(), "msBuiltin": mc.BuiltinMSClientID != "",
		"android": mc.Android,
	}
}

// Theme is the current UI theme (for embedded pages).
func (a *App) Theme() string {
	t := a.store.Get().Theme
	if t != "white" && t != "black" {
		t = "sakura"
	}
	return t
}
