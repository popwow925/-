package app

import (
	"context"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"eririlily/mc"
)

// extraMethods: saves and skins.
func (a *App) extraMethods() map[string]handler {
	return map[string]handler{
		"worlds": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Instance string }](p)
			type worldView struct {
				mc.World
				Cover int64 `json:"cover"`
			}
			var out []worldView
			for _, w := range mc.ListWorlds(a.runDir(q.Instance)) {
				out = append(out, worldView{w, mc.CoverVersion(a.DataDir, "world", q.Instance, w.Folder)})
			}
			return out, nil
		},
		"backupWorld": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Instance, Folder, Name string }](p)
			return nil, a.background("备份 "+firstNonEmpty(q.Name, q.Folder), func(ctx context.Context) error {
				path, err := mc.BackupWorld(ctx, a.runDir(q.Instance), q.Folder)
				if err == nil {
					a.emit("fixed", "已备份到 backups/"+filepath.Base(path))
					_ = mc.OpenPath(filepath.Dir(path))
				}
				return err
			})
		},
		"deleteWorld": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Instance, Folder string }](p)
			if a.gameRunning(q.Instance) {
				return nil, errors.New("游戏正在运行，先退出游戏再删除存档")
			}
			if err := mc.DeleteWorld(a.runDir(q.Instance), q.Folder); err != nil {
				return nil, err
			}
			mc.ResetCover(a.DataDir, "world", q.Instance, q.Folder)
			return nil, nil
		},
		"openWorld": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Instance, Folder string }](p)
			d, err := mc.WorldDir(a.runDir(q.Instance), q.Folder)
			if err != nil {
				return nil, err
			}
			return nil, mc.OpenPath(d)
		},

		"screenshots": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Instance string }](p)
			return mc.ListScreenshots(a.runDir(q.Instance)), nil
		},
		"coverFromShot": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Kind, Instance, World, File string }](p)
			sp, err := mc.ScreenshotPath(a.runDir(q.Instance), q.File)
			if err != nil {
				return nil, err
			}
			b, err := os.ReadFile(sp)
			if err != nil {
				return nil, err
			}
			kind, k2, err := coverTarget(q.Kind, q.World)
			if err != nil {
				return nil, err
			}
			return nil, mc.SetCover(a.DataDir, kind, q.Instance, k2, b)
		},
		"coverReset": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Kind, Instance, World string }](p)
			kind, k2, err := coverTarget(q.Kind, q.World)
			if err != nil {
				return nil, err
			}
			mc.ResetCover(a.DataDir, kind, q.Instance, k2)
			return nil, nil
		},

		"optimizeState": func(ctx context.Context, p json.RawMessage) (any, error) {
			return map[string]any{"supported": mc.OptimizeSupported(), "sticky": mc.StickyHotkeysOn()}, nil
		},
		"setSticky": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ On bool }](p)
			return nil, mc.SetStickyHotkeys(q.On)
		},
		"setGPU": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ On bool }](p)
			cfg := a.store.Update(func(c *mc.Config) { c.DedicatedGPU = q.On })
			mc.ApplyGPUPreference(cfg.GameDir, a.DataDir, q.On)
			return cfg, nil
		},
		"perfMods": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Instance string }](p)
			return nil, a.background("安装性能优化模组", func(ctx context.Context) error {
				added, err := mc.InstallPerfMods(ctx, a.store.Get().GameDir, q.Instance, a.runDir(q.Instance), a.progress)
				if err != nil {
					return err
				}
				if len(added) == 0 {
					a.emit("fixed", "性能模组都已装好，或没有适合这个版本的")
				} else {
					a.emit("fixed", "已安装："+strings.Join(added, "、"))
				}
				return nil
			})
		},
		"optimizeAll": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Instance string }](p)
			var done []string
			if mc.OptimizeSupported() {
				if mc.SetStickyHotkeys(false) == nil {
					done = append(done, "关闭粘滞键弹窗")
				}
			}
			cfg := a.store.Update(func(c *mc.Config) {
				c.HighPriority, c.DedicatedGPU, c.ZhCN = true, true, true
				if c.Threads < 64 {
					c.Threads = 64
				}
			})
			if mc.OptimizeSupported() {
				mc.ApplyGPUPreference(cfg.GameDir, a.DataDir, true)
				done = append(done, "独立显卡运行")
			}
			done = append(done, "提高进程优先级", "新实例默认中文")
			msg := "已完成：" + strings.Join(done, "、")
			if q.Instance != "" {
				if mc.InstanceLoader(cfg.GameDir, q.Instance) != "" {
					_ = a.background("安装性能优化模组", func(ctx context.Context) error {
						added, err := mc.InstallPerfMods(ctx, cfg.GameDir, q.Instance, a.runDir(q.Instance), a.progress)
						if err == nil && len(added) > 0 {
							a.emit("fixed", "已安装性能模组："+strings.Join(added, "、"))
						}
						return err
					})
					msg += "；正在为当前实例安装性能模组"
				}
			}
			return msg, nil
		},

		"mapInfo": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Instance, Folder string }](p)
			if a.store.Get().HideMap {
				return nil, errors.New("世界地图预览已在设置里关闭")
			}
			d, err := mc.WorldDir(a.runDir(q.Instance), q.Folder)
			if err != nil {
				return nil, err
			}
			return mc.WorldMapInfo(d), nil
		},

		"skinInfo": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ ID string }](p)
			acc, ok := a.accounts.Get(q.ID)
			if !ok {
				return nil, errors.New("账户不存在")
			}
			_, m := mc.AccountSkin(ctx, a.DataDir, acc)
			return m, nil
		},
		"skinImport": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ ID, Player string }](p)
			uuid, err := mc.LookupPlayer(ctx, strings.TrimSpace(q.Player))
			if err != nil {
				return nil, err
			}
			b, model, err := mc.ProfileSkin(ctx, uuid)
			if err != nil {
				return nil, err
			}
			return nil, mc.SaveCustomSkin(a.DataDir, q.ID, b, model, q.Player)
		},
		"skinModel": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ ID, Model string }](p)
			return nil, mc.SetSkinModel(a.DataDir, q.ID, q.Model)
		},
		"skinReset": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ ID string }](p)
			mc.ResetSkin(a.DataDir, q.ID)
			return nil, nil
		},
	}
}

func (a *App) gameRunning(id string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.game != nil && a.game.ID == id
}

func (a *App) skinImage(w http.ResponseWriter, r *http.Request) {
	if !a.authed(r) {
		http.Error(w, "forbidden", 403)
		return
	}
	acc, ok := a.accounts.Get(r.URL.Query().Get("id"))
	var b []byte
	m := mc.SkinMeta{Model: "classic", Source: "default"}
	if ok {
		b, m = mc.AccountSkin(r.Context(), a.DataDir, acc)
	}
	if b == nil {
		b, _ = webFS.ReadFile("web/skin_default.png")
		m.Source = "default"
	}
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Skin-Model", m.Model)
	w.Header().Set("X-Skin-Source", m.Source)
	_, _ = w.Write(b)
}

func (a *App) skinUpload(w http.ResponseWriter, r *http.Request) {
	if !a.authed(r) || r.Method != "POST" {
		http.Error(w, "forbidden", 403)
		return
	}
	q := r.URL.Query()
	b, _ := io.ReadAll(io.LimitReader(r.Body, 4<<20))
	acc, ok := a.accounts.Get(q.Get("id"))
	var err error
	switch {
	case !ok:
		err = errors.New("账户不存在")
	default:
		err = mc.SaveCustomSkin(a.DataDir, acc.ID, b, q.Get("model"), q.Get("name"))
		if err == nil && q.Get("official") == "1" {
			err = mc.UploadOfficialSkin(r.Context(), acc, b, q.Get("model"))
			if err == nil {
				mc.ResetSkin(a.DataDir, acc.ID) // the official skin now is the source of truth
				_ = os.Remove(filepath.Join(a.DataDir, "skins", "cache", acc.ID+".png"))
			}
		}
	}
	w.Header().Set("Content-Type", "application/json")
	if err != nil {
		w.WriteHeader(400)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}
	_ = json.NewEncoder(w).Encode(map[string]any{"result": nil})
}

func (a *App) worldIcon(w http.ResponseWriter, r *http.Request) {
	if !a.authed(r) {
		http.Error(w, "forbidden", 403)
		return
	}
	d, err := mc.WorldDir(a.runDir(r.URL.Query().Get("inst")), r.URL.Query().Get("world"))
	if err != nil {
		http.Error(w, "bad", 400)
		return
	}
	b, err := os.ReadFile(filepath.Join(d, "icon.png"))
	if err != nil {
		http.Error(w, "none", 404)
		return
	}
	w.Header().Set("Content-Type", "image/png")
	_, _ = w.Write(b)
}

func coverTarget(kind, world string) (string, string, error) {
	switch kind {
	case "inst":
		return "inst", "", nil
	case "world":
		if world == "" {
			return "", "", errors.New("缺少存档")
		}
		return "world", world, nil
	}
	return "", "", errors.New("未知类型")
}

func (a *App) cover(w http.ResponseWriter, r *http.Request) {
	if !a.authed(r) {
		http.Error(w, "forbidden", 403)
		return
	}
	q := r.URL.Query()
	kind, k2, err := coverTarget(q.Get("kind"), q.Get("world"))
	if err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	b, err := os.ReadFile(mc.CoverPath(a.DataDir, kind, q.Get("inst"), k2))
	if err != nil {
		http.Error(w, "none", 404)
		return
	}
	ct, _ := mc.ImageType(b)
	w.Header().Set("Content-Type", ct)
	w.Header().Set("Cache-Control", "max-age=31536000") // URLs carry a version
	_, _ = w.Write(b)
}

func (a *App) coverUpload(w http.ResponseWriter, r *http.Request) {
	if !a.authed(r) || r.Method != "POST" {
		http.Error(w, "forbidden", 403)
		return
	}
	q := r.URL.Query()
	b, _ := io.ReadAll(io.LimitReader(r.Body, 25<<20))
	kind, k2, err := coverTarget(q.Get("kind"), q.Get("world"))
	if err == nil {
		err = mc.SetCover(a.DataDir, kind, q.Get("inst"), k2, b)
	}
	w.Header().Set("Content-Type", "application/json")
	if err != nil {
		w.WriteHeader(400)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}
	_ = json.NewEncoder(w).Encode(map[string]any{"result": nil})
}

func (a *App) screenshot(w http.ResponseWriter, r *http.Request) {
	if !a.authed(r) {
		http.Error(w, "forbidden", 403)
		return
	}
	p, err := mc.ScreenshotPath(a.runDir(r.URL.Query().Get("inst")), r.URL.Query().Get("file"))
	if err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	w.Header().Set("Cache-Control", "max-age=3600")
	http.ServeFile(w, r, p)
}

// img proxies Modrinth icons: tries the mirror first, then the official CDN,
// and caches them on disk. Loading them straight in the page failed when the
// mirror refused the browser's request.
var imgSem = make(chan struct{}, 8)

func (a *App) img(w http.ResponseWriter, r *http.Request) {
	if !a.authed(r) {
		http.Error(w, "forbidden", 403)
		return
	}
	u := r.URL.Query().Get("u")
	pu, err := url.Parse(u)
	if err != nil || pu.Scheme != "https" || !(pu.Host == "cdn.modrinth.com" || strings.HasSuffix(pu.Host, ".modrinth.com")) {
		http.Error(w, "bad url", 400)
		return
	}
	sum := sha1.Sum([]byte(u))
	cp := filepath.Join(a.DataDir, "cache", "img", hex.EncodeToString(sum[:]))
	b, err := os.ReadFile(cp)
	if err != nil {
		imgSem <- struct{}{}
		ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
		b, err = mc.GetBytes(ctx, u)
		cancel()
		<-imgSem
		if err != nil {
			http.Error(w, err.Error(), 502)
			return
		}
		_ = os.MkdirAll(filepath.Dir(cp), 0o755)
		_ = os.WriteFile(cp, b, 0o644)
	}
	ct := http.DetectContentType(b)
	if strings.Contains(u, ".svg") {
		ct = "image/svg+xml"
	}
	w.Header().Set("Content-Type", ct)
	w.Header().Set("Cache-Control", "max-age=604800")
	_, _ = w.Write(b)
}

var mapSem = make(chan struct{}, 3)

// mapTile renders one 512×512 region of a save (cached under the launcher's data folder).
func (a *App) mapTile(w http.ResponseWriter, r *http.Request) {
	if !a.authed(r) {
		http.Error(w, "forbidden", 403)
		return
	}
	q := r.URL.Query()
	d, err := mc.WorldDir(a.runDir(q.Get("inst")), q.Get("world"))
	if err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	rx, e1 := strconv.Atoi(q.Get("rx"))
	rz, e2 := strconv.Atoi(q.Get("rz"))
	if e1 != nil || e2 != nil {
		http.Error(w, "bad region", 400)
		return
	}
	sum := sha1.Sum([]byte(d))
	cache := filepath.Join(a.DataDir, "cache", "map", hex.EncodeToString(sum[:8]))
	select {
	case mapSem <- struct{}{}:
	case <-r.Context().Done():
		return
	}
	b, err := mc.RegionTile(cache, d, q.Get("dim"), rx, rz)
	<-mapSem
	if err != nil {
		http.Error(w, err.Error(), 404)
		return
	}
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "no-cache")
	_, _ = w.Write(b)
}
