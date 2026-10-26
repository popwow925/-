package mc

import (
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// InstInfo is everything the UI shows about an instance, worked out from its
// version json and the jsons it inherits from.
type InstInfo struct {
	ID       string
	Game     string
	Loader   string
	Java     int
	EL       *InstanceMeta
	Isolated bool
}

type infoEntry struct {
	chain []string // version ids whose jsons this entry was built from
	sig   string   // their size+mtime
	info  InstInfo
}

var (
	infoMu    sync.Mutex
	infoCache = map[string]*infoEntry{} // key: gameDir|id
)

func chainSig(gameDir string, chain []string) (string, bool) {
	var b strings.Builder
	for _, id := range chain {
		st, err := os.Stat(VersionJSON(gameDir, id))
		if err != nil {
			return "", false
		}
		b.WriteString(id)
		b.WriteByte(':')
		b.WriteString(itoa(int(st.Size())))
		b.WriteByte('@')
		b.WriteString(itoa(int(st.ModTime().UnixNano() / int64(time.Millisecond))))
		b.WriteByte(';')
	}
	return b.String(), true
}

// Info returns the instance summary. It is cached and only rebuilt when one
// of the jsons involved changes, so listing instances costs a few stat calls
// instead of parsing every version json (and its parents) several times.
func Info(gameDir, id string) (InstInfo, error) {
	key := gameDir + "|" + id
	infoMu.Lock()
	e := infoCache[key]
	infoMu.Unlock()
	if e != nil {
		if sig, ok := chainSig(gameDir, e.chain); ok && sig == e.sig {
			return e.info, nil
		}
	}
	v, err := LoadVersion(gameDir, id)
	if err != nil {
		return InstInfo{}, err
	}
	chain := []string{id}
	cur := v
	for depth := 0; cur.InheritsFrom != "" && depth < 8; depth++ {
		chain = append(chain, cur.InheritsFrom)
		p, err := LoadVersion(gameDir, cur.InheritsFrom)
		if err != nil {
			break
		}
		cur = p
	}
	info := InstInfo{ID: id, EL: v.EL, Isolated: v.EL != nil && v.EL.Isolated, Game: GameVersion(gameDir, id)}
	if r, err := Resolve(gameDir, id); err == nil {
		info.Loader = DetectLoader(r)
		info.Java, _ = requiredJava(r)
	}
	sig, ok := chainSig(gameDir, chain)
	if ok {
		infoMu.Lock()
		infoCache[key] = &infoEntry{chain: chain, sig: sig, info: info}
		infoMu.Unlock()
	}
	return info, nil
}

// InstanceIDs lists the folders under versions/ that hold a version json.
func InstanceIDs(gameDir string) []string {
	ents, _ := os.ReadDir(filepath.Join(gameDir, "versions"))
	var out []string
	for _, e := range ents {
		if !e.IsDir() {
			continue
		}
		if _, err := os.Stat(VersionJSON(gameDir, e.Name())); err == nil {
			out = append(out, e.Name())
		}
	}
	return out
}

type countEntry struct {
	mod time.Time
	n   int
}

var countCache sync.Map // dir -> countEntry

// CountContent counts enabled mods (or other content) by file name only. The
// result is cached until the folder itself changes (adding, removing or
// renaming a file updates the folder's modification time).
func CountContent(runDir, kind string) int {
	dir := filepath.Join(runDir, contentDir(kind))
	st, err := os.Stat(dir)
	if err != nil {
		return 0
	}
	ck := dir + "|" + kind
	if c, ok := countCache.Load(ck); ok && c.(countEntry).mod.Equal(st.ModTime()) {
		return c.(countEntry).n
	}
	f, err := os.Open(dir)
	if err != nil {
		return 0
	}
	names, _ := f.Readdirnames(-1)
	f.Close()
	n := 0
	for _, name := range names {
		if kind != "mod" || strings.HasSuffix(strings.ToLower(name), ".jar") {
			n++
		}
	}
	countCache.Store(ck, countEntry{st.ModTime(), n})
	return n
}

// ForgetInfo drops the cached summary of an instance (after the launcher edits its json).
func ForgetInfo(gameDir, id string) {
	infoMu.Lock()
	delete(infoCache, gameDir+"|"+id)
	infoMu.Unlock()
}

// TrimCache keeps a cache folder in check: files older than maxAge go, then the
// oldest files until the folder is under maxBytes.
func TrimCache(dir string, maxBytes int64, maxAge time.Duration) {
	type f struct {
		p    string
		size int64
		mod  time.Time
	}
	var all []f
	var total int64
	_ = filepath.WalkDir(dir, func(p string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		if time.Since(info.ModTime()) > maxAge {
			_ = os.Remove(p)
			return nil
		}
		all = append(all, f{p, info.Size(), info.ModTime()})
		total += info.Size()
		return nil
	})
	if total <= maxBytes {
		return
	}
	sort.Slice(all, func(i, j int) bool { return all[i].mod.Before(all[j].mod) })
	for _, x := range all {
		if total <= maxBytes {
			break
		}
		if os.Remove(x.p) == nil {
			total -= x.size
		}
	}
}
