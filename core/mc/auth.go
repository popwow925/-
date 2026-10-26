package mc

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

type Account struct {
	ID       string `json:"id"`   // uuid without dashes
	Name     string `json:"name"` // player name
	Type     string `json:"type"` // offline | msa
	Token    string `json:"token,omitempty"`
	Refresh  string `json:"refresh,omitempty"`
	Expires  int64  `json:"expires,omitempty"`
	XUID     string `json:"xuid,omitempty"`
	ClientID string `json:"clientId,omitempty"`
}

// Public view (no tokens) for the UI.
func (a Account) Public() map[string]any {
	return map[string]any{"id": a.ID, "name": a.Name, "type": a.Type}
}

type Accounts struct {
	mu   sync.Mutex
	path string
	List []Account `json:"accounts"`
}

func OpenAccounts(dataDir string) *Accounts {
	a := &Accounts{path: filepath.Join(dataDir, "accounts.json")}
	_ = readJSON(a.path, a)
	return a
}

func (a *Accounts) save() { _ = writeJSON(a.path, a) }

func (a *Accounts) All() []Account {
	a.mu.Lock()
	defer a.mu.Unlock()
	return append([]Account(nil), a.List...)
}

func (a *Accounts) Get(id string) (Account, bool) {
	a.mu.Lock()
	defer a.mu.Unlock()
	for _, x := range a.List {
		if x.ID == id {
			return x, true
		}
	}
	return Account{}, false
}

func (a *Accounts) Put(acc Account) {
	a.mu.Lock()
	defer a.mu.Unlock()
	for i, x := range a.List {
		if x.ID == acc.ID {
			a.List[i] = acc
			a.save()
			return
		}
	}
	a.List = append(a.List, acc)
	a.save()
}

func (a *Accounts) Remove(id string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	out := a.List[:0]
	for _, x := range a.List {
		if x.ID != id {
			out = append(out, x)
		}
	}
	a.List = out
	a.save()
}

var reName = regexp.MustCompile(`^[A-Za-z0-9_]{3,16}$`)

// OfflineAccount makes the same UUID the vanilla server uses for offline players.
func OfflineAccount(name string) (Account, error) {
	if !reName.MatchString(name) {
		return Account{}, errors.New("玩家名需为 3–16 位字母、数字或下划线")
	}
	h := md5.Sum([]byte("OfflinePlayer:" + name))
	h[6] = h[6]&0x0f | 0x30
	h[8] = h[8]&0x3f | 0x80
	return Account{ID: hex.EncodeToString(h[:]), Name: name, Type: "offline"}, nil
}

// ---------------------------------------------------------------- Microsoft

type DeviceCode struct {
	UserCode        string `json:"user_code"`
	DeviceCode      string `json:"device_code"`
	VerificationURI string `json:"verification_uri"`
	ExpiresIn       int    `json:"expires_in"`
	Interval        int    `json:"interval"`
	Message         string `json:"message"`
}

func postForm(ctx context.Context, u string, form url.Values, out any) (int, error) {
	req, err := newReq(ctx, "POST", u, strings.NewReader(form.Encode()))
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	_ = json.Unmarshal(b, out)
	return resp.StatusCode, nil
}

func postJSON(ctx context.Context, u string, body any, bearer string, out any) (int, error) {
	bb, _ := json.Marshal(body)
	req, err := newReq(ctx, "POST", u, bytes.NewReader(bb))
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	resp, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	_ = json.Unmarshal(b, out)
	return resp.StatusCode, nil
}

const msBase = "https://login.microsoftonline.com/consumers/oauth2/v2.0/"

// BuiltinMSClientID is the launcher's own Azure app id for Microsoft login. It is
// injected at build time (-ldflags "-X eririlily/mc.BuiltinMSClientID=…") so the id
// is not kept in the source tree. Empty = users must enter their own in settings.
var BuiltinMSClientID = ""

// MSClient picks the Azure app id: the user's own if set, else the built-in one.
func MSClient(user string) string {
	if u := strings.TrimSpace(user); u != "" {
		return u
	}
	return BuiltinMSClientID
}

func MSDeviceCode(ctx context.Context, clientID string) (*DeviceCode, error) {
	if clientID == "" {
		return nil, errors.New("请先在设置里填写 Azure 应用的客户端 ID")
	}
	var dc DeviceCode
	code, err := postForm(ctx, msBase+"devicecode", url.Values{"client_id": {clientID}, "scope": {"XboxLive.signin offline_access"}}, &dc)
	if err != nil {
		return nil, err
	}
	if code != 200 || dc.DeviceCode == "" {
		return nil, fmt.Errorf("微软登录初始化失败（HTTP %d），请检查客户端 ID", code)
	}
	if dc.Interval <= 0 {
		dc.Interval = 5
	}
	return &dc, nil
}

type msToken struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	Error        string `json:"error"`
}

// MSPoll waits for the user to finish the device-code login, then signs into Minecraft.
func MSPoll(ctx context.Context, clientID string, dc *DeviceCode) (Account, error) {
	deadline := time.Now().Add(time.Duration(dc.ExpiresIn) * time.Second)
	interval := time.Duration(dc.Interval) * time.Second
	for time.Now().Before(deadline) {
		select {
		case <-ctx.Done():
			return Account{}, ctx.Err()
		case <-time.After(interval):
		}
		var t msToken
		_, err := postForm(ctx, msBase+"token", url.Values{
			"grant_type":  {"urn:ietf:params:oauth:grant-type:device_code"},
			"client_id":   {clientID},
			"device_code": {dc.DeviceCode},
		}, &t)
		if err != nil {
			continue
		}
		switch t.Error {
		case "":
			return minecraftLogin(ctx, clientID, t)
		case "authorization_pending":
			continue
		case "slow_down":
			interval += 5 * time.Second
		case "authorization_declined":
			return Account{}, errors.New("你取消了授权")
		case "expired_token":
			return Account{}, errors.New("登录码已过期，请重试")
		default:
			return Account{}, fmt.Errorf("微软登录失败：%s", t.Error)
		}
	}
	return Account{}, errors.New("登录超时")
}

// MSRefresh renews an expired Minecraft token.
func MSRefresh(ctx context.Context, acc Account) (Account, error) {
	var t msToken
	_, err := postForm(ctx, msBase+"token", url.Values{
		"grant_type":    {"refresh_token"},
		"client_id":     {acc.ClientID},
		"refresh_token": {acc.Refresh},
		"scope":         {"XboxLive.signin offline_access"},
	}, &t)
	if err != nil {
		return acc, err
	}
	if t.AccessToken == "" {
		return acc, errors.New("登录已失效，请重新登录微软账户")
	}
	return minecraftLogin(ctx, acc.ClientID, t)
}

func minecraftLogin(ctx context.Context, clientID string, t msToken) (Account, error) {
	var xbl struct {
		Token         string
		DisplayClaims struct {
			Xui []struct {
				Uhs string `json:"uhs"`
			} `json:"xui"`
		}
	}
	if _, err := postJSON(ctx, "https://user.auth.xboxlive.com/user/authenticate", map[string]any{
		"Properties":   map[string]any{"AuthMethod": "RPS", "SiteName": "user.auth.xboxlive.com", "RpsTicket": "d=" + t.AccessToken},
		"RelyingParty": "http://auth.xboxlive.com", "TokenType": "JWT",
	}, "", &xbl); err != nil || xbl.Token == "" || len(xbl.DisplayClaims.Xui) == 0 {
		return Account{}, errors.New("Xbox Live 验证失败")
	}
	uhs := xbl.DisplayClaims.Xui[0].Uhs
	var xsts struct {
		Token string
		XErr  int64
	}
	if _, err := postJSON(ctx, "https://xsts.auth.xboxlive.com/xsts/authorize", map[string]any{
		"Properties":   map[string]any{"SandboxId": "RETAIL", "UserTokens": []string{xbl.Token}},
		"RelyingParty": "rp://api.minecraftservices.com/", "TokenType": "JWT",
	}, "", &xsts); err != nil {
		return Account{}, err
	}
	switch xsts.XErr {
	case 0:
	case 2148916233:
		return Account{}, errors.New("该微软账户还没有 Xbox 档案，请先登录一次 xbox.com")
	case 2148916235:
		return Account{}, errors.New("Xbox Live 在你所在的地区不可用")
	case 2148916238:
		return Account{}, errors.New("未成年账户需要家长把它加入家庭组")
	default:
		return Account{}, fmt.Errorf("XSTS 验证失败（%d）", xsts.XErr)
	}
	var mc struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int64  `json:"expires_in"`
	}
	code, err := postJSON(ctx, "https://api.minecraftservices.com/authentication/login_with_xbox",
		map[string]string{"identityToken": "XBL3.0 x=" + uhs + ";" + xsts.Token}, "", &mc)
	if err != nil || mc.AccessToken == "" {
		if code == 403 {
			return Account{}, errors.New("此客户端 ID 尚未获得 Mojang 的 Minecraft API 授权")
		}
		return Account{}, fmt.Errorf("Minecraft 登录失败（HTTP %d）", code)
	}
	req, _ := newReq(ctx, "GET", "https://api.minecraftservices.com/minecraft/profile", nil)
	req.Header.Set("Authorization", "Bearer "+mc.AccessToken)
	resp, err := client.Do(req)
	if err != nil {
		return Account{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == 404 {
		return Account{}, errors.New("这个账户还没有购买 Minecraft: Java Edition")
	}
	var prof struct{ ID, Name string }
	_ = json.NewDecoder(resp.Body).Decode(&prof)
	if prof.ID == "" {
		return Account{}, errors.New("读取玩家档案失败")
	}
	return Account{ID: prof.ID, Name: prof.Name, Type: "msa", Token: mc.AccessToken, Refresh: t.RefreshToken,
		Expires: time.Now().Unix() + mc.ExpiresIn - 300, ClientID: clientID}, nil
}
