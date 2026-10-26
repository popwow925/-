package mc

import (
	"archive/zip"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

var modrinthAPI = "https://api.modrinth.com/v2"

type Project struct {
	ID          string   `json:"project_id"`
	Slug        string   `json:"slug"`
	Title       string   `json:"title"`
	Description string   `json:"description"`
	Author      string   `json:"author"`
	Icon        string   `json:"icon_url"`
	Downloads   int64    `json:"downloads"`
	Type        string   `json:"project_type"`
	Categories  []string `json:"display_categories"`
	AllCats     []string `json:"categories"` // includes the loaders (fabric, forge, neoforge, quilt…)
	Versions    []string `json:"versions"`
}

type SearchResult struct {
	Hits  []Project `json:"hits"`
	Total int       `json:"total_hits"`
}

// Search queries Modrinth. kind: mod | modpack | resourcepack | shader.
func Search(ctx context.Context, query, kind, game, loader string, offset int) (*SearchResult, error) {
	if game != "" && !reGameVer.MatchString(" "+game+" ") {
		game = "" // not a real version number: don't filter by it
	}
	try := func(game, loader string) (*SearchResult, error) {
		facets := [][]string{{"project_type:" + kind}}
		if game != "" {
			facets = append(facets, []string{"versions:" + game})
		}
		if loader != "" && (kind == "mod" || kind == "modpack") {
			facets = append(facets, []string{"categories:" + loader})
		}
		fb, _ := json.Marshal(facets)
		q := url.Values{"query": {query}, "facets": {string(fb)}, "limit": {"24"}, "offset": {itoa(offset)}}
		if query == "" {
			q.Set("index", "downloads")
		}
		var out SearchResult
		err := GetJSON(ctx, modrinthAPI+"/search?"+q.Encode(), &out)
		return &out, err
	}
	out, err := try(game, loader)
	if err != nil && strings.Contains(err.Error(), "HTTP 400") && (game != "" || loader != "") {
		out, err = try("", loader) // a filter the API refused: loosen it rather than fail
		if err != nil && loader != "" {
			out, err = try("", "")
		}
	}
	if err != nil {
		return nil, fmt.Errorf("搜索失败：%w", err)
	}
	return out, nil
}

type MRFile struct {
	URL      string            `json:"url"`
	Filename string            `json:"filename"`
	Primary  bool              `json:"primary"`
	Size     int64             `json:"size"`
	Hashes   map[string]string `json:"hashes"`
}

type MRVersion struct {
	ID           string   `json:"id"`
	ProjectID    string   `json:"project_id"`
	Name         string   `json:"name"`
	Number       string   `json:"version_number"`
	GameVersions []string `json:"game_versions"`
	Loaders      []string `json:"loaders"`
	Files        []MRFile `json:"files"`
	Dependencies []struct {
		VersionID string `json:"version_id"`
		ProjectID string `json:"project_id"`
		Type      string `json:"dependency_type"`
	} `json:"dependencies"`
}

func (v *MRVersion) primary() *MRFile {
	for i := range v.Files {
		if v.Files[i].Primary {
			return &v.Files[i]
		}
	}
	if len(v.Files) > 0 {
		return &v.Files[0]
	}
	return nil
}

// ProjectVersions lists versions of a project compatible with game/loader (empty = any).
func ProjectVersions(ctx context.Context, id, game, loader string) ([]MRVersion, error) {
	q := url.Values{}
	if game != "" {
		b, _ := json.Marshal([]string{game})
		q.Set("game_versions", string(b))
	}
	if loader != "" {
		b, _ := json.Marshal([]string{loader})
		q.Set("loaders", string(b))
	}
	var vs []MRVersion
	if err := GetJSON(ctx, modrinthAPI+"/project/"+url.PathEscape(id)+"/version?"+q.Encode(), &vs); err != nil {
		return nil, err
	}
	return vs, nil
}

func getProject(ctx context.Context, id string) (*Project, error) {
	var p struct {
		ID    string `json:"id"`
		Slug  string `json:"slug"`
		Title string `json:"title"`
		Type  string `json:"project_type"`
		Icon  string `json:"icon_url"`
	}
	if err := GetJSON(ctx, modrinthAPI+"/project/"+url.PathEscape(id), &p); err != nil {
		return nil, err
	}
	return &Project{ID: p.ID, Slug: p.Slug, Title: p.Title, Type: p.Type, Icon: p.Icon}, nil
}

func contentDir(kind string) string {
	switch kind {
	case "resourcepack":
		return "resourcepacks"
	case "shader":
		return "shaderpacks"
	case "datapack":
		return "datapacks"
	}
	return "mods"
}

// InstallProject downloads a mod / resource pack / shader into an instance,
// pulling in required dependencies. Returns the titles that were installed.
// Each project's details and versions are fetched at the same time, and the
// dependencies of one level are resolved in parallel, so a mod with a few
// dependencies needs about two round trips instead of six or more.
func InstallProject(ctx context.Context, gameDir, instance, runDir, projectID string, rep Reporter) ([]string, error) {
	v, err := Resolve(gameDir, instance)
	if err != nil {
		return nil, err
	}
	game := GameVersion(gameDir, instance)
	loader := DetectLoader(v)
	var (
		mu     sync.Mutex
		files  []File
		titles []string
		seen   = map[string]bool{}
	)
	// what is already installed (by hash, so renamed files count too)
	var have map[string]bool
	haveDone := make(chan struct{})
	go func() {
		hctx, cancel := context.WithTimeout(ctx, 6*time.Second) // never hold an install up for long
		defer cancel()
		have = InstalledProjects(hctx, runDir, "mod")
		close(haveDone)
	}()
	var add func(id string, depth int) error
	add = func(id string, depth int) error {
		mu.Lock()
		if seen[id] || depth > 4 {
			mu.Unlock()
			return nil
		}
		seen[id] = true
		mu.Unlock()
		var p *Project
		var vs []MRVersion
		var pErr, vErr error
		var wg sync.WaitGroup
		wg.Add(2)
		go func() { defer wg.Done(); p, pErr = getProject(ctx, id) }()
		go func() { defer wg.Done(); vs, vErr = ProjectVersions(ctx, id, game, loader) }() // most projects are mods
		wg.Wait()
		if pErr != nil {
			return pErr
		}
		if p.Type != "mod" { // resource packs / shaders are loader-agnostic
			vs, vErr = ProjectVersions(ctx, id, game, "")
		}
		if vErr != nil {
			return vErr
		}
		if len(vs) == 0 {
			if depth == 0 {
				return fmt.Errorf("「%s」没有适用于 %s %s 的版本", p.Title, game, loader)
			}
			return nil
		}
		f := vs[0].primary()
		if f == nil {
			return nil
		}
		dir := filepath.Join(runDir, contentDir(p.Type))
		mu.Lock()
		files = append(files, File{URL: f.URL, Path: filepath.Join(dir, safeName(f.Filename)), SHA1: f.Hashes["sha1"], Size: f.Size})
		titles = append(titles, p.Title)
		mu.Unlock()
		if p.Type != "mod" {
			return nil
		}
		<-haveDone
		var deps []string
		for _, d := range vs[0].Dependencies {
			if d.Type == "required" && d.ProjectID != "" && !have[d.ProjectID] && !installedMod(dir, d.ProjectID) {
				deps = append(deps, d.ProjectID)
			}
		}
		errs := make([]error, len(deps))
		var dw sync.WaitGroup
		for i, d := range deps {
			dw.Add(1)
			go func(i int, d string) { defer dw.Done(); errs[i] = add(d, depth+1) }(i, d)
		}
		dw.Wait()
		for _, e := range errs {
			if e != nil {
				return e
			}
		}
		return nil
	}
	if err := add(projectID, 0); err != nil {
		return nil, err
	}
	return titles, Batch(ctx, "下载 "+strings.Join(titles, "、"), files, 16, rep)
}

// installedMod is a cheap check: a file whose name contains the slug/id.
func installedMod(dir, id string) bool {
	ents, _ := os.ReadDir(dir)
	id = strings.ToLower(id)
	for _, e := range ents {
		if strings.Contains(strings.ToLower(e.Name()), id) {
			return true
		}
	}
	return false
}

func safeName(s string) string {
	s = filepath.Base(filepath.FromSlash(s))
	if s == "." || s == ".." || s == "" {
		return "file.jar"
	}
	return s
}

// ---------------------------------------------------------------- modpacks (.mrpack)

type mrIndex struct {
	Name         string            `json:"name"`
	VersionID    string            `json:"versionId"`
	Dependencies map[string]string `json:"dependencies"`
	Files        []struct {
		Path      string            `json:"path"`
		Hashes    map[string]string `json:"hashes"`
		Env       map[string]string `json:"env"`
		Downloads []string          `json:"downloads"`
		Size      int64             `json:"fileSize"`
	} `json:"files"`
}

// InstallModpack installs the newest compatible version of a Modrinth modpack as a new instance.
func InstallModpack(ctx context.Context, env Env, projectID, name string) (string, error) {
	gameDir, rep := env.Cfg.GameDir, env.Rep
	p, err := getProject(ctx, projectID)
	if err != nil {
		return "", err
	}
	vs, err := ProjectVersions(ctx, projectID, "", "")
	if err != nil || len(vs) == 0 {
		return "", fmt.Errorf("整合包「%s」没有可用版本", p.Title)
	}
	// newest version with a loader we can install
	pick := -1
	for i, v := range vs {
		for _, l := range v.Loaders {
			if l == "fabric" || l == "quilt" || l == "forge" || l == "neoforge" {
				pick = i
				break
			}
		}
		if pick >= 0 {
			break
		}
	}
	if pick < 0 {
		return "", fmt.Errorf("「%s」没有可安装的版本", p.Title)
	}
	f := vs[pick].primary()
	if f == nil {
		return "", fmt.Errorf("整合包文件缺失")
	}
	tmp := filepath.Join(gameDir, ".eririlily-tmp", safeName(f.Filename))
	if err := Batch(ctx, "下载整合包", []File{{URL: f.URL, Path: tmp, SHA1: f.Hashes["sha1"], Size: f.Size}}, 1, rep); err != nil {
		return "", err
	}
	defer os.Remove(tmp)
	if name == "" {
		name = p.Title
	}
	id, err := InstallMrpack(ctx, env, tmp, name)
	if err == nil {
		stampMeta(gameDir, id, func(m *InstanceMeta) { m.Pack = p.Title; m.Icon = p.Icon })
	}
	return id, err
}

// InstallMrpack installs a local .mrpack file as a new instance.
func InstallMrpack(ctx context.Context, env Env, path, name string) (string, error) {
	gameDir, threads, rep := env.Cfg.GameDir, env.Cfg.Threads, env.Rep
	zr, err := zip.OpenReader(path)
	if err != nil {
		return "", fmt.Errorf("无法打开整合包：%w", err)
	}
	defer zr.Close()
	var idx mrIndex
	for _, f := range zr.File {
		if f.Name == "modrinth.index.json" {
			rc, err := f.Open()
			if err != nil {
				return "", err
			}
			err = json.NewDecoder(rc).Decode(&idx)
			rc.Close()
			if err != nil {
				return "", fmt.Errorf("整合包索引损坏：%w", err)
			}
		}
	}
	game := idx.Dependencies["minecraft"]
	if game == "" {
		return "", fmt.Errorf("这不是有效的 Modrinth 整合包")
	}
	loader, lv := "", ""
	switch {
	case idx.Dependencies["fabric-loader"] != "":
		loader, lv = "fabric", idx.Dependencies["fabric-loader"]
	case idx.Dependencies["quilt-loader"] != "":
		loader, lv = "quilt", idx.Dependencies["quilt-loader"]
	case idx.Dependencies["neoforge"] != "":
		loader, lv = "neoforge", idx.Dependencies["neoforge"]
		if game == "1.20.1" && !strings.HasPrefix(lv, "1.20.1-") {
			lv = "1.20.1-" + lv
		}
	case idx.Dependencies["forge"] != "":
		loader, lv = "forge", game+"-"+idx.Dependencies["forge"]
	}
	if name == "" {
		name = idx.Name
	}
	name = uniqueInstanceName(gameDir, sanitizeName(name))
	id, err := CreateInstance(ctx, env, game, loader, lv, name)
	if err != nil {
		return "", err
	}
	stampMeta(gameDir, id, func(m *InstanceMeta) { m.Isolated = true; m.Pack = idx.Name })
	runDir := VersionDir(gameDir, id)
	var files []File
	for _, f := range idx.Files {
		if f.Env != nil && f.Env["client"] == "unsupported" {
			continue
		}
		dst, ok := within(runDir, f.Path)
		if !ok || len(f.Downloads) == 0 {
			continue
		}
		files = append(files, File{URL: f.Downloads[0], Alt: f.Downloads[1:], Path: dst, SHA1: f.Hashes["sha1"], Size: f.Size})
	}
	if err := Batch(ctx, "下载整合包模组", files, threads, rep); err != nil {
		return id, err
	}
	for _, f := range zr.File {
		var rel string
		switch {
		case strings.HasPrefix(f.Name, "overrides/"):
			rel = strings.TrimPrefix(f.Name, "overrides/")
		case strings.HasPrefix(f.Name, "client-overrides/"):
			rel = strings.TrimPrefix(f.Name, "client-overrides/")
		default:
			continue
		}
		if rel == "" || f.FileInfo().IsDir() {
			continue
		}
		dst, ok := within(runDir, rel)
		if !ok {
			continue
		}
		if err := unzipOne(f, dst); err != nil {
			return id, err
		}
	}
	return id, nil
}

// within joins rel under base, refusing paths that escape it.
func within(base, rel string) (string, bool) {
	p := filepath.Join(base, filepath.FromSlash(rel))
	r, err := filepath.Rel(base, p)
	if err != nil || r == "." || strings.HasPrefix(r, "..") || filepath.IsAbs(rel) {
		return "", false
	}
	return p, true
}

func unzipOne(f *zip.File, dst string) error {
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	rc, err := f.Open()
	if err != nil {
		return err
	}
	defer rc.Close()
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer out.Close()
	_, err = io.Copy(out, rc)
	return err
}

func sanitizeName(s string) string {
	s = strings.Map(func(r rune) rune {
		switch r {
		case '\\', '/', ':', '*', '?', '"', '<', '>', '|':
			return '-'
		}
		if r < 32 {
			return -1
		}
		return r
	}, strings.TrimSpace(s))
	if s == "" || s == "." || s == ".." {
		s = "整合包-" + time.Now().Format("0102-1504")
	}
	return s
}

func uniqueInstanceName(gameDir, s string) string {
	name := s
	for i := 2; ; i++ {
		if _, err := os.Stat(VersionDir(gameDir, name)); err != nil {
			return name
		}
		name = fmt.Sprintf("%s (%d)", s, i)
	}
}

// ---------------------------------------------------------------- local content

type ContentFile struct {
	Name    string `json:"name"`
	File    string `json:"file"`
	Enabled bool   `json:"enabled"`
	Size    int64  `json:"size"`
}

// ListContent lists mods / resourcepacks / shaderpacks of an instance.
func ListContent(runDir, kind string) []ContentFile {
	ents, _ := os.ReadDir(filepath.Join(runDir, contentDir(kind)))
	var out []ContentFile
	for _, e := range ents {
		if e.IsDir() && kind == "mod" {
			continue
		}
		n := e.Name()
		low := strings.ToLower(n)
		if kind == "mod" && !(strings.HasSuffix(low, ".jar") || strings.HasSuffix(low, ".jar.disabled")) {
			continue
		}
		info, _ := e.Info()
		var size int64
		if info != nil {
			size = info.Size()
		}
		en := !strings.HasSuffix(low, ".disabled")
		out = append(out, ContentFile{Name: strings.TrimSuffix(strings.TrimSuffix(n, ".disabled"), ".jar"), File: n, Enabled: en, Size: size})
	}
	return out
}

// ToggleContent enables/disables a file by adding or removing ".disabled".
func ToggleContent(runDir, kind, file string) error {
	dir := filepath.Join(runDir, contentDir(kind))
	src, ok := within(dir, file)
	if !ok {
		return fmt.Errorf("非法文件名")
	}
	dst := strings.TrimSuffix(src, ".disabled")
	if dst == src {
		dst = src + ".disabled"
	}
	return os.Rename(src, dst)
}

func DeleteContent(runDir, kind, file string) error {
	p, ok := within(filepath.Join(runDir, contentDir(kind)), file)
	if !ok {
		return fmt.Errorf("非法文件名")
	}
	return os.Remove(p)
}
