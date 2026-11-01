# 坑友岛 / Frenemy Isle — M0 工程

局域网派对游戏（MultipeerConnectivity）。iPhone + iPad，iOS 17+，SwiftUI。规格见 `party-plan.md`（v0.5）。

## ⚠️ 先读这个：这份代码没有被编译过

写代码的环境里没有 Swift 工具链（swift.org 下载被网络策略拒绝），所以**从未编译、从未运行**。已经做过的只有静态检查（见下），第一次在 Xcode 里 Build 很可能出现几处编译错误，属于正常，预计是类型/可见性/拼写级别的小问题。

已做的静态检查（脚本在 `tools/`，其余是一次性命令）：
- 所有 `.swift` 文件括号配对；
- `FrenemyCore` / `FrenemyDemo` 里没有 `import MultipeerConnectivity`、`SwiftUI`、`UIKit`；
- 所有 `Codable` 类型的存储属性类型也都是 `Codable`（这一项查出并修了 `RankedResult`）；
- `python3 tools/check_strings.py`：代码里用到的 142 个界面文案 key 在 `Localizable.xcstrings` 里都有中英文，格式占位符一致；
- 两个语言的内容包 key 完全一致、占位符一致，引擎会发出的所有内容 ID 都有对应文案；
- JSON / plist / xcstrings 文件格式有效。

**没有验证过的：** 编译、全部单元测试（`Tests/`，测试期望值是手算的）、任何真机行为、本地网络权限弹窗、外接屏、Multipeer 实际连接。

## 目录

```
Package.swift            Swift Package：FrenemyCore / FrenemyDemo / 测试
Sources/FrenemyCore      纯规则与引擎（只依赖 Foundation）
Sources/FrenemyDemo      完全离线的单机演示（内存传输 + 机器人）
Tests/FrenemyCoreTests   规则与整局离线自动跑的测试
App/                     iOS App（SwiftUI）
  Net/MultipeerTransport.swift   唯一碰 MultipeerConnectivity 的文件
  Model/                 AppModel、本机资料、内容加载
  Views/                 首页、建房/大厅/加入、公共控制台(大屏)、玩家手机界面、设置
  Resources/             Localizable.xcstrings、InfoPlist.xcstrings、Content/*.json、隐私清单、图标目录
  Info.plist             含 NSLocalNetworkUsageDescription、NSBonjourServices
project.yml              XcodeGen 配置
docs/                    隐私政策模板（中/英）、App Review 备注模板
tools/check_strings.py   文案 key 校验
```

## 怎么跑

1. 先跑核心测试：Mac 上 `swift test`（或 Xcode 打开 `Package.swift` → Product → Test）。先把这一步修绿，再做 App。
2. 生成工程：`brew install xcodegen && xcodegen generate`，然后打开 `FrenemyIsle.xcodeproj`。
   不用 XcodeGen 的话：新建 iOS App（SwiftUI，iOS 17），把 `App/` 里的文件拖进去，File → Add Package Dependencies → Add Local… 选本文件夹，勾选 FrenemyCore 与 FrenemyDemo，把 `App/Info.plist` 设为 Info.plist 文件，并保证 `App/Resources` 下所有文件在 Copy Bundle Resources 里。
3. 在 `project.yml`（或 Signing & Capabilities）里填 Bundle ID 与 Team。
4. **真机**运行（本地网络与 Multipeer 在模拟器上不可靠）。

## 已经做了什么 / 没做什么

| 内容 | 状态 |
|---|---|
| 规则引擎：券、护盾、终局大赌、谁最可能、内鬼任务、排名、延迟/并列 | ✅ 已写（未编译） |
| 单机演示（离线、不创建任何 Multipeer 对象、不弹本地网络权限） | ✅ 界面 + 逻辑 |
| 创建房间 / 加入房间 / 大厅 / 版本握手 / 断线 30 秒重连 | ✅ 已写，**需真机验证** |
| 公共控制台只渲染 PublicState；玩家手机才渲染 PrivateState | ✅ |
| 举报昵称 / 本机隐藏 / 房主移出 | ✅ 举报已发送给房主；**房主查看举报列表的界面还没做** |
| 外接屏（TV 场景） | 🧪 PoC：代码已写，**必须真机验证**；镜像投屏时不会产生外接屏场景，此时房主屏幕本身就是大屏（它只显示公共信息） |
| 小游戏 | ⚠️ **占位**：所有小游戏目前都是“5 秒点击挑战”（木头人反过来：点得越少越好）。真正的摇晃/划屏（CoreMotion）、画画传话、抢答、卧底词展示在 M1a/M1b。接入 CoreMotion 时要补 `NSMotionUsageDescription` |
| 房主同时当玩家 | ❌ v1 决定：真实房间里房主设备只当公共控制台，不是玩家。想一个人试玩用单机演示。2–3 人小局需要另一台设备当房主 |
| 内容 | 中/英各 64 条文案、8 道题、10 组卧底词；屏蔽词表只是**种子**，上架前必须由母语者审核扩充 |
| 隐私清单 | 只声明了实际用到的 UserDefaults（CA92.1）。归档后请用 Xcode 的 Privacy Report 再核对一遍 |
| App 图标 | ✅ 已设计（源文件 `design/AppIcon.svg`，成品在 AppIcon.appiconset）；上架前你可以按喜好调整 |

## 需要你提供 / 替换

- 支持邮箱、隐私政策网址 → `App/Views/SettingsView.swift` 里的 `SupportInfo`（现在是 example.com 占位）
- Bundle ID 前缀、Team ID → `project.yml`
- 二维码落地页是否要域名/静态托管（否则二维码指向 App Store）——之前问过，还没回复
- 开发者名称、联系邮箱 → `docs/privacy-policy.*.md`

## 我做的假设（和 plan 不完全一致处请纠正）

- 内鬼任务不会出现在最后一轮（plan 没写，我为了让终局大赌结算不叠加奖励而加的）。
- 界面框架文字（按钮、阶段名）按各设备的系统语言显示；游戏内事件/称号/题目按**房间内容语言**显示。所以同一局里手机界面可能一台中文一台英文，但大屏和游戏文案一致。
- 玩家 ID 是首次启动生成的 8 位随机十六进制，存在 UserDefaults，同时作为 MultipeerConnectivity 的设备名。

## 真机测试清单

1. 两台设备同一 Wi-Fi：建房 → 加入 → 开局 → 整局跑完 → 结束后回首页。
2. 第一次的本地网络弹窗：先点“不允许”，确认界面有提示；再去设置里打开，确认能恢复。
3. 玩家中途锁屏/切后台 30 秒内回来能否重连；超过 30 秒是否被拒绝。
4. 两台设备装不同版本（改 `content.*.json` 里的 `version`）→ 应提示更新。
5. 房主把玩家移出后，该玩家不能重新加入。
6. iPad 接外接屏 / AirPlay“用作单独显示器”，确认 TV 场景是否出现；若不出现，回到镜像方案。
7. 单机演示在飞行模式下完整跑通，且全程没有本地网络弹窗。
