//go:build windows

package mc

import (
	"os/exec"
	"syscall"
	"unsafe"
)

var kernel32 = syscall.NewLazyDLL("kernel32.dll")

type memStatusEx struct {
	Length               uint32
	MemoryLoad           uint32
	TotalPhys            uint64
	AvailPhys            uint64
	TotalPageFile        uint64
	AvailPageFile        uint64
	TotalVirtual         uint64
	AvailVirtual         uint64
	AvailExtendedVirtual uint64
}

func memStatus() (total, avail int) {
	var m memStatusEx
	m.Length = uint32(unsafe.Sizeof(m))
	r, _, _ := kernel32.NewProc("GlobalMemoryStatusEx").Call(uintptr(unsafe.Pointer(&m)))
	if r == 0 {
		return 0, 0
	}
	return int(m.TotalPhys >> 20), int(m.AvailPhys >> 20)
}

// TotalMemoryMB is the physical memory size.
func TotalMemoryMB() int { t, _ := memStatus(); return t }

// FreeMemoryMB is the currently available physical memory.
func FreeMemoryMB() int { _, a := memStatus(); return a }

func hideWindow(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000} // CREATE_NO_WINDOW
}

// OpenPath shows a folder or URL with the shell.
func OpenPath(p string) error {
	cmd := exec.Command("rundll32", "url.dll,FileProtocolHandler", p)
	return cmd.Start()
}
