package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"eririlily/mc"
)

// Android: runtimes, renderers, controls and the one-tap optimiser.

// gameOptionKeys are the options.txt keys the phone UI edits.
var gameOptionKeys = []string{"renderDistance", "simulationDistance", "maxFps", "guiScale", "graphicsMode", "fov",
	"particles", "entityShadows", "renderClouds", "biomeBlendRadius", "mipmapLevels", "ao", "gamma", "enableVsync",
	"entityDistanceScaling", "bobView", "soundCategory_master", "lang", "autoJump", "toggleCrouch", "toggleSprint"}

func (a *App) androidMethods() map[string]handler {
	return map[string]handler{
		"androidInfo": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Refresh bool }](p)
			cfg := a.store.Get()
			javas := mc.DetectJava(cfg.GameDir, a.DataDir, q.Refresh)
			if javas == nil {
				javas = []*mc.Java{}
			}
			_, bridgeErr := os.Stat(filepath.Join(mc.AEnv.NativeDir, "libelbridge.so"))
			d := mc.AEnv.Device
			return map[string]any{
				"android": mc.Android, "abi": mc.AEnv.ABI, "device": d, "tier": d.Tier(),
				"runtimes": javas, "bundled": mc.BundledRuntimes(), "renderers": mc.Renderers(a.DataDir),
				"bridge": bridgeErr == nil, "layouts": a.layouts(), "memTotal": mc.TotalMemoryMB(),
				"autoMem": mc.AutoMemory(false), "autoMemModded": mc.AutoMemory(true),
			}, nil
		},

		"installJre": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Major int }](p)
			if q.Major <= 0 {
				return nil, errors.New("无效的 Java 版本")
			}
			return nil, a.background(fmt.Sprintf("安装 Java %d", q.Major), func(ctx context.Context) error {
				j, err := mc.InstallRuntime(ctx, a.DataDir, mc.ComponentFor(q.Major), a.store.Get().Threads, a.progress)
				if err != nil {
					return err
				}
				a.emit("fixed", "已安装 Java "+j.Version)
				return nil
			})
		},

		"removeJre": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Home string }](p)
			if a.gameRunningAny() {
				return nil, errors.New("游戏正在运行")
			}
			return nil, mc.RemoveRuntime(a.DataDir, q.Home)
		},

		"removeRenderer": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ ID string }](p)
			name := strings.TrimPrefix(q.ID, "plugin:")
			if name == q.ID || name == "" || strings.ContainsAny(name, `/\`) || name == "." || name == ".." {
				return nil, errors.New("只能删除导入的渲染器")
			}
			a.store.Update(func(c *mc.Config) {
				if c.Renderer == q.ID {
					c.Renderer = "auto"
				}
			})
			return nil, os.RemoveAll(filepath.Join(a.DataDir, "renderers", name))
		},

		"optimizePlan": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Instance, Level string }](p)
			game := ""
			if q.Instance != "" {
				game = mc.GameVersion(a.store.Get().GameDir, q.Instance)
			}
			return mc.PlanOptimize(mc.AEnv.Device, q.Level, game), nil
		},

		// one tap: device-tuned config + options.txt + phone-safe performance mods
		"superOptimize": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct {
				Instance, Level string
				Mods            bool
			}](p)
			cfg := a.store.Get()
			game := ""
			if q.Instance != "" {
				game = mc.GameVersion(cfg.GameDir, q.Instance)
			}
			plan := mc.PlanOptimize(mc.AEnv.Device, q.Level, game)
			a.store.Update(func(c *mc.Config) {
				c.RenderScale, c.FpsLimit, c.Vsync = plan.RenderScale, plan.FpsLimit, false
				c.SustainedPerf, c.BigCores, c.PerfHint, c.ZhCN = true, true, true, true
				c.Renderer, c.MemoryMB = "auto", 0
				if c.PerfHud == "off" || c.PerfHud == "" {
					c.PerfHud = "fps"
				}
			})
			changes := plan.Changes
			if q.Instance != "" {
				if a.gameRunning(q.Instance) {
					changes = append(changes, "游戏正在运行：游戏选项将在下次进入前写入失败，请退出后再优化一次")
				} else if err := mc.ApplyOptions(a.runDir(q.Instance), plan.Options); err == nil {
					changes = append(changes, "已写入「"+q.Instance+"」的游戏选项")
				}
				if q.Mods && mc.InstanceLoader(cfg.GameDir, q.Instance) != "" {
					inst, render := q.Instance, plan.RenderMods
					_ = a.background("安装性能优化模组", func(ctx context.Context) error {
						added, err := mc.InstallPerfMods(ctx, cfg.GameDir, inst, a.runDir(inst), a.progress)
						if render && err == nil {
							more, _ := mc.InstallRenderMods(ctx, cfg.GameDir, inst, a.runDir(inst), a.progress)
							added = append(added, more...)
						}
						if err == nil {
							if len(added) > 0 {
								a.emit("fixed", "已安装性能模组："+strings.Join(added, "、"))
							} else {
								a.emit("fixed", "性能模组都已装好，或没有适合这个版本的")
							}
						}
						return err
					})
					changes = append(changes, "正在后台安装手机适用的性能模组")
				}
			}
			return map[string]any{"plan": plan, "changes": changes, "config": a.store.Get()}, nil
		},

		"gameOptions": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Instance string }](p)
			return mc.ReadOptions(a.runDir(q.Instance), gameOptionKeys), nil
		},

		"setGameOptions": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct {
				Instance string
				Options  map[string]string
			}](p)
			if a.gameRunning(q.Instance) {
				return nil, errors.New("游戏正在运行时修改会被游戏覆盖，先退出游戏")
			}
			kv := map[string]string{}
			allowed := map[string]bool{}
			for _, k := range gameOptionKeys {
				allowed[k] = true
			}
			for k, v := range q.Options {
				if allowed[k] && !strings.ContainsAny(v, "\r\n") {
					kv[k] = v
				}
			}
			return nil, mc.ApplyOptions(a.runDir(q.Instance), kv)
		},

		"deleteLayout": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Name string }](p)
			if q.Name == "" || q.Name == "default" || strings.ContainsAny(q.Name, `/\`) || strings.HasPrefix(q.Name, ".") {
				return nil, errors.New("默认布局不能删除")
			}
			a.store.Update(func(c *mc.Config) {
				if c.ControlsLayout == q.Name {
					c.ControlsLayout = "default"
				}
			})
			return nil, os.Remove(filepath.Join(a.DataDir, "controls", q.Name+".json"))
		},
	}
}

func (a *App) layouts() []string {
	out := []string{"default"}
	ents, _ := os.ReadDir(filepath.Join(a.DataDir, "controls"))
	for _, e := range ents {
		if n := strings.TrimSuffix(e.Name(), ".json"); n != e.Name() && n != "default" {
			out = append(out, n)
		}
	}
	sort.Strings(out[1:])
	return out
}

func (a *App) gameRunningAny() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.game != nil
}

// importFile receives a JRE or a renderer plugin picked in the UI.
func (a *App) importFile(w http.ResponseWriter, r *http.Request) {
	if !a.authed(r) || r.Method != "POST" {
		http.Error(w, "forbidden", 403)
		return
	}
	kind, name := r.URL.Query().Get("kind"), filepath.Base(r.URL.Query().Get("name"))
	tmpDir := filepath.Join(a.DataDir, "cache")
	_ = os.MkdirAll(tmpDir, 0o755)
	f, err := os.CreateTemp(tmpDir, "import-*")
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	_, err = io.Copy(f, io.LimitReader(r.Body, 1<<30))
	f.Close()
	reply := func(err error) {
		w.Header().Set("Content-Type", "application/json")
		if err != nil {
			os.Remove(f.Name())
			w.WriteHeader(400)
			_ = json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"result": nil})
	}
	if err != nil {
		reply(err)
		return
	}
	switch kind {
	case "jre":
		reply(a.background("导入 Java 运行时", func(ctx context.Context) error {
			defer os.Remove(f.Name())
			a.emit("stage", "解压运行时")
			j, err := mc.InstallRuntimeArchive(ctx, a.DataDir, f.Name(), name)
			if err != nil {
				return err
			}
			a.emit("fixed", "已导入 Java "+j.Version)
			return nil
		}))
	case "renderer":
		defer os.Remove(f.Name())
		rd, err := mc.ImportRenderer(a.DataDir, f.Name(), name)
		if err == nil {
			a.emit("fixed", "已导入渲染器 "+rd.Name)
		}
		reply(err)
	default:
		reply(errors.New("未知的导入类型"))
	}
}
