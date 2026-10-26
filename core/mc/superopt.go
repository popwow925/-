package mc

import (
	"fmt"
	"math"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

// One-tap "super optimisation" for phones: picks render scale, frame cap and
// game options from what the device can do.

// Tier rates the device: 3 flagship, 2 mid-range, 1 entry.
func (d Device) Tier() int {
	score := 0
	switch {
	case d.RAMMB >= 11000:
		score += 3
	case d.RAMMB >= 7500:
		score += 2
	case d.RAMMB >= 5500:
		score += 1
	}
	switch {
	case d.MaxFreqMHz >= 3000:
		score += 3
	case d.MaxFreqMHz >= 2600:
		score += 2
	case d.MaxFreqMHz >= 2200:
		score += 1
	}
	score += gpuScore(d.GLRenderer)
	switch {
	case score >= 8:
		return 3
	case score >= 4:
		return 2
	}
	return 1
}

var reNum = regexp.MustCompile(`\d+`)

// gpuScore: 0..3 from the GL_RENDERER string.
func gpuScore(r string) int {
	l := strings.ToLower(r)
	n := 0
	if m := reNum.FindString(l); m != "" {
		n, _ = strconv.Atoi(m)
	}
	switch {
	case strings.Contains(l, "adreno"):
		switch {
		case n >= 730 || (n >= 800 && n < 1000):
			return 3
		case n >= 640:
			return 2
		case n >= 610:
			return 1
		}
	case strings.Contains(l, "immortalis"):
		return 3
	case strings.Contains(l, "mali"):
		switch {
		case strings.Contains(l, "g7") && n >= 710, strings.Contains(l, "g9"):
			return 3
		case strings.Contains(l, "g7"), strings.Contains(l, "g6") && n >= 610:
			return 2
		case strings.Contains(l, "g5"):
			return 1
		}
	case strings.Contains(l, "xclipse"), strings.Contains(l, "maleoon"):
		return 2
	}
	return 0
}

// OptimizeLevel: balanced | extreme | quality.
type OptPlan struct {
	Tier        int               `json:"tier"`
	TierName    string            `json:"tierName"`
	RenderScale int               `json:"renderScale"`
	FpsLimit    int               `json:"fpsLimit"`
	Options     map[string]string `json:"options"`
	Changes     []string          `json:"changes"`
	RenderMods  bool              `json:"renderMods"`
}

// PlanOptimize computes the settings for the device and level.
func PlanOptimize(d Device, level, game string) OptPlan {
	t := d.Tier()
	p := OptPlan{Tier: t, TierName: map[int]string{1: "入门机", 2: "中端机", 3: "旗舰机"}[t]}
	short := min(d.ScreenW, d.ScreenH)
	if short <= 0 {
		short = 1080
	}
	// target the short side of the render resolution
	target := map[int]int{1: 540, 2: 720, 3: 1080}[t]
	switch level {
	case "extreme":
		target = target * 3 / 4
	case "quality":
		target = min(short, target*4/3)
	}
	p.RenderScale = int(math.Round(float64(target)*100/float64(short)/5) * 5)
	p.RenderScale = max(25, min(100, p.RenderScale))

	refresh := int(math.Round(d.Refresh))
	if refresh <= 0 {
		refresh = 60
	}
	switch {
	case level == "quality":
		p.FpsLimit = min(refresh, 120)
	case t == 3:
		p.FpsLimit = min(refresh, 120)
	case t == 2:
		p.FpsLimit = min(refresh, 90)
	default:
		p.FpsLimit = 60
	}
	if level == "extreme" && t < 3 {
		p.FpsLimit = 60 // steady 60 beats a throttling 90
	}

	rd := map[int]int{1: 6, 2: 8, 3: 12}[t]
	sd := map[int]int{1: 5, 2: 6, 3: 8}[t]
	switch level {
	case "extreme":
		rd, sd = max(4, rd-2), max(5, sd-1)
	case "quality":
		rd, sd = rd+4, sd+2
	}
	o := map[string]string{
		"renderDistance":         strconv.Itoa(rd),
		"simulationDistance":     strconv.Itoa(sd),
		"maxFps":                 strconv.Itoa(p.FpsLimit),
		"enableVsync":            "false",
		"graphicsMode":           "0",
		"fancyGraphics":          "false",
		"renderClouds":           "\"false\"",
		"entityShadows":          "false",
		"biomeBlendRadius":       "0",
		"mipmapLevels":           "0",
		"particles":              "1",
		"entityDistanceScaling":  "0.75",
		"prioritizeChunkUpdates": "0",
		"showAutosaveIndicator":  "false",
		"bobView":                "false",
		"touchscreen":            "false",
	}
	if t >= 2 && level != "extreme" {
		o["mipmapLevels"] = "2"
		o["biomeBlendRadius"] = "1"
	}
	if level == "quality" {
		o["graphicsMode"], o["fancyGraphics"], o["renderClouds"], o["entityShadows"], o["particles"] = "1", "true", "\"fast\"", "true", "0"
		o["entityDistanceScaling"] = "1.0"
	}
	if t == 1 || level == "extreme" {
		o["particles"] = "2"
		o["ao"] = "false"
	}
	p.Options = o
	p.RenderMods = level == "extreme" && t >= 2 && (game == "" || gameCmp(game, "1.17") >= 0)
	p.Changes = []string{
		fmt.Sprintf("识别为%s（%s）", p.TierName, strings.TrimSpace(d.Brand+" "+d.Model)),
		fmt.Sprintf("渲染倍率 %d%%（约 %dp）", p.RenderScale, short*p.RenderScale/100),
		fmt.Sprintf("帧率上限 %d", p.FpsLimit),
		fmt.Sprintf("视距 %d · 模拟距离 %d", rd, sd),
		"图像：流畅 · 关闭云/实体阴影 · 精简粒子",
		"持续性能模式 · 渲染线程绑定大核 · ADPF 性能提示",
		"内存按机型自动分配",
	}
	return p
}

// ApplyOptions merges keys into an instance's options.txt.
func ApplyOptions(runDir string, kv map[string]string) error {
	p := filepath.Join(runDir, "options.txt")
	b, _ := os.ReadFile(p)
	lines := strings.Split(strings.ReplaceAll(string(b), "\r\n", "\n"), "\n")
	seen := map[string]bool{}
	for i, l := range lines {
		k, _, ok := strings.Cut(l, ":")
		if !ok {
			continue
		}
		if v, ok := kv[k]; ok {
			lines[i] = k + ":" + v
			seen[k] = true
		}
	}
	var out []string
	for _, l := range lines {
		if strings.TrimSpace(l) != "" {
			out = append(out, l)
		}
	}
	for k, v := range kv {
		if !seen[k] {
			out = append(out, k+":"+v)
		}
	}
	_ = os.MkdirAll(runDir, 0o755)
	return os.WriteFile(p, []byte(strings.Join(out, "\n")+"\n"), 0o644)
}

// ReadOptions returns the options.txt keys asked for.
func ReadOptions(runDir string, keys []string) map[string]string {
	out := map[string]string{}
	b, err := os.ReadFile(filepath.Join(runDir, "options.txt"))
	if err != nil {
		return out
	}
	want := map[string]bool{}
	for _, k := range keys {
		want[k] = true
	}
	for _, l := range strings.Split(string(b), "\n") {
		if k, v, ok := strings.Cut(strings.TrimSpace(l), ":"); ok && (len(keys) == 0 || want[k]) {
			out[k] = v
		}
	}
	return out
}
