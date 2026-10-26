package mc

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"time"
)

const ManifestURL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

type ManifestVersion struct {
	ID          string `json:"id"`
	Type        string `json:"type"`
	URL         string `json:"url"`
	SHA1        string `json:"sha1"`
	ReleaseTime string `json:"releaseTime"`
}

type Manifest struct {
	Latest   struct{ Release, Snapshot string } `json:"latest"`
	Versions []ManifestVersion                  `json:"versions"`
}

var (
	manMu    sync.Mutex
	manCache *Manifest
	manTime  time.Time
)

func GetManifest(ctx context.Context) (*Manifest, error) {
	manMu.Lock()
	defer manMu.Unlock()
	if manCache != nil && time.Since(manTime) < 10*time.Minute {
		return manCache, nil
	}
	var m Manifest
	if err := GetJSON(ctx, ManifestURL, &m); err != nil {
		return nil, fmt.Errorf("获取版本列表失败：%w", err)
	}
	manCache, manTime = &m, time.Now()
	return &m, nil
}

// ---------------------------------------------------------------- version json

type Download struct {
	Path string `json:"path,omitempty"`
	SHA1 string `json:"sha1"`
	Size int64  `json:"size"`
	URL  string `json:"url"`
}

type OSRule struct {
	Name    string `json:"name,omitempty"`
	Arch    string `json:"arch,omitempty"`
	Version string `json:"version,omitempty"`
}

type Rule struct {
	Action   string          `json:"action"`
	OS       *OSRule         `json:"os,omitempty"`
	Features map[string]bool `json:"features,omitempty"`
}

type Library struct {
	Name      string `json:"name"`
	URL       string `json:"url,omitempty"` // maven base (Fabric style)
	SHA1      string `json:"sha1,omitempty"`
	Size      int64  `json:"size,omitempty"`
	Downloads *struct {
		Artifact    *Download            `json:"artifact,omitempty"`
		Classifiers map[string]*Download `json:"classifiers,omitempty"`
	} `json:"downloads,omitempty"`
	Natives map[string]string `json:"natives,omitempty"`
	Extract *struct {
		Exclude []string `json:"exclude"`
	} `json:"extract,omitempty"`
	Rules []Rule `json:"rules,omitempty"`
}

// Arg is a string or {rules, value}.
type Arg struct {
	Rules []Rule
	Value []string
}

func (a *Arg) UnmarshalJSON(b []byte) error {
	var s string
	if json.Unmarshal(b, &s) == nil {
		a.Value = []string{s}
		return nil
	}
	var o struct {
		Rules []Rule          `json:"rules"`
		Value json.RawMessage `json:"value"`
	}
	if err := json.Unmarshal(b, &o); err != nil {
		return err
	}
	a.Rules = o.Rules
	if json.Unmarshal(o.Value, &s) == nil {
		a.Value = []string{s}
		return nil
	}
	return json.Unmarshal(o.Value, &a.Value)
}

func (a Arg) MarshalJSON() ([]byte, error) {
	if len(a.Rules) == 0 && len(a.Value) == 1 {
		return json.Marshal(a.Value[0])
	}
	return json.Marshal(map[string]any{"rules": a.Rules, "value": a.Value})
}

type Version struct {
	ID                 string `json:"id"`
	InheritsFrom       string `json:"inheritsFrom,omitempty"`
	Type               string `json:"type,omitempty"`
	MainClass          string `json:"mainClass,omitempty"`
	MinecraftArguments string `json:"minecraftArguments,omitempty"`
	Arguments          *struct {
		Game []Arg `json:"game,omitempty"`
		JVM  []Arg `json:"jvm,omitempty"`
	} `json:"arguments,omitempty"`
	AssetIndex *struct {
		ID        string `json:"id"`
		SHA1      string `json:"sha1"`
		Size      int64  `json:"size"`
		TotalSize int64  `json:"totalSize"`
		URL       string `json:"url"`
	} `json:"assetIndex,omitempty"`
	Assets    string               `json:"assets,omitempty"`
	Downloads map[string]*Download `json:"downloads,omitempty"`
	Libraries []Library            `json:"libraries,omitempty"`
	Logging   *struct {
		Client *struct {
			Argument string `json:"argument"`
			File     struct {
				ID   string `json:"id"`
				SHA1 string `json:"sha1"`
				Size int64  `json:"size"`
				URL  string `json:"url"`
			} `json:"file"`
		} `json:"client"`
	} `json:"logging,omitempty"`
	JavaVersion *struct {
		Component    string `json:"component"`
		MajorVersion int    `json:"majorVersion"`
	} `json:"javaVersion,omitempty"`
	ReleaseTime string `json:"releaseTime,omitempty"`
	Jar         string `json:"jar,omitempty"`

	// EririLily extras (ignored by other launchers)
	EL *InstanceMeta `json:"eririlily,omitempty"`
}

type InstanceMeta struct {
	Name      string `json:"name,omitempty"`
	Game      string `json:"game,omitempty"`   // minecraft version
	Loader    string `json:"loader,omitempty"` // fabric | quilt | ""
	LoaderVer string `json:"loaderVersion,omitempty"`
	Icon      string `json:"icon,omitempty"`
	Created   int64  `json:"created,omitempty"`
	LastPlay  int64  `json:"lastPlayed,omitempty"`
	PlayTime  int64  `json:"playTime,omitempty"` // seconds
	Pack      string `json:"pack,omitempty"`     // modrinth project title
	Isolated  bool   `json:"isolated,omitempty"` // always use the version folder as game dir
	JavaMajor int    `json:"java,omitempty"`     // overrides the Java the version json asks for
}

// ---------------------------------------------------------------- rules

// Android runs the Linux build of the game.
var osName = map[string]string{"windows": "windows", "darwin": "osx", "linux": "linux", "android": "linux"}[runtime.GOOS]

func archName() string {
	switch runtime.GOARCH {
	case "386":
		return "x86"
	case "arm64":
		return "arm64"
	default:
		return "x86_64"
	}
}

func ruleAllows(rules []Rule, features map[string]bool) bool {
	if len(rules) == 0 {
		return true
	}
	allow := false
	for _, r := range rules {
		match := true
		if r.OS != nil {
			if r.OS.Name != "" && r.OS.Name != osName {
				match = false
			}
			if r.OS.Arch != "" && r.OS.Arch != archName() {
				match = false
			}
		}
		for k, v := range r.Features {
			if features[k] != v {
				match = false
			}
		}
		if match {
			allow = r.Action == "allow"
		}
	}
	return allow
}

// ---------------------------------------------------------------- maven

// MavenPath turns "group:artifact:version[:classifier][@ext]" into a relative path.
func MavenPath(name string) string {
	ext := "jar"
	if i := strings.Index(name, "@"); i >= 0 {
		ext = name[i+1:]
		name = name[:i]
	}
	p := strings.Split(name, ":")
	if len(p) < 3 {
		return name
	}
	file := p[1] + "-" + p[2]
	if len(p) >= 4 {
		file += "-" + p[3]
	}
	return strings.ReplaceAll(p[0], ".", "/") + "/" + p[1] + "/" + p[2] + "/" + file + "." + ext
}

// libKey identifies a library ignoring its version (to de-duplicate on merge).
func libKey(name string) string {
	p := strings.Split(strings.SplitN(name, "@", 2)[0], ":")
	if len(p) < 3 {
		return name
	}
	k := p[0] + ":" + p[1]
	if len(p) >= 4 {
		k += ":" + p[3]
	}
	return k
}

// ---------------------------------------------------------------- local versions

func VersionDir(gameDir, id string) string { return filepath.Join(gameDir, "versions", id) }
func VersionJSON(gameDir, id string) string {
	return filepath.Join(VersionDir(gameDir, id), id+".json")
}

func LoadVersion(gameDir, id string) (*Version, error) {
	var v Version
	if err := readJSON(VersionJSON(gameDir, id), &v); err != nil {
		return nil, err
	}
	if v.ID == "" {
		v.ID = id
	}
	return &v, nil
}

func SaveVersion(gameDir string, v *Version) error {
	defer ForgetInfo(gameDir, v.ID)
	return writeJSON(VersionJSON(gameDir, v.ID), v)
}

// Resolve loads a version and merges its inheritsFrom chain.
func Resolve(gameDir, id string) (*Version, error) {
	v, err := LoadVersion(gameDir, id)
	if err != nil {
		return nil, fmt.Errorf("读取版本 %s 失败：%w", id, err)
	}
	root := id
	for depth := 0; v.InheritsFrom != "" && depth < 8; depth++ {
		root = v.InheritsFrom
		parent, err := LoadVersion(gameDir, v.InheritsFrom)
		if err != nil {
			return nil, fmt.Errorf("缺少前置版本 %s", v.InheritsFrom)
		}
		v = merge(v, parent)
	}
	if v.Jar == "" {
		v.Jar = root
	}
	return v, nil
}

func merge(child, parent *Version) *Version {
	out := *parent
	out.ID = child.ID
	out.InheritsFrom = parent.InheritsFrom
	out.EL = child.EL
	if child.Type != "" {
		out.Type = child.Type
	}
	if child.MainClass != "" {
		out.MainClass = child.MainClass
	}
	if child.MinecraftArguments != "" {
		out.MinecraftArguments = child.MinecraftArguments
	}
	if child.Arguments != nil {
		a := &struct {
			Game []Arg `json:"game,omitempty"`
			JVM  []Arg `json:"jvm,omitempty"`
		}{}
		if parent.Arguments != nil {
			a.Game = append(a.Game, parent.Arguments.Game...)
			a.JVM = append(a.JVM, parent.Arguments.JVM...)
		}
		a.Game = append(a.Game, child.Arguments.Game...)
		a.JVM = append(a.JVM, child.Arguments.JVM...)
		out.Arguments = a
	}
	if child.AssetIndex != nil {
		out.AssetIndex = child.AssetIndex
	}
	if child.JavaVersion != nil {
		out.JavaVersion = child.JavaVersion
	}
	// child libraries win over parent ones with the same group:artifact
	seen := map[string]bool{}
	var libs []Library
	for _, l := range child.Libraries {
		seen[libKey(l.Name)] = true
		libs = append(libs, l)
	}
	for _, l := range parent.Libraries {
		if !seen[libKey(l.Name)] {
			libs = append(libs, l)
		}
	}
	out.Libraries = libs
	if child.Jar != "" {
		out.Jar = child.Jar
	}
	return &out
}

var reGameVer = regexp.MustCompile(`(?:^|[^\d.])(1\.\d{1,2}(?:\.\d{1,2})?|2\d\.\d{1,2}(?:\.\d{1,2})?|\d{2}w\d{2}[a-z])(?:[^\d.]|$)`)

// GameVersion returns the Minecraft version of an instance. Instances made by
// other launchers are handled too: HMCL/PCL merge loaders into one json
// (no inheritsFrom) and keep the game version in clientVersion / patches.
func GameVersion(gameDir, id string) string {
	cur := id
	for i := 0; i < 8; i++ {
		v, err := LoadVersion(gameDir, cur)
		if err != nil {
			break
		}
		if v.EL != nil && v.EL.Game != "" {
			return v.EL.Game
		}
		if v.InheritsFrom == "" {
			if g := foreignGameVersion(gameDir, cur); g != "" {
				return g
			}
			break
		}
		cur = v.InheritsFrom
	}
	if m := reGameVer.FindStringSubmatch(" " + cur + " "); m != nil {
		return m[1]
	}
	return cur
}

func foreignGameVersion(gameDir, id string) string {
	var raw struct {
		ClientVersion string `json:"clientVersion"`
		Patches       []struct {
			ID      string `json:"id"`
			Version string `json:"version"`
		} `json:"patches"`
		Downloads map[string]*Download `json:"downloads"`
		ID        string               `json:"id"`
	}
	if readJSON(VersionJSON(gameDir, id), &raw) != nil {
		return ""
	}
	if raw.ClientVersion != "" {
		return raw.ClientVersion
	}
	for _, p := range raw.Patches {
		if p.ID == "game" && p.Version != "" {
			return p.Version
		}
	}
	if m := reGameVer.FindStringSubmatch(" " + id + " "); m != nil {
		return m[1]
	}
	return ""
}

// DetectLoader works out the mod loader from the merged version json.
func DetectLoader(v *Version) string {
	if v.EL != nil && v.EL.Loader != "" {
		return v.EL.Loader
	}
	mc := strings.ToLower(v.MainClass)
	libs := ""
	for _, l := range v.Libraries {
		libs += l.Name + " "
	}
	switch {
	case strings.Contains(mc, "quilt") || strings.Contains(libs, "org.quiltmc:quilt-loader"):
		return "quilt"
	case strings.Contains(mc, "fabricmc") || strings.Contains(libs, "net.fabricmc:fabric-loader"):
		return "fabric"
	case strings.Contains(mc, "neoforged") || strings.Contains(libs, "net.neoforged:"):
		return "neoforge"
	case strings.Contains(mc, "minecraftforge") || strings.Contains(mc, "bootstraplauncher") || strings.Contains(mc, "launchwrapper") ||
		strings.Contains(libs, "net.minecraftforge:"):
		return "forge"
	}
	return ""
}

// InstanceLoader resolves the instance (with its parents) and detects its loader.
func InstanceLoader(gameDir, id string) string {
	v, err := Resolve(gameDir, id)
	if err != nil {
		return ""
	}
	return DetectLoader(v)
}

// ListInstances returns installed version ids that have a json.
func ListInstances(gameDir string) []*Version {
	ents, _ := os.ReadDir(filepath.Join(gameDir, "versions"))
	var out []*Version
	for _, e := range ents {
		if !e.IsDir() {
			continue
		}
		v, err := LoadVersion(gameDir, e.Name())
		if err != nil {
			continue
		}
		out = append(out, v)
	}
	return out
}
