# Aura 桌面 — tvOS 风格的 Android TV 启动器

给索尼 Bravia(Android TV / Google TV,Android 9+)用的第三方桌面。
全中文界面,不依赖 Google 服务,无广告、无统计、不联网也能用(只有「应用商店」会联网)。

## 功能

- **主页**:大号时钟 / 当前选中应用名,下方是「常用应用」「最近使用」「信号源与快捷设置」三行横向磁贴
- **全部应用**:5 列网格;自动读取已安装应用(含电视入口和手机入口),装/卸后实时刷新
- **长按 OK 键**:打开 / 加入或移出常用 / 移到最前 / 隐藏 / 应用信息 / 卸载
- **应用商店**:读取你自己托管的 JSON 清单 → 下载 → SHA-256 与包名校验 → 系统安装器安装,支持取消、更新提示
- **本地安装**:浏览内部存储和 U 盘,选中 `.apk` 直接安装
- **设置**:5 套壁纸、设为默认桌面指引、商店源地址、恢复隐藏应用
- **信号源**:自动列出 HDMI / AV 直通输入,一键切换

## 编译成 APK

需要 Android Studio(Koala 及以上)或 JDK 17 + Android SDK 34。

```bash
./gradlew assembleRelease
# 输出:app/build/outputs/apk/release/app-release.apk
adb connect 电视IP:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```

仓库已配置阿里云 / 腾讯云镜像,国内网络可直接同步依赖。
release 默认用 debug 签名,方便侧载;正式分发请换成你自己的签名。

## 设为默认桌面

安装后 Aura 会出现在电视应用列表里,可以先手动打开试用。要让「主页」键回到 Aura:

```bash
adb shell cmd package set-home-activity com.aura.launcher/.MainActivity
```

> 部分索尼机型的遥控器「主页」键固定进入原厂桌面,这是系统限制,Aura 无法绕过。
> 恢复原厂桌面:设置 → 应用 中清除 Aura 的默认设置,或直接卸载 Aura。

## 配置应用商店

商店不内置任何第三方 APK。把清单 JSON 和 APK 放到你自己的服务器 / 对象存储 / Gitee Pages(必须是 https),
然后在电视上「设置 → 商店源地址」填入清单地址;也可以编译时指定:

```bash
./gradlew assembleRelease -PstoreUrl=https://your-server.example.com/aura/store.json
```

清单格式(参考 `app/src/main/assets/store.json`):

```json
{
  "name": "我的应用商店",
  "apps": [
    {
      "id": "vlc",
      "name": "VLC 播放器",
      "packageName": "org.videolan.vlc",
      "versionCode": 13050000,
      "versionName": "3.5.0",
      "size": 41943040,
      "sha256": "APK 文件的 SHA-256(强烈建议填写)",
      "apkUrl": "https://your-server.example.com/apks/vlc.apk",
      "iconUrl": "",
      "bannerUrl": "",
      "summary": "一句话介绍",
      "category": "影音"
    }
  ]
}
```

安全保护:下载地址必须是 https;填了 `sha256` 会校验;下载后还会核对 APK 里的包名与清单一致。
`versionCode` 大于已安装版本时,商店里显示「更新」。

## 本地安装 / U 盘

- 首次使用需要授予「所有文件访问」(Android 11+)和「允许安装未知应用」。
- 如果电视没有这两个设置页面,用电脑执行:

```bash
adb shell appops set com.aura.launcher MANAGE_EXTERNAL_STORAGE allow
adb shell appops set com.aura.launcher REQUEST_INSTALL_PACKAGES allow
```

## 性能设计

- 壁纸是纯渐变绘制,无位图、无模糊、无持续动画,切换时只做一次交叉淡入
- 焦点动画(放大、光晕、高光、倾斜回弹)全部只在绘制 / 图层阶段读取,焦点移动不触发重组
- 应用图标在后台线程加载并缓存(LRU),列表使用稳定 key 的 Lazy 布局
- 主页纵向按「行」吸附滚动,横向只在焦点快出屏时才滚动,统一使用无回弹弹簧
- 布局按 1920×1080 设计稿等比缩放,1080p / 4K、不同 dpi 的电视比例一致

## 目录结构

```
app/src/main/java/com/aura/launcher/
├── MainActivity.kt          入口,处理主页键与权限回调
├── data/                    应用读取、偏好、安装器、U 盘浏览、商店、信号源
└── ui/                      主题与缩放、磁贴与动画、各页面、弹窗、壁纸
```

## 已知限制

- 这份工程是在无法联网下载 Android 依赖的环境里写成的,**尚未在真机或模拟器上编译运行过**。
  首次编译如遇到小的依赖版本或 API 报错,把报错贴给我即可快速修复。
- 应用商店只提供「清单 → 下载 → 安装」的机制,APK 来源和版权由你自己负责。
- 没有内置影视推荐内容(按你的选择只做应用和设置)。
