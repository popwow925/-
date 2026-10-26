package mc

import (
	"bytes"
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

// CacheDir is where small lookup caches live (set by the app to <data>/cache).
var CacheDir string

// ---------------------------------------------------------------- POST helper

// PostJSON posts a JSON body, trying the mirror and the official API in the
// configured order, and decodes the answer into out.
func PostJSON(ctx context.Context, u string, body, out any) error {
	ensureProbe(ctx)
	bb, err := json.Marshal(body)
	if err != nil {
		return err
	}
	var last error
	for _, c := range Candidates(u) {
		cctx, cancel := context.WithTimeout(ctx, 15*time.Second)
		req, err := newReq(cctx, "POST", c, bytes.NewReader(bb))
		if err != nil {
			cancel()
			return err
		}
		req.Header.Set("Content-Type", "application/json")
		resp, err := client.Do(req)
		if err != nil {
			cancel()
			if ctx.Err() != nil {
				return ctx.Err()
			}
			markFail(c)
			last = netErr(c, err)
			continue
		}
		b, err := io.ReadAll(io.LimitReader(resp.Body, 32<<20))
		resp.Body.Close()
		cancel()
		if err == nil && resp.StatusCode == 200 {
			markOK(c)
			return json.Unmarshal(b, out)
		}
		if err == nil {
			err = fmt.Errorf("%s 返回 HTTP %d", host(c), resp.StatusCode)
		}
		last = err
	}
	return last
}

// ---------------------------------------------------------------- local hashes

type hashRec struct {
	Size int64  `json:"s"`
	Mod  int64  `json:"m"`
	SHA1 string `json:"h"`
}

// ModID is what Modrinth knows about one file (by its SHA-1).
type ModID struct {
	Project string `json:"p,omitempty"`
	Version string `json:"v,omitempty"`
	Number  string `json:"n,omitempty"`
	Title   string `json:"t,omitempty"`
	Icon    string `json:"i,omitempty"`
	Slug    string `json:"g,omitempty"`
	Checked int64  `json:"c"`
}

type modCache struct {
	mu     sync.Mutex
	loaded bool
	dirty  bool
	Hashes map[string]hashRec `json:"hashes"` // path -> hash
	IDs    map[string]ModID   `json:"ids"`    // sha1 -> identity ("" project = not on Modrinth)
}

var mcache = &modCache{}

func (c *modCache) load() {
	if c.loaded {
		return
	}
	c.loaded = true
	c.Hashes, c.IDs = map[string]hashRec{}, map[string]ModID{}
	if CacheDir != "" {
		if b, err := os.ReadFile(filepath.Join(CacheDir, "mods.json")); err == nil {
			_ = json.Unmarshal(b, c)
			if c.Hashes == nil {
				c.Hashes = map[string]hashRec{}
			}
			if c.IDs == nil {
				c.IDs = map[string]ModID{}
			}
		}
	}
}

func (c *modCache) save() {
	if !c.dirty || CacheDir == "" {
		return
	}
	c.dirty = false
	if len(c.Hashes) > 20000 { // keep the file small: forget old paths
		c.Hashes = map[string]hashRec{}
	}
	_ = writeJSON(filepath.Join(CacheDir, "mods.json"), c)
}

// sha1Of returns the SHA-1 of a file, reusing the cached value while its size
// and modification time are unchanged.
func sha1Of(p string) string {
	st, err := os.Stat(p)
	if err != nil {
		return ""
	}
	mcache.mu.Lock()
	mcache.load()
	r, ok := mcache.Hashes[p]
	mcache.mu.Unlock()
	if ok && r.Size == st.Size() && r.Mod == st.ModTime().UnixNano() {
		return r.SHA1
	}
	h, err := fileSHA1(p)
	if err != nil {
		return ""
	}
	mcache.mu.Lock()
	mcache.Hashes[p] = hashRec{st.Size(), st.ModTime().UnixNano(), h}
	mcache.dirty = true
	mcache.mu.Unlock()
	return h
}

// ---------------------------------------------------------------- identify

// ContentItem is a file in mods/resourcepacks/shaderpacks plus its Modrinth identity.
type ContentItem struct {
	ContentFile
	SHA1    string `json:"sha1"`
	Project string `json:"project,omitempty"`
	Title   string `json:"title,omitempty"`
	Icon    string `json:"icon,omitempty"`
	Version string `json:"version,omitempty"`
	Slug    string `json:"slug,omitempty"`
}

func hashAll(dir string, files []ContentFile) []string {
	out := make([]string, len(files))
	var wg sync.WaitGroup
	sem := make(chan struct{}, 4)
	for i, f := range files {
		wg.Add(1)
		sem <- struct{}{}
		go func(i int, f ContentFile) {
			defer wg.Done()
			defer func() { <-sem }()
			out[i] = sha1Of(filepath.Join(dir, f.File))
		}(i, f)
	}
	wg.Wait()
	return out
}

// Identify lists an instance's content with names and icons from Modrinth.
// Files are matched by hash (works for renamed files); results are cached, so
// only new files cost a request. Offline it still returns the local list.
func Identify(ctx context.Context, runDir, kind string) ([]ContentItem, error) {
	files := ListContent(runDir, kind)
	dir := filepath.Join(runDir, contentDir(kind))
	hs := hashAll(dir, files)
	mcache.mu.Lock()
	mcache.load()
	var ask []string
	now := time.Now().Unix()
	for _, h := range hs {
		if h == "" {
			continue
		}
		id, ok := mcache.IDs[h]
		// unknown files are asked about again after a week (they may have been published since)
		if !ok || (id.Project == "" && now-id.Checked > 7*86400) {
			ask = append(ask, h)
		}
	}
	mcache.mu.Unlock()
	var netErr error
	if len(ask) > 0 {
		netErr = lookupHashes(ctx, ask)
	}
	mcache.mu.Lock()
	out := make([]ContentItem, len(files))
	for i, f := range files {
		it := ContentItem{ContentFile: f, SHA1: hs[i]}
		if id, ok := mcache.IDs[hs[i]]; ok && id.Project != "" {
			it.Project, it.Title, it.Icon, it.Version, it.Slug = id.Project, id.Title, id.Icon, id.Number, id.Slug
		}
		out[i] = it
	}
	mcache.save()
	mcache.mu.Unlock()
	if netErr != nil && len(ask) == len(hs) {
		return out, netErr
	}
	return out, nil
}

func lookupHashes(ctx context.Context, hashes []string) error {
	got := map[string]MRVersion{}
	for i := 0; i < len(hashes); i += 400 {
		part := hashes[i:min(i+400, len(hashes))]
		var m map[string]MRVersion
		if err := PostJSON(ctx, modrinthAPI+"/version_files", map[string]any{"hashes": part, "algorithm": "sha1"}, &m); err != nil {
			return err
		}
		for k, v := range m {
			got[strings.ToLower(k)] = v
		}
	}
	// titles and icons of the projects we found
	var ids []string
	seen := map[string]bool{}
	for _, v := range got {
		if v.ProjectID != "" && !seen[v.ProjectID] {
			seen[v.ProjectID] = true
			ids = append(ids, v.ProjectID)
		}
	}
	projects := map[string]Project{}
	for i := 0; i < len(ids); i += 200 {
		part := ids[i:min(i+200, len(ids))]
		b, _ := json.Marshal(part)
		var ps []struct {
			ID    string `json:"id"`
			Slug  string `json:"slug"`
			Title string `json:"title"`
			Icon  string `json:"icon_url"`
		}
		if err := GetJSON(ctx, modrinthAPI+"/projects?ids="+url.QueryEscape(string(b)), &ps); err == nil {
			for _, p := range ps {
				projects[p.ID] = Project{ID: p.ID, Slug: p.Slug, Title: p.Title, Icon: p.Icon}
			}
		}
	}
	now := time.Now().Unix()
	mcache.mu.Lock()
	defer mcache.mu.Unlock()
	for _, h := range hashes {
		id := ModID{Checked: now}
		if v, ok := got[h]; ok {
			p := projects[v.ProjectID]
			id = ModID{Project: v.ProjectID, Version: v.ID, Number: v.Number, Title: p.Title, Icon: p.Icon, Slug: p.Slug, Checked: now}
		}
		mcache.IDs[h] = id
	}
	mcache.dirty = true
	return nil
}

// InstalledProjects returns the Modrinth project ids present in a content folder.
func InstalledProjects(ctx context.Context, runDir, kind string) map[string]bool {
	items, _ := Identify(ctx, runDir, kind)
	out := map[string]bool{}
	for _, it := range items {
		if it.Project != "" {
			out[it.Project] = true
		}
	}
	return out
}

// ---------------------------------------------------------------- updates

type ContentUpdate struct {
	File     string `json:"file"` // current file name
	Title    string `json:"title"`
	Icon     string `json:"icon,omitempty"`
	From     string `json:"from"`
	To       string `json:"to"`
	Project  string `json:"project"`
	URL      string `json:"url"`
	Filename string `json:"filename"`
	SHA1     string `json:"sha1"`
	Size     int64  `json:"size"`
}

func updateLoaders(kind, loader string) []string {
	switch kind {
	case "resourcepack":
		return []string{"minecraft"}
	case "shader":
		return []string{"iris", "optifine", "canvas", "vanilla"}
	}
	if loader == "quilt" {
		return []string{"quilt", "fabric"}
	}
	if loader == "" {
		return nil
	}
	return []string{loader}
}

// CheckUpdates asks Modrinth for the newest compatible version of every
// identified file in one request.
func CheckUpdates(ctx context.Context, runDir, kind, game, loader string) ([]ContentUpdate, error) {
	items, err := Identify(ctx, runDir, kind)
	if err != nil {
		return nil, err
	}
	byHash := map[string]ContentItem{}
	var hashes []string
	for _, it := range items {
		if it.Project != "" && it.SHA1 != "" {
			byHash[it.SHA1] = it
			hashes = append(hashes, it.SHA1)
		}
	}
	lds := updateLoaders(kind, loader)
	if len(hashes) == 0 || (kind == "mod" && lds == nil) {
		return nil, nil
	}
	body := map[string]any{"hashes": hashes, "algorithm": "sha1", "loaders": lds}
	if game != "" && reGameVer.MatchString(" "+game+" ") {
		body["game_versions"] = []string{game}
	}
	var m map[string]MRVersion
	if err := PostJSON(ctx, modrinthAPI+"/version_files/update", body, &m); err != nil {
		return nil, fmt.Errorf("检查更新失败：%w", err)
	}
	mcache.mu.Lock()
	cur := map[string]string{}
	for h := range byHash {
		cur[h] = mcache.IDs[h].Version
	}
	mcache.mu.Unlock()
	var out []ContentUpdate
	for h, v := range m {
		h = strings.ToLower(h)
		it, ok := byHash[h]
		if !ok || v.ID == "" || v.ID == cur[h] {
			continue
		}
		f := v.primary()
		if f == nil || strings.EqualFold(f.Hashes["sha1"], h) {
			continue
		}
		out = append(out, ContentUpdate{File: it.File, Title: it.Title, Icon: it.Icon, From: it.Version, To: v.Number,
			Project: it.Project, URL: f.URL, Filename: safeName(f.Filename), SHA1: f.Hashes["sha1"], Size: f.Size})
	}
	return out, nil
}

// ApplyUpdates downloads the new files, then removes the old ones. A disabled
// file stays disabled after its update.
func ApplyUpdates(ctx context.Context, runDir, kind string, ups []ContentUpdate, threads int, rep Reporter) (int, error) {
	dir := filepath.Join(runDir, contentDir(kind))
	var files []File
	type swap struct{ old, new string }
	var swaps []swap
	for _, u := range ups {
		if !strings.HasPrefix(u.URL, "https://") && !strings.HasPrefix(u.URL, "http://") {
			continue
		}
		old, ok := within(dir, u.File)
		if !ok {
			continue
		}
		name := u.Filename
		if strings.HasSuffix(strings.ToLower(u.File), ".disabled") {
			name += ".disabled"
		}
		dst, ok := within(dir, name)
		if !ok {
			continue
		}
		files = append(files, File{URL: u.URL, Path: dst, SHA1: u.SHA1, Size: u.Size})
		swaps = append(swaps, swap{old, dst})
	}
	if len(files) == 0 {
		return 0, nil
	}
	err := Batch(ctx, "更新 "+itoa(len(files))+" 个"+map[string]string{"mod": "模组", "resourcepack": "资源包", "shader": "光影"}[kind], files, threads, rep)
	// a finished download is verified and in place (partial ones stay .part
	// files): swap exactly those, so a failed batch never leaves two copies of a mod
	n := 0
	for i, s := range swaps {
		if !files[i].Present(false) {
			continue
		}
		n++
		if !strings.EqualFold(s.old, s.new) {
			_ = os.Remove(s.old)
		}
	}
	return n, err
}
