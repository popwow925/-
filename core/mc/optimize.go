package mc

import (
	"context"
	"os"
	"path/filepath"
	"strings"
)

// ApplyGPUPreference marks every Java the launcher knows about for the dedicated GPU.
func ApplyGPUPreference(gameDir, dataDir string, high bool) int {
	n := 0
	for _, j := range DetectJava(gameDir, dataDir, false) {
		for _, exe := range []string{j.Path, strings.Replace(j.Path, "javaw.exe", "java.exe", 1)} {
			if SetGPUPreference(exe, high) == nil {
				n++
			}
		}
	}
	return n
}

// chineseByDefault writes lang:zh_cn into a fresh instance's options.txt.
func chineseByDefault(runDir, game string) {
	p := filepath.Join(runDir, "options.txt")
	if _, err := os.Stat(p); err == nil {
		return
	}
	lang := "zh_cn"
	if old := strings.Split(game, "."); len(old) >= 2 && old[0] == "1" && len(old[1]) <= 2 && atoi(old[1]) < 11 {
		lang = "zh_CN" // 1.10 and older use the mixed-case code
	}
	_ = os.MkdirAll(runDir, 0o755)
	_ = os.WriteFile(p, []byte("lang:"+lang+"\n"), 0o644)
}

// Performance mods tried in order; each is installed only if Modrinth has a
// build for the instance's game version and loader.
type perfMod struct{ slug, alt, title string }

var perfMods = []perfMod{
	{"sodium", "embeddium", "Sodium / Embeddium（渲染）"},
	{"lithium", "", "Lithium（游戏逻辑）"},
	{"ferrite-core", "", "FerriteCore（内存）"},
	{"modernfix", "", "ModernFix（启动与内存）"},
	{"immediatelyfast", "", "ImmediatelyFast（界面渲染）"},
	{"entityculling", "", "EntityCulling（实体剔除）"},
	{"dynamic-fps", "", "Dynamic FPS（后台降帧）"},
}

func hasModLike(runDir, slug string) bool {
	norm := func(s string) string {
		return strings.NewReplacer("-", "", "_", "", " ", "").Replace(strings.ToLower(s))
	}
	want := norm(slug)
	for _, f := range ListContent(runDir, "mod") {
		if strings.Contains(norm(f.File), want) {
			return true
		}
	}
	return false
}

// Phones: no Sodium by default (renderer support varies), plus mods that cut
// memory and background work.
var androidPerfMods = []perfMod{
	{"lithium", "", "Lithium（游戏逻辑）"},
	{"ferrite-core", "", "FerriteCore（内存）"},
	{"modernfix", "", "ModernFix（启动与内存）"},
	{"entityculling", "", "EntityCulling（实体剔除）"},
	{"dynamic-fps", "", "Dynamic FPS（后台降帧）"},
	{"memoryleakfix", "", "MemoryLeakFix（内存泄漏修复）"},
	{"krypton", "", "Krypton（网络）"},
}

var androidRenderMods = []perfMod{
	{"sodium", "embeddium", "Sodium / Embeddium（渲染）"},
	{"moreculling", "", "MoreCulling（更多剔除）"},
}

// InstallPerfMods installs the performance mods that fit the instance and returns what was added.
func InstallPerfMods(ctx context.Context, gameDir, id, runDir string, rep Reporter) ([]string, error) {
	if Android {
		return installMods(ctx, gameDir, id, runDir, androidPerfMods, rep)
	}
	return installMods(ctx, gameDir, id, runDir, perfMods, rep)
}

// InstallRenderMods adds Sodium-class rendering mods (Android "extreme" optimisation).
func InstallRenderMods(ctx context.Context, gameDir, id, runDir string, rep Reporter) ([]string, error) {
	return installMods(ctx, gameDir, id, runDir, androidRenderMods, rep)
}

func installMods(ctx context.Context, gameDir, id, runDir string, list []perfMod, rep Reporter) ([]string, error) {
	v, err := LoadVersion(gameDir, id)
	if err != nil {
		return nil, err
	}
	_ = v
	loader := InstanceLoader(gameDir, id)
	if loader == "" {
		return nil, nil
	}
	game := GameVersion(gameDir, id)
	var added []string
	for _, m := range list {
		if ctx.Err() != nil {
			return added, ctx.Err()
		}
		pick := ""
		for _, slug := range []string{m.slug, m.alt} {
			if slug == "" {
				continue
			}
			if hasModLike(runDir, slug) {
				pick = "-"
				break
			}
			ld := loader
			vs, err := ProjectVersions(ctx, slug, game, ld)
			if err == nil && len(vs) == 0 && loader == "quilt" {
				vs, err = ProjectVersions(ctx, slug, game, "fabric")
			}
			if err == nil && len(vs) > 0 {
				pick = slug
				break
			}
		}
		if pick == "" || pick == "-" {
			continue
		}
		if t, err := InstallProject(ctx, gameDir, id, runDir, pick, rep); err == nil {
			added = append(added, t...)
		}
	}
	return added, nil
}
