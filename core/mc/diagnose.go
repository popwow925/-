package mc

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Diagnosis is a plain-language explanation of why the game stopped.
type Diagnosis struct {
	Title    string `json:"title"`
	Detail   string `json:"detail"`
	Fix      string `json:"fix,omitempty"`      // action id the UI can run
	FixArg   string `json:"fixArg,omitempty"`   // argument for the action
	FixLabel string `json:"fixLabel,omitempty"` // button text
	Evidence string `json:"evidence,omitempty"` // the log line that matched
	Report   string `json:"report,omitempty"`   // crash report path, if any
}

type rule struct {
	re *regexp.Regexp
	f  func(m []string) Diagnosis
}

func r(expr string, f func(m []string) Diagnosis) rule {
	return rule{regexp.MustCompile(expr), f}
}

// class file major version -> Java release
func classToJava(cls int) int { return cls - 44 }

var rules = []rule{
	r(`class file version (\d+)\.\d+\)?,? this version of the Java Runtime only recognizes class file versions up to (\d+)`, func(m []string) Diagnosis {
		need, _ := strconv.Atoi(m[1])
		return Diagnosis{Title: "Java 版本太旧",
			Detail: fmt.Sprintf("这个版本或其中的模组需要 Java %d，而当前用的是 Java %d。", classToJava(need), classToJava(atoi(m[2]))),
			Fix:    "java", FixArg: itoa(classToJava(need)), FixLabel: fmt.Sprintf("一键切换到 Java %d", classToJava(need))}
	}),
	r(`UnsupportedClassVersionError.*class file version (\d+)\.\d+`, func(m []string) Diagnosis {
		need, _ := strconv.Atoi(m[1])
		return Diagnosis{Title: "Java 版本太旧",
			Detail: fmt.Sprintf("有内容需要 Java %d 才能运行。", classToJava(need)),
			Fix:    "java", FixArg: itoa(classToJava(need)), FixLabel: fmt.Sprintf("一键切换到 Java %d", classToJava(need))}
	}),
	r(`Could not reserve enough space for (?:object heap|\d+KB object heap)|Invalid maximum heap size|Initial heap size set to a larger value`, func(m []string) Diagnosis {
		return Diagnosis{Title: "内存设置过大", Detail: "分配给游戏的内存超过了系统能提供的上限，Java 无法启动。",
			Fix: "memory", FixArg: "auto", FixLabel: "改为自动分配内存"}
	}),
	r(`java\.lang\.OutOfMemoryError|Out of memory|GC overhead limit exceeded`, func(m []string) Diagnosis {
		return Diagnosis{Title: "游戏内存不足", Detail: "游戏运行时用光了分配的内存。模组较多时通常需要 6–8 GB。",
			Fix: "memory", FixArg: "more", FixLabel: "增加内存后重试"}
	}),
	r(`(?:Pixel format not accelerated|GLFW error 65542|WGL: The driver does not appear to support OpenGL|No OpenGL context|GLFW error 65543)`, func(m []string) Diagnosis {
		return Diagnosis{Title: "显卡驱动不支持 OpenGL", Detail: "显卡驱动太旧或没装好，游戏无法创建画面。请到显卡厂商官网（NVIDIA / AMD / Intel）更新驱动；笔记本请确认使用独立显卡。"}
	}),
	r(`(?:InaccessibleObjectException|Unable to make (?:field|method|protected final).*accessible|ClassCastException.*jdk\.internal\.loader|sun\.misc\.Unsafe.*(?:not found|NoSuchMethod))`, func(m []string) Diagnosis {
		return Diagnosis{Title: "Java 版本太新", Detail: "老版本的游戏或加载器不兼容当前的新版 Java，换回 Java 8 一般就能解决。",
			Fix: "java", FixArg: "8", FixLabel: "一键切换到 Java 8"}
	}),
	r(`(?:DuplicateModsFoundException|Found duplicate mods|Duplicate mods found|Mod ID '?([a-z0-9_\-]+)'? (?:is )?(?:provided|present) by multiple)`, func(m []string) Diagnosis {
		d := Diagnosis{Title: "模组重复", Detail: "同一个模组装了两份（可能版本不同），请在模组页删掉多余的那个。", Fix: "mods", FixLabel: "打开模组管理"}
		if len(m) > 1 && m[1] != "" {
			d.Detail = fmt.Sprintf("模组「%s」装了不止一份，请删掉多余的那个。", m[1])
		}
		return d
	}),
	r(`requires (?:version |any version )?([^ ]+) of (?:mod )?'?minecraft'?`, func(m []string) Diagnosis {
		return Diagnosis{Title: "模组与游戏版本不匹配", Detail: fmt.Sprintf("有模组只支持 Minecraft %s，请换成对应版本的模组。", m[1]), Fix: "mods", FixLabel: "打开模组管理"}
	}),
	r(`(?m)Mod '([^']+)' \(([^)]+)\) [^ ]+ requires (?:version |any version )?(.*?) ?of (?:mod )?'?([^',!()]+?)'?(?: \(([^)]+)\))?(?:,| which|!|$)`, func(m []string) Diagnosis {
		dep := m[5]
		if dep == "" {
			dep = strings.TrimSpace(m[4])
		}
		ver := strings.TrimSpace(m[3])
		if ver != "" {
			ver = " " + ver
		}
		return Diagnosis{Title: "缺少前置模组", Detail: fmt.Sprintf("「%s」需要「%s」%s，但没有安装或版本不对。", m[1], strings.TrimSpace(m[4]), ver),
			Fix: "dep", FixArg: dep, FixLabel: "一键安装前置"}
	}),
	r(`Mod '([^']+)' \(([^)]+)\) [^ ]+ (?:is incompatible with|breaks) (.*)`, func(m []string) Diagnosis {
		return Diagnosis{Title: "模组冲突", Detail: fmt.Sprintf("「%s」与 %s 不兼容，请移除其中一个。", m[1], strings.TrimSpace(m[3])), Fix: "mods", FixLabel: "打开模组管理"}
	}),
	r(`(?:Mixin apply(?:ing)? failed|MixinApplyError|MixinTransformerError).*?(?:from mod |mod[ :]+)\(?([a-z0-9_\-]+)`, func(m []string) Diagnosis {
		return Diagnosis{Title: "模组注入失败", Detail: fmt.Sprintf("模组「%s」与当前游戏或其他模组冲突，试着更新或移除它。", m[1]), Fix: "mods", FixLabel: "打开模组管理"}
	}),
	r(`(?:ZipException|invalid LOC header|error in opening zip file|zip END header not found|Invalid or corrupt jarfile|Error: Could not find or load main class)`, func(m []string) Diagnosis {
		return Diagnosis{Title: "游戏文件损坏", Detail: "有文件下载不完整或已损坏。补全文件会逐个校验并重新下载坏掉的部分。",
			Fix: "verify", FixLabel: "校验并补全文件"}
	}),
	r(`(?:UnsatisfiedLinkError|Failed to locate library: (\S+)|lwjgl.*\.dll.*(?:Can't find|not found))`, func(m []string) Diagnosis {
		return Diagnosis{Title: "本地库加载失败", Detail: "LWJGL 等本地库没有正确解压，或被杀毒软件拦截了。",
			Fix: "verify", FixLabel: "重新解压并补全"}
	}),
	r(`(?:NoClassDefFoundError|ClassNotFoundException): (net/minecraftforge|net/neoforged|net/fabricmc|org/quiltmc)`, func(m []string) Diagnosis {
		return Diagnosis{Title: "模组和加载器不匹配", Detail: "装了别的加载器用的模组（比如 Forge 模组放进了 Fabric 实例）。", Fix: "mods", FixLabel: "打开模组管理"}
	}),
	r(`Missing or unsupported mandatory dependencies:\s*(?:\n\s*)?Mod ID: '([^']+)', Requested by: '([^']+)'`, func(m []string) Diagnosis {
		return Diagnosis{Title: "缺少前置模组", Detail: fmt.Sprintf("「%s」需要前置「%s」。", m[2], m[1]), Fix: "dep", FixArg: m[1], FixLabel: "一键安装前置"}
	}),
	r(`(?:Address already in use|BindException)`, func(m []string) Diagnosis {
		return Diagnosis{Title: "端口被占用", Detail: "有别的程序（或另一个游戏实例）占用了端口，关掉它再试。"}
	}),
	r(`(?:Exception in thread "main" java\.lang\.IllegalArgumentException.*Path|NoSuchFileException: (.*))`, func(m []string) Diagnosis {
		return Diagnosis{Title: "路径有问题", Detail: "游戏路径里可能有特殊字符，或者文件被删掉了。建议把游戏目录放在纯英文路径下。", Fix: "verify", FixLabel: "校验并补全文件"}
	}),
}

func atoi(s string) int { n, _ := strconv.Atoi(s); return n }

// exitCodeHints explains well-known native exit codes.
func exitCodeHint(code int) *Diagnosis {
	switch uint32(code) {
	case 0xC0000005:
		return &Diagnosis{Title: "游戏进程崩溃（访问冲突）", Detail: "通常是显卡驱动或某些覆盖层软件（录屏、加速器、杀毒软件）导致的。先更新显卡驱动，再关掉这些软件重试。"}
	case 0xC0000409, 0xC0000374:
		return &Diagnosis{Title: "游戏进程崩溃", Detail: "本地组件出错，通常和显卡驱动或损坏的本地库有关。", Fix: "verify", FixLabel: "校验并补全文件"}
	}
	return nil
}

// Diagnose looks at the game output and the newest crash report written since `since`.
func Diagnose(runDir string, since time.Time, logTail []string, exitCode int) Diagnosis {
	text := strings.Join(logTail, "\n")
	report := newestCrashReport(runDir, since)
	if report != "" {
		if b, err := os.ReadFile(report); err == nil {
			text = string(b) + "\n" + text
		}
	}
	if b, err := os.ReadFile(filepath.Join(runDir, "logs", "latest.log")); err == nil {
		if st, err := os.Stat(filepath.Join(runDir, "logs", "latest.log")); err == nil && st.ModTime().After(since) {
			if len(b) > 400<<10 {
				b = b[len(b)-400<<10:]
			}
			text += "\n" + string(b)
		}
	}
	for _, ru := range rules {
		if m := ru.re.FindStringSubmatch(text); m != nil {
			d := ru.f(m)
			d.Evidence = evidenceLine(text, m[0])
			d.Report = report
			return d
		}
	}
	if d := exitCodeHint(exitCode); d != nil {
		d.Report = report
		return *d
	}
	d := Diagnosis{Title: "游戏意外退出", Report: report,
		Detail: fmt.Sprintf("没有找到已知的原因（退出码 %d）。可以导出日志，或把崩溃报告发给模组作者。", exitCode),
		Fix:    "log", FixLabel: "查看完整日志"}
	if m := regexp.MustCompile(`(?m)^Description: (.+)$`).FindStringSubmatch(text); m != nil {
		d.Detail = "崩溃报告写着：" + m[1] + "。" + d.Detail
	}
	return d
}

func evidenceLine(text, match string) string {
	i := strings.Index(text, match)
	if i < 0 {
		return ""
	}
	s := strings.LastIndex(text[:i], "\n") + 1
	e := strings.Index(text[i:], "\n")
	if e < 0 {
		e = len(text) - i
	}
	line := strings.TrimSpace(text[s : i+e])
	if len(line) > 300 {
		line = line[:300] + "…"
	}
	return line
}

func newestCrashReport(runDir string, since time.Time) string {
	dir := filepath.Join(runDir, "crash-reports")
	ents, err := os.ReadDir(dir)
	if err != nil {
		return ""
	}
	type fi struct {
		p string
		t time.Time
	}
	var fs []fi
	for _, e := range ents {
		if info, err := e.Info(); err == nil && !e.IsDir() && info.ModTime().After(since.Add(-2*time.Second)) {
			fs = append(fs, fi{filepath.Join(dir, e.Name()), info.ModTime()})
		}
	}
	sort.Slice(fs, func(a, b int) bool { return fs[a].t.After(fs[b].t) })
	if len(fs) == 0 {
		return ""
	}
	return fs[0].p
}
