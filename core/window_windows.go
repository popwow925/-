//go:build windows

package main

import (
	"os"
	"path/filepath"
	"syscall"
	"unsafe"

	"github.com/jchv/go-webview2"

	"eririlily/app"
	"eririlily/mc"
)

var (
	user32         = syscall.NewLazyDLL("user32.dll")
	procMessageBox = user32.NewProc("MessageBoxW")
	procShowWindow = user32.NewProc("ShowWindow")
)

func fatal(msg string) {
	t, _ := syscall.UTF16PtrFromString("EririLily 樱梨启动器")
	m, _ := syscall.UTF16PtrFromString(msg)
	procMessageBox.Call(0, uintptr(unsafe.Pointer(m)), uintptr(unsafe.Pointer(t)), 0x10)
	os.Exit(1)
}

func runWindow(a *app.App, url, dataDir string) {
	// cap the WebView2 disk cache (main UI + wiki) so the launcher's footprint stays small
	if os.Getenv("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS") == "" {
		_ = os.Setenv("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS", "--disk-cache-size=104857600")
	}
	w := webview2.NewWithOptions(webview2.WebViewOptions{
		Debug:     os.Getenv("ERIRILILY_DEBUG") != "",
		AutoFocus: true,
		DataPath:  filepath.Join(dataDir, "webview"),
		WindowOptions: webview2.WindowOptions{
			Title:  "EririLily 樱梨启动器",
			Width:  1180,
			Height: 760,
			IconId: 1, // from the embedded resources (winres)
			Center: true,
		},
	})
	if w == nil {
		_ = mc.OpenPath("https://go.microsoft.com/fwlink/p/?LinkId=2124703")
		fatal("需要 Microsoft Edge WebView2 运行时。\n\nWindows 10/11 一般自带；如果没有，浏览器已打开下载页，安装后重新打开启动器即可。")
	}
	defer w.Destroy()
	w.SetSize(1000, 640, webview2.HintMin)
	makeFrameless(w)
	setupPanes(w, dataDir)
	a.OnLaunched = func(hide bool) {
		if hide {
			w.Dispatch(func() { procShowWindow.Call(uintptr(w.Window()), 6) }) // SW_MINIMIZE
		}
	}
	w.Navigate(url)
	w.Run()
}
