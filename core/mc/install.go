package mc

import (
	"archive/zip"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// InstallVanilla writes versions/<id>/<id>.json for a vanilla version.
func InstallVanilla(ctx context.Context, gameDir, id string) error {
	if _, err := os.Stat(VersionJSON(gameDir, id)); err == nil {
		return nil
	}
	m, err := GetManifest(ctx)
	if err != nil {
		return err
	}
	for _, mv := range m.Versions {
		if mv.ID == id {
			b, err := GetBytes(ctx, mv.URL)
			if err != nil {
				return fmt.Errorf("下载版本信息失败：%w", err)
			}
			if err := os.MkdirAll(VersionDir(gameDir, id), 0o755); err != nil {
				return err
			}
			return os.WriteFile(VersionJSON(gameDir, id), b, 0o644)
		}
	}
	return fmt.Errorf("找不到版本 %s", id)
}

// nativeLib is a jar whose native libraries must be extracted before launch.
type nativeLib struct {
	Path    string
	Exclude []string
}

func classifierOK(cls string) bool {
	// skip natives built for another CPU
	a := archName()
	switch {
	case strings.HasSuffix(cls, "-arm64"):
		return a == "arm64"
	case strings.HasSuffix(cls, "-x86"):
		return a == "x86"
	}
	return true
}

// libraryFiles returns the downloads, the classpath and the native jars.
func libraryFiles(gameDir string, v *Version) (files []File, cp []string, natives []nativeLib) {
	libDir := filepath.Join(gameDir, "libraries")
	for _, l := range v.Libraries {
		if !ruleAllows(l.Rules, nil) {
			continue
		}
		parts := strings.Split(strings.SplitN(l.Name, "@", 2)[0], ":")
		cls := ""
		if len(parts) >= 4 {
			cls = parts[3]
		}
		if cls != "" && strings.HasPrefix(cls, "natives-") && !classifierOK(cls) {
			continue
		}
		// main artifact
		var art *Download
		if l.Downloads != nil {
			art = l.Downloads.Artifact
		}
		hasNativesMap := l.Natives != nil
		if art != nil {
			p := art.Path
			if p == "" {
				p = MavenPath(l.Name)
			}
			full := filepath.Join(libDir, filepath.FromSlash(p))
			if art.URL != "" {
				files = append(files, File{URL: art.URL, Path: full, SHA1: art.SHA1, Size: art.Size})
			}
			cp = append(cp, full)
			if strings.HasPrefix(cls, "natives-") {
				natives = append(natives, nativeLib{Path: full})
			}
		} else if !hasNativesMap || l.Downloads == nil {
			p := MavenPath(l.Name)
			full := filepath.Join(libDir, filepath.FromSlash(p))
			base := l.URL
			if base == "" {
				base = "https://libraries.minecraft.net/"
			}
			if !strings.HasSuffix(base, "/") {
				base += "/"
			}
			files = append(files, File{URL: base + p, Path: full, SHA1: l.SHA1, Size: l.Size,
				Alt: []string{"https://maven.fabricmc.net/" + p, "https://repo1.maven.org/maven2/" + p}})
			cp = append(cp, full)
		}
		// legacy natives map
		if hasNativesMap {
			key, ok := l.Natives[osName]
			if !ok {
				continue
			}
			bits := "64"
			if archName() == "x86" {
				bits = "32"
			}
			key = strings.ReplaceAll(key, "${arch}", bits)
			var d *Download
			if l.Downloads != nil && l.Downloads.Classifiers != nil {
				d = l.Downloads.Classifiers[key]
			}
			var full string
			if d != nil {
				p := d.Path
				if p == "" {
					p = MavenPath(l.Name + ":" + key)
				}
				full = filepath.Join(libDir, filepath.FromSlash(p))
				files = append(files, File{URL: d.URL, Path: full, SHA1: d.SHA1, Size: d.Size})
			} else {
				p := MavenPath(l.Name + ":" + key)
				full = filepath.Join(libDir, filepath.FromSlash(p))
				files = append(files, File{URL: "https://libraries.minecraft.net/" + p, Path: full})
			}
			nl := nativeLib{Path: full}
			if l.Extract != nil {
				nl.Exclude = l.Extract.Exclude
			}
			natives = append(natives, nl)
		}
	}
	// de-duplicate classpath keeping order
	seen := map[string]bool{}
	out := cp[:0]
	for _, p := range cp {
		if !seen[p] {
			seen[p] = true
			out = append(out, p)
		}
	}
	return files, out, natives
}

type assetIndex struct {
	Objects map[string]struct {
		Hash string `json:"hash"`
		Size int64  `json:"size"`
	} `json:"objects"`
	Virtual        bool `json:"virtual"`
	MapToResources bool `json:"map_to_resources"`
}

func assetIndexID(v *Version) string {
	if v.AssetIndex != nil && v.AssetIndex.ID != "" {
		return v.AssetIndex.ID
	}
	if v.Assets != "" {
		return v.Assets
	}
	return "legacy"
}

type idxEntry struct {
	size int64
	mod  time.Time
	idx  *assetIndex
}

var idxCache sync.Map // path -> idxEntry

// loadAssetIndex parses an asset index (≈4,000 entries) once per process and
// file version: the second launch of the session skips the JSON work entirely.
func loadAssetIndex(gameDir string, v *Version) (*assetIndex, error) {
	p := filepath.Join(gameDir, "assets", "indexes", assetIndexID(v)+".json")
	st, err := os.Stat(p)
	if err != nil {
		return &assetIndex{}, err
	}
	if c, ok := idxCache.Load(p); ok {
		if e := c.(idxEntry); e.size == st.Size() && e.mod.Equal(st.ModTime()) {
			return e.idx, nil
		}
	}
	var idx assetIndex
	if err := readJSON(p, &idx); err != nil {
		return &idx, err
	}
	idxCache.Store(p, idxEntry{st.Size(), st.ModTime(), &idx})
	return &idx, nil
}

// Complete makes sure every file the version needs is present ("补全文件").
func Complete(ctx context.Context, gameDir, id string, threads int, deep bool, rep Reporter) error {
	v, err := Resolve(gameDir, id)
	if err != nil {
		return err
	}
	var files []File
	// client jar
	if c := v.Downloads["client"]; c != nil {
		files = append(files, File{URL: c.URL, Path: filepath.Join(VersionDir(gameDir, v.Jar), v.Jar+".jar"), SHA1: c.SHA1, Size: c.Size})
	}
	lf, _, _ := libraryFiles(gameDir, v)
	files = append(files, lf...)
	if v.Logging != nil && v.Logging.Client != nil && v.Logging.Client.File.URL != "" {
		f := v.Logging.Client.File
		files = append(files, File{URL: f.URL, Path: filepath.Join(gameDir, "assets", "log_configs", f.ID), SHA1: f.SHA1, Size: f.Size})
	}
	// the asset index is tiny: fetch it first so everything else goes in one batch
	if v.AssetIndex != nil {
		ix := File{URL: v.AssetIndex.URL, Path: filepath.Join(gameDir, "assets", "indexes", v.AssetIndex.ID+".json"),
			SHA1: v.AssetIndex.SHA1, Size: v.AssetIndex.Size}
		if deep {
			removeBad([]File{ix})
		}
		if err := Batch(ctx, "资源索引", []File{ix}, 1, nil); err != nil {
			return err
		}
		idx, err := loadAssetIndex(gameDir, v)
		if err != nil {
			return fmt.Errorf("读取资源索引失败：%w", err)
		}
		for _, o := range idx.Objects {
			if len(o.Hash) < 2 {
				continue
			}
			rel := o.Hash[:2] + "/" + o.Hash
			files = append(files, File{URL: "https://resources.download.minecraft.net/" + rel,
				Path: filepath.Join(gameDir, "assets", "objects", o.Hash[:2], o.Hash), SHA1: o.Hash, Size: o.Size})
		}
	}
	if deep {
		removeBad(files)
		_ = os.RemoveAll(filepath.Join(VersionDir(gameDir, id), "natives-"+osName+"-"+archName()))
	}
	return Batch(ctx, "下载游戏文件", files, threads, rep)
}

// Prewarm loads what launching an instance reads first (its resolved version
// and asset index) so the first click on 开始游戏 doesn't pay for it.
func Prewarm(gameDir, id string) {
	if v, err := Resolve(gameDir, id); err == nil {
		_, _ = loadAssetIndex(gameDir, v)
	}
}

// removeBad deletes files whose hash does not match, so Batch downloads them again.
func removeBad(files []File) {
	for _, f := range files {
		if _, err := os.Stat(f.Path); err == nil && f.SHA1 != "" && !f.Present(true) {
			_ = os.Remove(f.Path)
		}
	}
}

// extractNatives unpacks native libraries (dll/so/dylib) flat into dir.
func extractNatives(natives []nativeLib, dir string) error {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	for _, n := range natives {
		zr, err := zip.OpenReader(n.Path)
		if err != nil {
			continue // missing natives are reported by the game itself
		}
		for _, f := range zr.File {
			name := f.Name
			if f.FileInfo().IsDir() || strings.HasPrefix(name, "META-INF") {
				continue
			}
			skip := false
			for _, ex := range n.Exclude {
				if strings.HasPrefix(name, ex) {
					skip = true
				}
			}
			low := strings.ToLower(name)
			if skip || !(strings.HasSuffix(low, ".dll") || strings.HasSuffix(low, ".so") ||
				strings.HasSuffix(low, ".dylib") || strings.HasSuffix(low, ".jnilib")) {
				continue
			}
			dst := filepath.Join(dir, filepath.Base(name))
			if st, err := os.Stat(dst); err == nil && st.Size() == int64(f.UncompressedSize64) {
				continue
			}
			rc, err := f.Open()
			if err != nil {
				continue
			}
			out, err := os.Create(dst)
			if err == nil {
				_, _ = io.Copy(out, rc)
				out.Close()
			}
			rc.Close()
		}
		zr.Close()
	}
	return nil
}

// prepareLegacyAssets copies objects to the virtual/resources layout old versions expect.
func prepareLegacyAssets(gameDir, runDir string, v *Version) (virtualDir string) {
	idx, err := loadAssetIndex(gameDir, v)
	if err != nil || !(idx.Virtual || idx.MapToResources) {
		return ""
	}
	target := filepath.Join(gameDir, "assets", "virtual", assetIndexID(v))
	if idx.MapToResources {
		target = filepath.Join(runDir, "resources")
	}
	for name, o := range idx.Objects {
		dst := filepath.Join(target, filepath.FromSlash(name))
		if st, err := os.Stat(dst); err == nil && st.Size() == o.Size {
			continue
		}
		src := filepath.Join(gameDir, "assets", "objects", o.Hash[:2], o.Hash)
		_ = copyFile(src, dst)
	}
	return target
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer out.Close()
	_, err = io.Copy(out, in)
	return err
}

// stampMeta records EririLily metadata on an installed instance.
func stampMeta(gameDir, id string, f func(*InstanceMeta)) {
	// Only the "eririlily" key is touched: version jsons written by other launchers
	// (HMCL patches, PCL fields, …) must survive byte-for-byte otherwise.
	path := VersionJSON(gameDir, id)
	b, err := os.ReadFile(path)
	if err != nil {
		return
	}
	var raw map[string]json.RawMessage
	if json.Unmarshal(b, &raw) != nil {
		return
	}
	meta := &InstanceMeta{}
	if m, ok := raw["eririlily"]; ok {
		_ = json.Unmarshal(m, meta)
	}
	if meta.Created == 0 {
		meta.Created = time.Now().Unix()
	}
	f(meta)
	mb, _ := json.Marshal(meta)
	raw["eririlily"] = mb
	out, err := json.MarshalIndent(raw, "", "  ")
	if err != nil {
		return
	}
	tmp := path + ".tmp"
	if os.WriteFile(tmp, out, 0o644) == nil {
		_ = os.Rename(tmp, path)
	}
	ForgetInfo(gameDir, id)
}
