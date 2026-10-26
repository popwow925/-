package mc

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestIdentifyAndUpdate(t *testing.T) {
	old := modrinthAPI
	defer func() { modrinthAPI = old }()
	CacheDir = t.TempDir()
	mcache = &modCache{}
	run := t.TempDir()
	mods := filepath.Join(run, "mods")
	os.MkdirAll(mods, 0o755)
	os.WriteFile(filepath.Join(mods, "sodium-0.5.jar"), []byte("sodium old"), 0o644)
	os.WriteFile(filepath.Join(mods, "mystery.jar.disabled"), []byte("lithium old"), 0o644)
	os.WriteFile(filepath.Join(mods, "local.jar"), []byte("not on modrinth"), 0o644)
	hs, _ := fileSHA1(filepath.Join(mods, "sodium-0.5.jar"))
	hl, _ := fileSHA1(filepath.Join(mods, "mystery.jar.disabled"))
	newBody := []byte("sodium new")
	var srv *httptest.Server
	calls := 0
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		switch {
		case r.URL.Path == "/v2/version_files":
			json.NewEncoder(w).Encode(map[string]any{
				hs: map[string]any{"id": "v1", "project_id": "AANobbMI", "version_number": "0.5"},
				hl: map[string]any{"id": "v7", "project_id": "gvQqBUqZ", "version_number": "0.2"},
			})
		case r.URL.Path == "/v2/projects":
			if !strings.Contains(r.URL.Query().Get("ids"), "AANobbMI") {
				t.Errorf("ids %s", r.URL.RawQuery)
			}
			json.NewEncoder(w).Encode([]any{
				map[string]any{"id": "AANobbMI", "slug": "sodium", "title": "Sodium", "icon_url": "https://cdn.modrinth.com/x.png"},
				map[string]any{"id": "gvQqBUqZ", "slug": "lithium", "title": "Lithium"},
			})
		case r.URL.Path == "/v2/version_files/update":
			var body struct {
				Loaders []string `json:"loaders"`
				Games   []string `json:"game_versions"`
			}
			json.NewDecoder(r.Body).Decode(&body)
			if body.Loaders[0] != "fabric" || body.Games[0] != "1.21.1" {
				t.Errorf("body %+v", body)
			}
			json.NewEncoder(w).Encode(map[string]any{
				hs: map[string]any{"id": "v2", "project_id": "AANobbMI", "version_number": "0.6",
					"files": []any{map[string]any{"url": srv.URL + "/f/sodium-0.6.jar", "filename": "sodium-0.6.jar", "primary": true,
						"size": len(newBody), "hashes": map[string]any{"sha1": sha1hex(newBody)}}}},
				hl: map[string]any{"id": "v7", "project_id": "gvQqBUqZ"}, // already newest
			})
		case r.URL.Path == "/f/sodium-0.6.jar":
			w.Write(newBody)
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	modrinthAPI = srv.URL + "/v2"
	items, err := Identify(context.Background(), run, "mod")
	if err != nil || len(items) != 3 {
		t.Fatal(err, items)
	}
	titles := map[string]string{}
	for _, it := range items {
		titles[it.File] = it.Title
	}
	if titles["sodium-0.5.jar"] != "Sodium" || titles["mystery.jar.disabled"] != "Lithium" || titles["local.jar"] != "" {
		t.Fatalf("%v", titles)
	}
	// second call: everything cached, no requests
	before := calls
	if _, err := Identify(context.Background(), run, "mod"); err != nil || calls != before {
		t.Fatalf("cache miss: %d calls", calls-before)
	}
	ups, err := CheckUpdates(context.Background(), run, "mod", "1.21.1", "fabric")
	if err != nil || len(ups) != 1 || ups[0].To != "0.6" || ups[0].From != "0.5" {
		t.Fatalf("%v %+v", err, ups)
	}
	n, err := ApplyUpdates(context.Background(), run, "mod", ups, 4, nil)
	if err != nil || n != 1 {
		t.Fatal(n, err)
	}
	if _, err := os.Stat(filepath.Join(mods, "sodium-0.5.jar")); err == nil {
		t.Fatal("old file kept")
	}
	if b, _ := os.ReadFile(filepath.Join(mods, "sodium-0.6.jar")); string(b) != "sodium new" {
		t.Fatal("new file missing")
	}
	// cache survives a restart
	mcache = &modCache{}
	have := InstalledProjects(context.Background(), run, "mod")
	if !have["gvQqBUqZ"] {
		t.Fatalf("%v", have)
	}
}

func sha1hex(b []byte) string {
	p := filepath.Join(os.TempDir(), "el-sha1-tmp")
	os.WriteFile(p, b, 0o644)
	defer os.Remove(p)
	h, _ := fileSHA1(p)
	return h
}
