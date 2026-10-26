package mc

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	_ "image/png"
	"io"
	"mime/multipart"
	"net/url"
	"os"
	"path/filepath"
	"time"
)

// Skin settings for one account, stored next to the png in <data>/skins.
type SkinMeta struct {
	Model  string `json:"model"`  // classic | slim
	Source string `json:"source"` // custom | official | default
	From   string `json:"from,omitempty"`
}

func skinDir(dataDir string) string { return filepath.Join(dataDir, "skins") }

func CustomSkinPath(dataDir, id string) string {
	return filepath.Join(skinDir(dataDir), id+".png")
}

func readSkinMeta(dataDir, id string) SkinMeta {
	var m SkinMeta
	_ = readJSON(filepath.Join(skinDir(dataDir), id+".json"), &m)
	if m.Model != "slim" {
		m.Model = "classic"
	}
	return m
}

// ValidateSkin checks a png is a Minecraft skin (64x64 or legacy 64x32, or HD multiples).
func ValidateSkin(b []byte) error {
	cfg, format, err := image.DecodeConfig(bytes.NewReader(b))
	if err != nil || format != "png" {
		return errors.New("请选择 PNG 格式的皮肤文件")
	}
	if cfg.Width%64 != 0 || cfg.Width == 0 || !(cfg.Height == cfg.Width || cfg.Height*2 == cfg.Width) || cfg.Width > 1024 {
		return fmt.Errorf("皮肤尺寸应为 64×64 或 64×32（当前 %d×%d）", cfg.Width, cfg.Height)
	}
	return nil
}

// SaveCustomSkin stores a skin chosen by the user for an account.
func SaveCustomSkin(dataDir, id string, png []byte, model, from string) error {
	if err := ValidateSkin(png); err != nil {
		return err
	}
	if err := os.MkdirAll(skinDir(dataDir), 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(CustomSkinPath(dataDir, id), png, 0o644); err != nil {
		return err
	}
	if model != "slim" {
		model = "classic"
	}
	return writeJSON(filepath.Join(skinDir(dataDir), id+".json"), SkinMeta{Model: model, Source: "custom", From: from})
}

func SetSkinModel(dataDir, id, model string) error {
	m := readSkinMeta(dataDir, id)
	m.Model = model
	if m.Source == "" {
		m.Source = "custom"
	}
	return writeJSON(filepath.Join(skinDir(dataDir), id+".json"), m)
}

func ResetSkin(dataDir, id string) {
	os.Remove(CustomSkinPath(dataDir, id))
	os.Remove(filepath.Join(skinDir(dataDir), id+".json"))
}

// ProfileSkin fetches the public skin of a premium player (by uuid without dashes).
func ProfileSkin(ctx context.Context, uuid string) ([]byte, string, error) {
	var prof struct {
		Properties []struct {
			Name, Value string
		} `json:"properties"`
	}
	if err := GetJSON(ctx, "https://sessionserver.mojang.com/session/minecraft/profile/"+url.PathEscape(uuid), &prof); err != nil {
		return nil, "", err
	}
	for _, p := range prof.Properties {
		if p.Name != "textures" {
			continue
		}
		raw, err := base64.StdEncoding.DecodeString(p.Value)
		if err != nil {
			return nil, "", err
		}
		var tex struct {
			Textures struct {
				Skin *struct {
					URL      string `json:"url"`
					Metadata struct {
						Model string `json:"model"`
					} `json:"metadata"`
				} `json:"SKIN"`
			} `json:"textures"`
		}
		if err := json.Unmarshal(raw, &tex); err != nil {
			return nil, "", err
		}
		if tex.Textures.Skin == nil {
			return nil, "", errors.New("这个玩家使用的是默认皮肤")
		}
		u := tex.Textures.Skin.URL
		if len(u) > 7 && u[:7] == "http://" {
			u = "https://" + u[7:]
		}
		b, err := GetBytes(ctx, u)
		if err != nil {
			return nil, "", err
		}
		model := "classic"
		if tex.Textures.Skin.Metadata.Model == "slim" {
			model = "slim"
		}
		return b, model, nil
	}
	return nil, "", errors.New("找不到皮肤信息")
}

// LookupPlayer resolves a premium player name to a uuid.
func LookupPlayer(ctx context.Context, name string) (string, error) {
	if !reName.MatchString(name) {
		return "", errors.New("玩家名需为 3–16 位字母、数字或下划线")
	}
	var p struct{ ID, Name string }
	err := GetJSON(ctx, "https://api.minecraftservices.com/minecraft/profile/lookup/name/"+url.PathEscape(name), &p)
	if err != nil || p.ID == "" {
		if err2 := GetJSON(ctx, "https://api.mojang.com/users/profiles/minecraft/"+url.PathEscape(name), &p); err2 != nil || p.ID == "" {
			return "", fmt.Errorf("找不到正版玩家「%s」", name)
		}
	}
	return p.ID, nil
}

// officialCache keeps a premium account's skin for an hour.
func officialCache(dataDir, uuid string) string {
	return filepath.Join(skinDir(dataDir), "cache", uuid+".png")
}

// AccountSkin returns the png and model to show for an account.
func AccountSkin(ctx context.Context, dataDir string, acc Account) ([]byte, SkinMeta) {
	if b, err := os.ReadFile(CustomSkinPath(dataDir, acc.ID)); err == nil {
		m := readSkinMeta(dataDir, acc.ID)
		m.Source = "custom"
		return b, m
	}
	if acc.Type == "msa" {
		cp := officialCache(dataDir, acc.ID)
		metaP := cp + ".json"
		if st, err := os.Stat(cp); err == nil && time.Since(st.ModTime()) < time.Hour {
			if b, err := os.ReadFile(cp); err == nil {
				var m SkinMeta
				_ = readJSON(metaP, &m)
				m.Source = "official"
				return b, m
			}
		}
		cctx, cancel := context.WithTimeout(ctx, 8*time.Second)
		defer cancel()
		if b, model, err := ProfileSkin(cctx, acc.ID); err == nil {
			_ = os.MkdirAll(filepath.Dir(cp), 0o755)
			_ = os.WriteFile(cp, b, 0o644)
			_ = writeJSON(metaP, SkinMeta{Model: model})
			return b, SkinMeta{Model: model, Source: "official"}
		}
		if b, err := os.ReadFile(cp); err == nil { // stale but better than nothing
			var m SkinMeta
			_ = readJSON(metaP, &m)
			m.Source = "official"
			return b, m
		}
	}
	return nil, SkinMeta{Model: "classic", Source: "default"}
}

// UploadOfficialSkin changes a premium account's skin on Mojang's servers.
func UploadOfficialSkin(ctx context.Context, acc Account, png []byte, model string) error {
	if acc.Type != "msa" || acc.Token == "" {
		return errors.New("只有微软正版账户可以上传皮肤")
	}
	if model != "slim" {
		model = "classic"
	}
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	_ = mw.WriteField("variant", model)
	fw, _ := mw.CreateFormFile("file", "skin.png")
	_, _ = fw.Write(png)
	mw.Close()
	req, err := newReq(ctx, "POST", "https://api.minecraftservices.com/minecraft/profile/skins", &body)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+acc.Token)
	resp, err := client.Do(req)
	if err != nil {
		return netErr(req.URL.String(), err)
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 300))
		return fmt.Errorf("上传失败（HTTP %d）%s", resp.StatusCode, string(b))
	}
	return nil
}

// InstallOfflineSkin lets CustomSkinLoader (if the instance has it) show the
// offline account's custom skin in game.
func InstallOfflineSkin(dataDir, runDir string, acc Account) {
	if acc.Type != "offline" {
		return
	}
	b, err := os.ReadFile(CustomSkinPath(dataDir, acc.ID))
	if err != nil {
		return
	}
	dst := filepath.Join(runDir, "CustomSkinLoader", "LocalSkin", "skins", acc.Name+".png")
	_ = os.MkdirAll(filepath.Dir(dst), 0o755)
	_ = os.WriteFile(dst, b, 0o644)
}
