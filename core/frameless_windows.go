//go:build windows

package main

import (
	"syscall"
	"unsafe"

	"github.com/jchv/go-webview2"
)

// Frameless window: the system title bar is removed (WM_NCCALCSIZE) and the
// web UI draws its own glass title bar. Moving, resizing and the window
// buttons are forwarded from JS through the bound function window.elWindow(op).

var (
	dwmapi                    = syscall.NewLazyDLL("dwmapi.dll")
	procDwmExtendFrame        = dwmapi.NewProc("DwmExtendFrameIntoClientArea")
	procDwmSetWindowAttribute = dwmapi.NewProc("DwmSetWindowAttribute")
	procSetWindowLongPtrW     = user32.NewProc("SetWindowLongPtrW")
	procCallWindowProcW       = user32.NewProc("CallWindowProcW")
	procSetWindowPos          = user32.NewProc("SetWindowPos")
	procIsZoomed              = user32.NewProc("IsZoomed")
	procReleaseCapture        = user32.NewProc("ReleaseCapture")
	procSendMessageW          = user32.NewProc("SendMessageW")
	procPostMessageW          = user32.NewProc("PostMessageW")
	procMonitorFromWindow     = user32.NewProc("MonitorFromWindow")
	procGetMonitorInfoW       = user32.NewProc("GetMonitorInfoW")
	oldWndProc                uintptr
)

const (
	wmNCCalcSize    = 0x0083
	wmNCLButtonDown = 0x00A1
	wmClose         = 0x0010
	htCaption       = 2
	swMaximize      = 3
	swMinimize      = 6
	swRestore       = 9
)

type rect struct{ L, T, R, B int32 }
type monitorInfo struct {
	Size    uint32
	Monitor rect
	Work    rect
	Flags   uint32
}
type margins struct{ L, R, T, B int32 }

func zoomed(h uintptr) bool { r, _, _ := procIsZoomed.Call(h); return r != 0 }

func frameProc(hwnd, msg, wp, lp uintptr) uintptr {
	if msg == wmNCCalcSize && wp == 1 {
		if zoomed(hwnd) {
			// a maximized borderless window would cover the taskbar: clamp to the work area
			mon, _, _ := procMonitorFromWindow.Call(hwnd, 2)
			mi := monitorInfo{Size: uint32(unsafe.Sizeof(monitorInfo{}))}
			if r, _, _ := procGetMonitorInfoW.Call(mon, uintptr(unsafe.Pointer(&mi))); r != 0 {
				*(*rect)(unsafe.Add(nil, lp)) = mi.Work // lp points to NCCALCSIZE_PARAMS (first field: the new window rect)
			}
		}
		return 0 // the whole window is client area: no system title bar
	}
	r, _, _ := procCallWindowProcW.Call(oldWndProc, hwnd, msg, wp, lp)
	return r
}

func makeFrameless(w webview2.WebView) {
	h := uintptr(w.Window())
	gwlpWndProc := -4
	oldWndProc, _, _ = procSetWindowLongPtrW.Call(h, uintptr(gwlpWndProc), syscall.NewCallback(frameProc))
	m := margins{0, 0, 1, 0} // keeps the DWM drop shadow
	procDwmExtendFrame.Call(h, uintptr(unsafe.Pointer(&m)))
	pref := int32(2) // DWMWCP_ROUND: rounded corners on Windows 11
	procDwmSetWindowAttribute.Call(h, 33, uintptr(unsafe.Pointer(&pref)), 4)
	procSetWindowPos.Call(h, 0, 0, 0, 0, 0, 0x0020|0x0002|0x0001|0x0004) // FRAMECHANGED|NOMOVE|NOSIZE|NOZORDER

	hit := map[string]uintptr{"drag": htCaption, "l": 10, "r": 11, "t": 12, "tl": 13, "tr": 14, "b": 15, "bl": 16, "br": 17}
	_ = w.Bind("elWindow", func(op string) bool {
		switch op {
		case "min":
			procShowWindow.Call(h, swMinimize)
		case "max":
			if zoomed(h) {
				procShowWindow.Call(h, swRestore)
			} else {
				procShowWindow.Call(h, swMaximize)
			}
		case "close":
			procPostMessageW.Call(h, wmClose, 0, 0)
		case "state":
		default:
			if ht, ok := hit[op]; ok && (op == "drag" || !zoomed(h)) {
				procReleaseCapture.Call()
				// start the system move/size loop outside the WebView2 callback
				w.Dispatch(func() { procSendMessageW.Call(h, wmNCLButtonDown, ht, 0) })
			}
		}
		return zoomed(h)
	})
}
