package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"eririlily/mc"
)

// v0.7 features: identified content (names + icons), update checks and the
// multiplayer server list.

var (
	updMu   sync.Mutex
	updLast = map[string][]mc.ContentUpdate{} // instance|kind -> last check (never trust URLs from the page)
)

func validKind(k string) bool { return k == "mod" || k == "resourcepack" || k == "shader" }

func (a *App) featureMethods() map[string]handler {
	return map[string]handler{
		// content list with Modrinth names and icons (falls back to the plain list offline)
		"contentInfo": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Instance, Kind string }](p)
			if !validKind(q.Kind) {
				return nil, errors.New("未知类型")
			}
			cctx, cancel := context.WithTimeout(ctx, 12*time.Second)
			defer cancel()
			items, err := mc.Identify(cctx, a.runDir(q.Instance), q.Kind)
			return map[string]any{"items": items, "offline": err != nil}, nil
		},
		"checkUpdates": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Instance, Kind string }](p)
			if !validKind(q.Kind) {
				return nil, errors.New("未知类型")
			}
			in, err := mc.Info(a.store.Get().GameDir, q.Instance)
			if err != nil {
				return nil, errors.New("找不到实例")
			}
			ups, err := mc.CheckUpdates(ctx, a.runDir(q.Instance), q.Kind, in.Game, in.Loader)
			if err != nil {
				return nil, err
			}
			updMu.Lock()
			updLast[q.Instance+"|"+q.Kind] = ups
			updMu.Unlock()
			if ups == nil {
				ups = []mc.ContentUpdate{}
			}
			return ups, nil
		},
		"applyUpdates": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct {
				Instance, Kind string
				Files          []string // empty = all
			}](p)
			if a.gameRunning(q.Instance) {
				return nil, errors.New("游戏正在运行，先退出游戏再更新")
			}
			updMu.Lock()
			all := updLast[q.Instance+"|"+q.Kind]
			updMu.Unlock()
			pick := all
			if len(q.Files) > 0 {
				want := map[string]bool{}
				for _, f := range q.Files {
					want[f] = true
				}
				pick = nil
				for _, u := range all {
					if want[u.File] {
						pick = append(pick, u)
					}
				}
			}
			if len(pick) == 0 {
				return nil, errors.New("没有可更新的内容，先检查一次更新")
			}
			return nil, a.background(fmt.Sprintf("更新 %d 项", len(pick)), func(ctx context.Context) error {
				n, err := mc.ApplyUpdates(ctx, a.runDir(q.Instance), q.Kind, pick, a.store.Get().Threads, a.progress)
				updMu.Lock()
				delete(updLast, q.Instance+"|"+q.Kind)
				updMu.Unlock()
				if n > 0 {
					a.emit("updated", map[string]any{"instance": q.Instance, "kind": q.Kind, "n": n})
				}
				return err
			})
		},

		// ------------------------------------------------ multiplayer servers
		"servers": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Instance string }](p)
			list, err := mc.ListServers(a.runDir(q.Instance))
			if err != nil {
				return nil, fmt.Errorf("读取服务器列表失败：%w", err)
			}
			if list == nil {
				list = []mc.Server{}
			}
			return list, nil
		},
		"pingServer": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Addr string }](p)
			if !mc.ValidServer(q.Addr) {
				return nil, errors.New("服务器地址不正确")
			}
			return mc.PingServer(ctx, q.Addr), nil
		},
		"addServer": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Instance, Name, IP string }](p)
			if a.gameRunning(q.Instance) {
				return nil, errors.New("游戏正在运行时修改会被游戏覆盖，先退出游戏")
			}
			return nil, mc.AddServer(a.runDir(q.Instance), q.Name, q.IP)
		},
		"removeServer": func(ctx context.Context, p json.RawMessage) (any, error) {
			q := bind[struct{ Instance, IP string }](p)
			if a.gameRunning(q.Instance) {
				return nil, errors.New("游戏正在运行时修改会被游戏覆盖，先退出游戏")
			}
			return nil, mc.RemoveServer(a.runDir(q.Instance), q.IP)
		},
	}
}
