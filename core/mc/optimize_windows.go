//go:build windows

package mc

import (
	"os/exec"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows/registry"
)

var procSPI = syscall.NewLazyDLL("user32.dll").NewProc("SystemParametersInfoW")

// Accessibility shortcuts that pop up while gaming: Shift×5 (sticky keys),
// holding right Shift 8 s (filter keys) and holding Num Lock 5 s (toggle keys).
type accessKey struct {
	get, set uint32
	size     uint32
}

var accessKeys = []accessKey{
	{0x003A, 0x003B, 8},  // STICKYKEYS  {cbSize, dwFlags}
	{0x0032, 0x0033, 24}, // FILTERKEYS  {cbSize, dwFlags, 4 × DWORD}
	{0x0034, 0x0035, 8},  // TOGGLEKEYS  {cbSize, dwFlags}
}

const (
	hotkeyActive  = 0x4 // *_HOTKEYACTIVE
	confirmHotkey = 0x8 // *_CONFIRMHOTKEY
)

func OptimizeSupported() bool { return true }

// StickyHotkeysOn reports whether Shift×5 (or the other shortcuts) can still pop up.
func StickyHotkeysOn() bool {
	for _, k := range accessKeys {
		buf := make([]uint32, k.size/4)
		buf[0] = k.size
		if r, _, _ := procSPI.Call(uintptr(k.get), uintptr(k.size), uintptr(unsafe.Pointer(&buf[0])), 0); r != 0 && buf[1]&hotkeyActive != 0 {
			return true
		}
	}
	return false
}

// SetStickyHotkeys turns the accessibility shortcuts on or off (per user, persisted).
func SetStickyHotkeys(on bool) error {
	for _, k := range accessKeys {
		buf := make([]uint32, k.size/4)
		buf[0] = k.size
		if r, _, err := procSPI.Call(uintptr(k.get), uintptr(k.size), uintptr(unsafe.Pointer(&buf[0])), 0); r == 0 {
			return err
		}
		if on {
			buf[1] |= hotkeyActive | confirmHotkey
		} else {
			buf[1] &^= hotkeyActive | confirmHotkey
		}
		if r, _, err := procSPI.Call(uintptr(k.set), uintptr(k.size), uintptr(unsafe.Pointer(&buf[0])), 1|2); r == 0 {
			return err
		}
	}
	return nil
}

const gpuKey = `Software\Microsoft\DirectX\UserGpuPreferences`

// SetGPUPreference asks Windows to run exe on the high-performance GPU (laptops with two GPUs).
func SetGPUPreference(exe string, high bool) error {
	k, _, err := registry.CreateKey(registry.CURRENT_USER, gpuKey, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()
	if !high {
		err := k.DeleteValue(exe)
		if err == registry.ErrNotExist {
			return nil
		}
		return err
	}
	return k.SetStringValue(exe, "GpuPreference=2;")
}

func setHighPriority(cmd *exec.Cmd) {
	if cmd.SysProcAttr == nil {
		cmd.SysProcAttr = &syscall.SysProcAttr{}
	}
	cmd.SysProcAttr.CreationFlags |= 0x00008000 // ABOVE_NORMAL_PRIORITY_CLASS
}
