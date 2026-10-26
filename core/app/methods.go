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
	"strings"
	"time"

	"eririlily/mc"
)

func (a *App) methods() map[string]handler {
	return map[string]handler{
		"state": func(ctx context.Context, p json.RawMessage) (any, error) { return a.state(), nil },

		"versions": func(ctx context.Context, p json.RawMessage) (any, error) {
			m, err := mc.GetManifest(ctx)
			if err != nil {
				return nil, err
			}
			return m, nil
		},

		"loaderVersions": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Loader, Game string }](p)
			return mc.LoaderVersions(ctx, q.Loader, q.Game)
		},

		"createInstance": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Game, Loader, LoaderVersion, Name string }](p)
			if q.Game == "" {
				return nil, errors.New("请选择游戏版本")
			}
			return nil, a.background("安装 "+firstNonEmpty(q.Name, q.Game), func(ctx context.Context) error {
				cfg := a.store.Get()
				a.emit("stage", "获取版本信息")
				id, err := mc.CreateInstance(ctx, a.env(), q.Game, q.Loader, q.LoaderVersion, strings.TrimSpace(q.Name))
				if err != nil {
					return err
				}
				a.store.Update(func(c *mc.Config) { c.Selected = id })
				a.emit("stage", "下载游戏文件")
				if err := mc.Complete(ctx, cfg.GameDir, id, cfg.Threads, false, a.progress); err != nil {
					return err
				}
				a.emit("installed", id)
				return nil
			})
		},

		"deleteInstance": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ ID string }](p)
			cfg := a.store.Get()
			if q.ID == "" || strings.ContainsAny(q.ID, `/\`) || q.ID == "." || q.ID == ".." {
				return nil, errors.New("无效实例")
			}
			// keep vanilla versions other instances inherit from
			for _, v := range mc.ListInstances(cfg.GameDir) {
				if v.InheritsFrom == q.ID {
					return nil, fmt.Errorf("「%s」依赖这个版本，请先删除它", v.ID)
				}
			}
			a.mu.Lock()
			running := a.game != nil && a.game.ID == q.ID
			a.mu.Unlock()
			if running {
				return nil, errors.New("游戏正在运行")
			}
			if err := os.RemoveAll(mc.VersionDir(cfg.GameDir, q.ID)); err != nil {
				return nil, err
			}
			mc.ResetCover(a.DataDir, "inst", q.ID, "")
			a.store.Update(func(c *mc.Config) {
				if c.Selected == q.ID {
					c.Selected = ""
				}
			})
			return a.state(), nil
		},

		"renameInstance": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ ID, Name string }](p)
			name := strings.TrimSpace(q.Name)
			if name == "" {
				return nil, errors.New("名字不能为空")
			}
			mc.StampMeta(a.store.Get().GameDir, q.ID, func(m *mc.InstanceMeta) { m.Name = name })
			return nil, nil
		},

		"select": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ ID string }](p)
			cfg := a.store.Update(func(c *mc.Config) { c.Selected = q.ID })
			go mc.Prewarm(cfg.GameDir, q.ID)
			return nil, nil
		},

		"setConfig": func(ctx context.Context, p json.RawMessage) (any, error) {
			cfg := a.store.Get()
			b, _ := json.Marshal(cfg)
			var m map[string]any
			_ = json.Unmarshal(b, &m)
			var patch map[string]any
			if err := json.Unmarshal(p, &patch); err != nil {
				return nil, err
			}
			for k, v := range patch {
				m[k] = v
			}
			b, _ = json.Marshal(m)
			var next mc.Config
			if err := json.Unmarshal(b, &next); err != nil {
				return nil, err
			}
			if next.Threads < 1 || next.Threads > 256 {
				next.Threads = 64
			}
			out := a.store.Update(func(c *mc.Config) { *c = next })
			mc.Source.Store(out.Source)
			mc.RuntimeMirror = out.RuntimeMirror
			return out, nil
		},

		"launch": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ ID, Server string }](p)
			return nil, a.launch(q.ID, q.Server)
		},

		"cancel": func(ctx context.Context, p json.RawMessage) (any, error) {
			a.mu.Lock()
			if a.cancel != nil {
				a.cancel()
			}
			a.mu.Unlock()
			return nil, nil
		},

		"kill": func(ctx context.Context, p json.RawMessage) (any, error) {
			a.mu.Lock()
			g := a.game
			a.mu.Unlock()
			if g != nil {
				g.Kill()
			}
			return nil, nil
		},

		"logs": func(ctx context.Context, p json.RawMessage) (any, error) {
			a.mu.Lock()
			defer a.mu.Unlock()
			if a.game != nil {
				return a.game.Logs(), nil
			}
			return a.lastLog, nil
		},

		"exportLog": func(ctx context.Context, p json.RawMessage) (any, error) {
			a.mu.Lock()
			lines := a.lastLog
			if a.game != nil {
				lines = a.game.Logs()
			}
			a.mu.Unlock()
			dir := filepath.Join(a.DataDir, "logs")
			_ = os.MkdirAll(dir, 0o755)
			path := filepath.Join(dir, "game-"+time.Now().Format("20060102-150405")+".log")
			if err := os.WriteFile(path, []byte(strings.Join(lines, "\r\n")), 0o644); err != nil {
				return nil, err
			}
			_ = mc.OpenPath(dir)
			return path, nil
		},

		// ---------------------------------------------------------------- accounts
		"addOffline": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Name string }](p)
			acc, err := mc.OfflineAccount(strings.TrimSpace(q.Name))
			if err != nil {
				return nil, err
			}
			a.accounts.Put(acc)
			a.store.Update(func(c *mc.Config) { c.Account = acc.ID })
			return acc.Public(), nil
		},

		"msLogin": func(ctx context.Context, p json.RawMessage) (any, error) {
			cfg := a.store.Get()
			dc, err := mc.MSDeviceCode(ctx, mc.MSClient(cfg.MSClientID))
			if err != nil {
				return nil, err
			}
			a.msMu.Lock()
			if a.msStop != nil {
				a.msStop()
			}
			pctx, stop := context.WithTimeout(context.Background(), time.Duration(dc.ExpiresIn)*time.Second)
			a.msStop = stop
			a.msMu.Unlock()
			go func() {
				defer stop()
				acc, err := mc.MSPoll(pctx, mc.MSClient(cfg.MSClientID), dc)
				if err != nil {
					if !errors.Is(err, context.Canceled) {
						a.emit("account", map[string]any{"error": err.Error()})
					}
					return
				}
				a.accounts.Put(acc)
				a.store.Update(func(c *mc.Config) { c.Account = acc.ID })
				a.emit("account", map[string]any{"ok": acc.Name})
			}()
			_ = mc.OpenPath(dc.VerificationURI)
			return map[string]any{"code": dc.UserCode, "url": dc.VerificationURI}, nil
		},

		"msCancel": func(ctx context.Context, p json.RawMessage) (any, error) {
			a.msMu.Lock()
			if a.msStop != nil {
				a.msStop()
			}
			a.msMu.Unlock()
			return nil, nil
		},

		"selectAccount": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ ID string }](p)
			a.store.Update(func(c *mc.Config) { c.Account = q.ID })
			return nil, nil
		},

		"removeAccount": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ ID string }](p)
			a.accounts.Remove(q.ID)
			a.store.Update(func(c *mc.Config) {
				if c.Account == q.ID {
					c.Account = ""
				}
			})
			return nil, nil
		},

		// ---------------------------------------------------------------- java
		"javaList": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Refresh bool }](p)
			cfg := a.store.Get()
			return mc.DetectJava(cfg.GameDir, a.DataDir, q.Refresh), nil
		},

		// ---------------------------------------------------------------- modrinth
		"search": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct {
				Query, Kind, Game, Loader string
				Offset                    int
			}](p)
			if q.Kind == "" {
				q.Kind = "mod"
			}
			r, err := mc.Search(ctx, q.Query, q.Kind, q.Game, q.Loader, q.Offset)
			return r, err
		},

		"installProject": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Instance, Project, Title string }](p)
			if q.Instance == "" {
				return nil, errors.New("请先选择一个实例")
			}
			return nil, a.background("安装 "+firstNonEmpty(q.Title, "资源"), func(ctx context.Context) error {
				titles, err := mc.InstallProject(ctx, a.store.Get().GameDir, q.Instance, a.runDir(q.Instance), q.Project, a.progress)
				if err == nil {
					a.emit("installed", strings.Join(titles, "、"))
				}
				return err
			})
		},

		"installPack": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Project, Title string }](p)
			return nil, a.background("安装整合包 "+q.Title, func(ctx context.Context) error {
				cfg := a.store.Get()
				id, err := mc.InstallModpack(ctx, a.env(), q.Project, q.Title)
				if err != nil {
					return err
				}
				a.store.Update(func(c *mc.Config) { c.Selected = id })
				a.emit("stage", "下载游戏文件")
				if err := mc.Complete(ctx, cfg.GameDir, id, cfg.Threads, false, a.progress); err != nil {
					return err
				}
				a.emit("installed", id)
				return nil
			})
		},

		"content": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Instance, Kind string }](p)
			return mc.ListContent(a.runDir(q.Instance), q.Kind), nil
		},
		"toggleContent": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Instance, Kind, File string }](p)
			return nil, mc.ToggleContent(a.runDir(q.Instance), q.Kind, q.File)
		},
		"deleteContent": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Instance, Kind, File string }](p)
			return nil, mc.DeleteContent(a.runDir(q.Instance), q.Kind, q.File)
		},

		// ---------------------------------------------------------------- misc
		"open": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ What, ID string }](p)
			cfg := a.store.Get()
			var path string
			switch q.What {
			case "instance":
				path = a.runDir(q.ID)
			case "mods":
				path = filepath.Join(a.runDir(q.ID), "mods")
			case "gameDir":
				path = cfg.GameDir
			case "data":
				path = a.DataDir
			case "report":
				a.mu.Lock()
				if a.lastDx != nil {
					path = a.lastDx.Report
				}
				a.mu.Unlock()
			case "url":
				if strings.HasPrefix(q.ID, "https://") {
					path = q.ID
				}
			}
			if path == "" {
				return nil, errors.New("没有可打开的内容")
			}
			if !strings.HasPrefix(path, "https://") {
				_ = os.MkdirAll(path, 0o755)
			}
			return nil, mc.OpenPath(path)
		},

		"fix": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Action, Arg, Instance string }](p)
			return nil, a.fix(q.Action, q.Arg, q.Instance)
		},
	}
}

func firstNonEmpty(s ...string) string {
	for _, x := range s {
		if x != "" {
			return x
		}
	}
	return ""
}

// launch: refresh the account if needed, then run mc.Launch in the background.
func (a *App) launch(id, server string) error {
	cfg := a.store.Get()
	if id == "" {
		id = cfg.Selected
	}
	if id == "" {
		return errors.New("还没有实例，先去实例库新建一个吧")
	}
	acc, ok := a.accounts.Get(cfg.Account)
	if !ok {
		return errors.New("请先添加一个账户")
	}
	a.mu.Lock()
	if a.game != nil {
		a.mu.Unlock()
		return errors.New("游戏已经在运行了")
	}
	a.mu.Unlock()
	return a.background("启动 "+id, func(ctx context.Context) error {
		if acc.Type == "msa" && time.Now().Unix() > acc.Expires {
			a.emit("stage", "刷新微软登录")
			na, err := mc.MSRefresh(ctx, acc)
			if err != nil {
				return err
			}
			a.accounts.Put(na)
			acc = na
		}
		a.mu.Lock()
		a.lastDx = nil
		a.mu.Unlock()
		started := time.Now()
		var exit map[string]any
		var dx *mc.Diagnosis
		g, err := mc.Launch(ctx, mc.LaunchRequest{Config: a.store.Get(), DataDir: a.DataDir, ID: id, Account: acc, Server: server}, mc.Hooks{
			Stage:    func(s string) { a.emit("stage", s) },
			Progress: a.progress,
			Log:      a.logLine,
			Ready: func() {
				a.flushLogs()
				a.emit("ready", map[string]any{"ms": time.Since(started).Milliseconds()})
				if a.OnLaunched != nil {
					a.OnLaunched(a.store.Get().CloseOnLaunch)
				}
			},
			Exit: func(code int, d *mc.Diagnosis, played time.Duration) {
				exit = map[string]any{"code": code, "diagnosis": d, "played": int(played.Seconds()), "id": id}
				dx = d
			},
		})
		if err != nil {
			return err
		}
		a.mu.Lock()
		a.game = g
		a.mu.Unlock()
		go func() {
			<-g.Done()
			a.mu.Lock()
			a.lastLog = g.Logs()
			a.game = nil
			a.lastDx = dx
			a.mu.Unlock()
			a.flushLogs()
			a.emit("exit", exit)
		}()
		a.emit("started", map[string]any{"id": id, "ms": time.Since(started).Milliseconds()})
		return nil
	})
}

func (a *App) fix(action, arg, inst string) error {
	cfg := a.store.Get()
	if inst == "" {
		inst = cfg.Selected
	}
	switch action {
	case "java":
		major := 0
		fmt.Sscan(arg, &major)
		if major <= 0 {
			return errors.New("无效的 Java 版本")
		}
		return a.background(fmt.Sprintf("切换到 Java %d", major), func(ctx context.Context) error {
			mc.StampMeta(cfg.GameDir, inst, func(m *mc.InstanceMeta) { m.JavaMajor = major })
			a.store.Update(func(c *mc.Config) { c.JavaMode = "auto" })
			if mc.PickJava(mc.DetectJava(cfg.GameDir, a.DataDir, true), major) == nil {
				a.emit("stage", fmt.Sprintf("下载 Java %d", major))
				if _, err := mc.InstallRuntime(ctx, a.DataDir, mc.ComponentFor(major), cfg.Threads, a.progress); err != nil {
					return err
				}
			}
			a.mu.Lock()
			a.lastDx = nil
			a.mu.Unlock()
			a.emit("fixed", fmt.Sprintf("已为「%s」切换到 Java %d", inst, major))
			return nil
		})
	case "memory":
		next := a.store.Update(func(c *mc.Config) {
			if arg == "auto" {
				c.MemoryMB = 0
				return
			}
			cur := c.MemoryMB
			if cur == 0 {
				cur = mc.AutoMemory(true)
			}
			cur += 2048
			if lim := mc.TotalMemoryMB() - 1536; lim > 0 && cur > lim {
				cur = lim
			}
			c.MemoryMB = cur
		})
		msg := "内存已改为自动分配"
		if next.MemoryMB > 0 {
			msg = fmt.Sprintf("内存已调到 %d MB", next.MemoryMB)
		}
		a.mu.Lock()
		a.lastDx = nil
		a.mu.Unlock()
		a.emit("fixed", msg)
		return nil
	case "verify":
		return a.background("校验文件", func(ctx context.Context) error {
			if err := mc.Complete(ctx, cfg.GameDir, inst, cfg.Threads, true, a.progress); err != nil {
				return err
			}
			a.mu.Lock()
			a.lastDx = nil
			a.mu.Unlock()
			a.emit("fixed", "文件已校验并补全")
			return nil
		})
	case "dep":
		return a.background("安装前置 "+arg, func(ctx context.Context) error {
			titles, err := mc.InstallProject(ctx, cfg.GameDir, inst, a.runDir(inst), arg, a.progress)
			if err != nil {
				return err
			}
			a.mu.Lock()
			a.lastDx = nil
			a.mu.Unlock()
			a.emit("fixed", "已安装 "+strings.Join(titles, "、"))
			return nil
		})
	}
	return fmt.Errorf("未知操作 %s", action)
}

// upload imports a local .mrpack chosen in the UI.
func (a *App) upload(w http.ResponseWriter, r *http.Request) {
	if !a.authed(r) || r.Method != "POST" {
		http.Error(w, "forbidden", 403)
		return
	}
	cfg := a.store.Get()
	tmp := filepath.Join(cfg.GameDir, ".eririlily-tmp")
	_ = os.MkdirAll(tmp, 0o755)
	f, err := os.CreateTemp(tmp, "import-*.mrpack")
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	_, err = io.Copy(f, io.LimitReader(r.Body, 2<<30))
	f.Close()
	if err != nil {
		os.Remove(f.Name())
		http.Error(w, err.Error(), 500)
		return
	}
	name := strings.TrimSuffix(r.URL.Query().Get("name"), ".mrpack")
	err = a.background("导入整合包 "+name, func(ctx context.Context) error {
		defer os.Remove(f.Name())
		id, err := mc.InstallMrpack(ctx, a.env(), f.Name(), name)
		if err != nil {
			return err
		}
		a.store.Update(func(c *mc.Config) { c.Selected = id })
		if err := mc.Complete(ctx, cfg.GameDir, id, cfg.Threads, false, a.progress); err != nil {
			return err
		}
		a.emit("installed", id)
		return nil
	})
	w.Header().Set("Content-Type", "application/json")
	if err != nil {
		os.Remove(f.Name())
		w.WriteHeader(400)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}
	_ = json.NewEncoder(w).Encode(map[string]any{"result": nil})
}

func (a *App) env() mc.Env {
	return mc.Env{Cfg: a.store.Get(), DataDir: a.DataDir, Rep: a.progress, Stage: func(s string) { a.emit("stage", s) }}
}
