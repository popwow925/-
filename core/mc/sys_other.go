//go:build !windows && !android

package mc

import (
	"bufio"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
)

func meminfo(key string) int {
	f, err := os.Open("/proc/meminfo")
	if err != nil {
		return 0
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		if strings.HasPrefix(sc.Text(), key+":") {
			fs := strings.Fields(sc.Text())
			if len(fs) >= 2 {
				n, _ := strconv.Atoi(fs[1])
				return n / 1024
			}
		}
	}
	return 0
}

func TotalMemoryMB() int { return meminfo("MemTotal") }
func FreeMemoryMB() int  { return meminfo("MemAvailable") }

func hideWindow(cmd *exec.Cmd) {}

func OpenPath(p string) error {
	if runtime.GOOS == "darwin" {
		return exec.Command("open", p).Start()
	}
	return exec.Command("xdg-open", p).Start()
}
