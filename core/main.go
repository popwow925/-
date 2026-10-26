// EririLily 樱梨启动器 — a fast, minimal Minecraft: Java Edition launcher.
package main

import (
	"os"
	"path/filepath"
	"runtime"

	"eririlily/app"
)

// paths returns the launcher data dir and the base folder for .minecraft.
// A ".minecraft" (or "EririLily" data folder) next to the exe makes the launcher portable.
func paths() (dataDir, base string) {
	exe, _ := os.Executable()
	exeDir := filepath.Dir(exe)
	base = exeDir
	if runtime.GOOS == "windows" {
		if ad := os.Getenv("APPDATA"); ad != "" {
			base = ad
		}
	} else if h, err := os.UserHomeDir(); err == nil {
		base = h
	}
	if st, err := os.Stat(filepath.Join(exeDir, ".minecraft")); err == nil && st.IsDir() {
		base = exeDir
	}
	dataDir = filepath.Join(base, "EririLily")
	if st, err := os.Stat(filepath.Join(exeDir, "EririLily")); err == nil && st.IsDir() {
		dataDir = filepath.Join(exeDir, "EririLily")
	}
	if d := os.Getenv("ERIRILILY_DATA"); d != "" { // dev / tests
		dataDir, base = d, d
	}
	return
}

func main() {
	dataDir, base := paths()
	a := app.New(dataDir, base)
	url, err := a.Serve("127.0.0.1:0")
	if err != nil {
		fatal("无法启动本地服务：" + err.Error())
	}
	runWindow(a, url, dataDir)
}
