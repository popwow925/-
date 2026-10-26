package mc

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"time"
)

type LoaderVersion struct {
	Version string `json:"version"`
	Stable  bool   `json:"stable"`
}

func loaderMeta(loader string) (string, error) {
	switch loader {
	case "fabric":
		return "https://meta.fabricmc.net/v2", nil
	case "quilt":
		return "https://meta.quiltmc.org/v3", nil
	}
	return "", fmt.Errorf("暂不支持 %s 加载器", loader)
}

// LoaderVersions lists loader builds available for a Minecraft version.
func LoaderVersions(ctx context.Context, loader, game string) ([]LoaderVersion, error) {
	switch loader {
	case "forge":
		return forgeVersions(ctx, game)
	case "neoforge":
		return neoVersions(ctx, game)
	}
	base, err := loaderMeta(loader)
	if err != nil {
		return nil, err
	}
	var raw []struct {
		Loader struct {
			Version string `json:"version"`
			Stable  *bool  `json:"stable"`
		} `json:"loader"`
	}
	if err := GetJSON(ctx, base+"/versions/loader/"+url.PathEscape(game), &raw); err != nil {
		return nil, err
	}
	var out []LoaderVersion
	for i, r := range raw {
		st := i == 0
		if r.Loader.Stable != nil {
			st = *r.Loader.Stable
		}
		out = append(out, LoaderVersion{Version: r.Loader.Version, Stable: st})
	}
	return out, nil
}

// InstallLoader installs vanilla `game` plus a Fabric/Quilt profile named `name`.
func InstallLoader(ctx context.Context, gameDir, game, loader, loaderVer, name string) (string, error) {
	if err := InstallVanilla(ctx, gameDir, game); err != nil {
		return "", err
	}
	base, err := loaderMeta(loader)
	if err != nil {
		return "", err
	}
	if loaderVer == "" {
		vs, err := LoaderVersions(ctx, loader, game)
		if err != nil || len(vs) == 0 {
			return "", fmt.Errorf("%s 不支持 Minecraft %s", loader, game)
		}
		loaderVer = vs[0].Version
		for _, v := range vs {
			if v.Stable {
				loaderVer = v.Version
				break
			}
		}
	}
	b, err := GetBytes(ctx, base+"/versions/loader/"+url.PathEscape(game)+"/"+url.PathEscape(loaderVer)+"/profile/json")
	if err != nil {
		return "", fmt.Errorf("获取 %s 配置失败：%w", loader, err)
	}
	var v Version
	if err := json.Unmarshal(b, &v); err != nil {
		return "", err
	}
	if name == "" {
		name = v.ID
	}
	v.ID = name
	v.InheritsFrom = game
	v.EL = &InstanceMeta{Name: name, Game: game, Loader: loader, LoaderVer: loaderVer, Created: time.Now().Unix()}
	if err := os.MkdirAll(VersionDir(gameDir, name), 0o755); err != nil {
		return "", err
	}
	return name, SaveVersion(gameDir, &v)
}

// CreateInstance installs a new instance: vanilla (loader == "") or with a loader.
// For vanilla with a custom name, a thin child version is created so several
// isolated instances of the same game version can coexist.
func CreateInstance(ctx context.Context, env Env, game, loader, loaderVer, name string) (string, error) {
	gameDir := env.Cfg.GameDir
	if !validName(name) {
		return "", fmt.Errorf("实例名不能包含 \\ / : * ? \" < > |")
	}
	if name != "" {
		if _, err := os.Stat(VersionDir(gameDir, name)); err == nil {
			return "", fmt.Errorf("已存在名为「%s」的实例", name)
		}
	}
	if loader == "forge" || loader == "neoforge" {
		return InstallForge(ctx, env, game, loader, loaderVer, name)
	}
	if loader != "" {
		return InstallLoader(ctx, gameDir, game, loader, loaderVer, name)
	}
	if err := InstallVanilla(ctx, gameDir, game); err != nil {
		return "", err
	}
	if name == "" || name == game {
		stampMeta(gameDir, game, func(m *InstanceMeta) {
			if m.Name == "" {
				m.Name = game
			}
			m.Game = game
		})
		return game, nil
	}
	v := &Version{ID: name, InheritsFrom: game, Type: "release",
		EL: &InstanceMeta{Name: name, Game: game, Created: time.Now().Unix()}}
	return name, SaveVersion(gameDir, v)
}

func validName(s string) bool {
	for _, r := range s {
		switch r {
		case '\\', '/', ':', '*', '?', '"', '<', '>', '|':
			return false
		}
		if r < 32 {
			return false
		}
	}
	return s != "." && s != ".."
}
