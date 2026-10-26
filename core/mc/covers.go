package mc

import (
	"bytes"
	"crypto/sha1"
	"encoding/hex"
	"errors"
	"image"
	_ "image/gif"
	_ "image/jpeg"
	_ "image/png"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// Custom backgrounds for instances ("inst") and saves ("world"). They live in
// the launcher's data folder, so instance and world folders stay untouched.

func coverKey(kind, a, b string) string {
	h := sha1.Sum([]byte(kind + "\x00" + a + "\x00" + b))
	return kind + "-" + hex.EncodeToString(h[:10])
}

func CoverPath(dataDir, kind, a, b string) string {
	return filepath.Join(dataDir, "covers", coverKey(kind, a, b)+".img")
}

// CoverVersion is the cover's mtime (0 when there is none) — handy for cache-busting URLs.
func CoverVersion(dataDir, kind, a, b string) int64 {
	st, err := os.Stat(CoverPath(dataDir, kind, a, b))
	if err != nil {
		return 0
	}
	return st.ModTime().UnixMilli()
}

// ImageType validates an image and returns its MIME type.
func ImageType(b []byte) (string, error) {
	if len(b) > 12 && string(b[:4]) == "RIFF" && string(b[8:12]) == "WEBP" {
		return "image/webp", nil
	}
	cfg, format, err := image.DecodeConfig(bytes.NewReader(b))
	if err != nil {
		return "", errors.New("请选择 PNG、JPG、GIF 或 WebP 图片")
	}
	if cfg.Width < 16 || cfg.Height < 16 {
		return "", errors.New("图片太小了")
	}
	return "image/" + format, nil
}

func SetCover(dataDir, kind, a, b string, img []byte) error {
	if _, err := ImageType(img); err != nil {
		return err
	}
	p := CoverPath(dataDir, kind, a, b)
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		return err
	}
	tmp := p + ".tmp"
	if err := os.WriteFile(tmp, img, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, p)
}

func ResetCover(dataDir, kind, a, b string) {
	os.Remove(CoverPath(dataDir, kind, a, b))
}

type Screenshot struct {
	File string `json:"file"`
	Time int64  `json:"time"`
}

// ListScreenshots returns the instance's in-game screenshots, newest first.
func ListScreenshots(runDir string) []Screenshot {
	ents, _ := os.ReadDir(filepath.Join(runDir, "screenshots"))
	var out []Screenshot
	for _, e := range ents {
		low := strings.ToLower(e.Name())
		if e.IsDir() || !(strings.HasSuffix(low, ".png") || strings.HasSuffix(low, ".jpg") || strings.HasSuffix(low, ".jpeg")) {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		out = append(out, Screenshot{File: e.Name(), Time: info.ModTime().Unix()})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Time > out[j].Time })
	if len(out) > 120 {
		out = out[:120]
	}
	return out
}

// ScreenshotPath resolves a screenshot file safely.
func ScreenshotPath(runDir, file string) (string, error) {
	p, ok := within(filepath.Join(runDir, "screenshots"), file)
	if !ok || strings.ContainsAny(file, `/\`) {
		return "", errors.New("非法文件名")
	}
	return p, nil
}
