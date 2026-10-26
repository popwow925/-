//go:build android

package mc

import "os/exec"

func OptimizeSupported() bool                      { return false }
func StickyHotkeysOn() bool                        { return false }
func SetStickyHotkeys(on bool) error               { return nil }
func SetGPUPreference(exe string, high bool) error { return nil }
func setHighPriority(cmd *exec.Cmd)                {}
