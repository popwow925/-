//go:build !windows

package main

import (
	"fmt"
	"io"
	"os"
	"os/signal"
	"sync"

	"eririlily/app"
	"eririlily/mc"
)

func fatal(msg string) {
	fmt.Fprintln(os.Stderr, msg)
	os.Exit(1)
}

var outMu sync.Mutex

// shellMsg is a line for the Android app, which reads the core's stdout.
func shellMsg(kind, arg string) {
	outMu.Lock()
	fmt.Printf("@@%s %s\n", kind, arg)
	outMu.Unlock()
}

// On Linux/macOS (development) the UI is served to the browser; on Android
// the app reads the URL (and launch/open requests) from stdout.
func runWindow(a *app.App, url, dataDir string) {
	fmt.Println("EririLily UI:", url)
	shellMsg("URL", url)
	if f := os.Getenv("ERIRILILY_URLFILE"); f != "" {
		_ = os.WriteFile(f, []byte(url), 0o644)
	}
	if os.Getenv("ERIRILILY_PARENT_PIPE") != "" {
		// the app holds our stdin: when it goes away, so do we
		go func() {
			_, _ = io.Copy(io.Discard, os.Stdin)
			os.Exit(0)
		}()
	}
	c := make(chan os.Signal, 1)
	signal.Notify(c, os.Interrupt)
	<-c
}

func init() {
	if !mc.Android {
		return
	}
	mc.InitAndroid()
	mc.AndroidStart = func(spec string) { shellMsg("LAUNCH", spec) }
	mc.OpenHook = func(p string) error { shellMsg("OPEN", p); return nil }
}
