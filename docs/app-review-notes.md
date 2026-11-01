# App Review 备注模板（填入 App Store Connect → App Review Information → Notes）

## English (paste this)
Frenemy Isle is a local multiplayer party game. Players join a room created by a host over the local network (MultipeerConnectivity). No account, server or internet connection is required.

How to review without other devices:
1. Open the app and tap “Solo demo” (单机演示). It plays a full game against 3 bots, entirely offline. It creates no network objects and does not trigger the Local Network prompt.
2. To review multiplayer, two devices on the same Wi-Fi are needed: on device A tap “Create room”, on device B tap “Join room” and pick the room. The first time, iOS shows the Local Network prompt; please allow it.

Local Network usage: used only to discover and connect to the host's device on the same network (Bonjour service types _frenemy._tcp and _frenemy._udp). No data leaves the local network.

User-generated content: players can show a short nickname to others in the same room. Nicknames are filtered by a blocklist; the host can remove any player; any player can report a nickname or hide it on their own device. Strong interactions are off by default. There is no chat and no free text beyond the nickname.

No sign-in, no purchases, no ads, no tracking, no data collected by the developer.

## 要在 App Store Connect 里自己核对的项目
- **App 隐私**：选“不收集数据”（Data Not Collected）。依据：没有任何数据发送到开发者或第三方。上线前再确认代码里没有新增网络请求。
- **年龄分级问卷**：按最新问卷如实回答（昵称对同房间玩家可见 = 有用户生成内容；有举报与屏蔽机制；无聊天；无赌博真钱——游戏内“水晶”和“终局大赌”是虚拟积分，不可充值、不可兑现，问卷中如有“模拟赌博”类问题请按实际如实回答并核对结果）。
- **出口合规**：Info.plist 中 `ITSAppUsesNonExemptEncryption = NO`（只使用系统自带的 MultipeerConnectivity 加密）。请你确认这个选择符合你的情况。
- **截图**：iPhone 与 iPad 各一套；建议包含电视大屏画面（用单机演示截取）。
- **本地网络权限**：真机测试时要先拒绝再允许各测一遍。
- **App 图标**：已内置 1024×1024（无透明、无圆角），上架前确认你满意即可。
