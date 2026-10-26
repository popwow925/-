//go:build android

package mc

import (
	"bufio"
	"errors"
	"os"
	"os/exec"
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

// OpenPath hands folders and links to the app shell (there is no desktop shell on Android).
func OpenPath(p string) error {
	if OpenHook == nil {
		return errors.New("无法打开")
	}
	return OpenHook(p)
}
