//go:build windows

package main

import (
	"encoding/json"
	"net/url"
	"os"
	"path/filepath"
	"strings"

	"github.com/jchv/go-webview2"
)

// The Minecraft Wiki shown inside the launcher: a second WebView2 re-parented as a child window and laid exactly
// over its page's content area. It is the real site (logging in, editing work),
// nothing of it ships with the launcher, and it's created on first use.

var (
	procSetParent          = user32.NewProc("SetParent")
	procSetWindowLongPtrW2 = user32.NewProc("SetWindowLongPtrW")
)

type pane struct {
	name   string
	home   string
	hosts  []string // navigation the launcher may trigger
	main   webview2.WebView
	parent uintptr
	data   string
	wv     webview2.WebView
	hwnd   uintptr
	shown  bool
}

func jsq(s string) string { b, _ := json.Marshal(s); return string(b) }

func (p *pane) ensure() bool {
	if p.wv != nil {
		return true
	}
	if _, err := os.Stat(filepath.Join(p.data, ".clear")); err == nil {
		_ = os.RemoveAll(p.data) // "clear cache" requested last session
	}
	wv := webview2.NewWithOptions(webview2.WebViewOptions{
		DataPath:      p.data,
		WindowOptions: webview2.WindowOptions{Title: p.name, Width: 1, Height: 1},
	})
	if wv == nil {
		return false
	}
	h := uintptr(wv.Window())
	procShowWindow.Call(h, 0) // SW_HIDE
	gwlStyle, gwlExStyle := -16, -20
	procSetWindowLongPtrW2.Call(h, uintptr(gwlStyle), 0x40000000|0x04000000|0x02000000) // WS_CHILD|WS_CLIPSIBLINGS|WS_CLIPCHILDREN
	procSetWindowLongPtrW2.Call(h, uintptr(gwlExStyle), 0)
	procSetParent.Call(h, p.parent)
	name := p.name
	_ = wv.Bind("elPaneLoc", func(u, title string) {
		p.main.Dispatch(func() {
			p.main.Eval("window.onPaneLoc && onPaneLoc(" + jsq(name) + "," + jsq(u) + "," + jsq(title) + ")")
		})
	})
	wv.Init(`addEventListener("DOMContentLoaded", () => { try { elPaneLoc(location.href, document.title) } catch (e) {} });`)
	p.wv, p.hwnd = wv, h
	wv.Navigate(p.home)
	return true
}

func (p *pane) allowed(u string) bool {
	pu, err := url.Parse(u)
	if err != nil || pu.Scheme != "https" {
		return false
	}
	h := strings.ToLower(pu.Hostname())
	for _, a := range p.hosts {
		if h == a || strings.HasSuffix(h, "."+a) {
			return true
		}
	}
	return false
}

func (p *pane) op(op string, x, y, w, h float64, arg string) {
	switch op {
	case "show":
		if !p.ensure() {
			return
		}
		procSetWindowPos.Call(p.hwnd, 0, uintptr(int(x)), uintptr(int(y)), uintptr(int(w)), uintptr(int(h)), 0x0040|0x0010) // SHOWWINDOW|NOACTIVATE
		p.shown = true
	case "hide":
		if p.hwnd != 0 && p.shown {
			procShowWindow.Call(p.hwnd, 0)
			p.shown = false
		}
	case "nav":
		if p.allowed(arg) && p.ensure() {
			p.wv.Navigate(arg)
		}
	case "back":
		if p.wv != nil {
			p.wv.Eval("history.back()")
		}
	case "fwd":
		if p.wv != nil {
			p.wv.Eval("history.forward()")
		}
	case "reload":
		if p.wv != nil {
			p.wv.Eval("location.reload()")
		}
	case "clear":
		_ = os.MkdirAll(p.data, 0o755)
		_ = os.WriteFile(filepath.Join(p.data, ".clear"), nil, 0o644)
	}
}

func setupPanes(main webview2.WebView, dataDir string) {
	parent := uintptr(main.Window())
	wiki := &pane{name: "wiki", home: "https://zh.minecraft.wiki/", hosts: []string{"zh.minecraft.wiki", "minecraft.wiki"},
		main: main, parent: parent, data: filepath.Join(dataDir, "wiki")}
	panes := map[string]*pane{"wiki": wiki}
	_ = main.Bind("elPane", func(name, op string, x, y, w, h float64, arg string) {
		p := panes[name]
		if p == nil {
			return
		}
		// never create or move windows inside a WebView2 callback: queue it on the UI thread
		main.Dispatch(func() { p.op(op, x, y, w, h, arg) })
	})
}
