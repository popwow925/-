# 樱梨启动器 EririLily · 安卓版

在安卓手机上玩 Minecraft: Java Edition 的启动器。与 Windows 版共用同一个 Go 核心
（下载、实例、模组、整合包、存档、服务器、皮肤、诊断……），界面换成了 iOS 系统风格，
游戏在应用内的独立进程里运行。

非 Mojang 或 Microsoft 官方产品。作者：D0cK × Claude。

## 功能

**启动器（iOS 风格界面）**
- 标签栏 + 大标题导航（滚动收起）、分组列表、底部弹层、操作表、长按菜单、左缘右滑返回
- 「灵动岛」显示下载 / 安装 / 启动进度，点开可看详情和取消；后台下载有前台通知
- 实例：新建（原版 / Fabric / Forge / NeoForge / Quilt）、导入 .mrpack、封面、重命名、校验补全、指定 Java
- 内容：模组 / 资源包 / 光影的启用开关、删除、Modrinth 识别与一键更新
- 存档（备份、删除）、服务器（延迟、人数、彩色 MOTD、一键加入）、截图设为封面
- 游戏选项编辑（视距、模拟距离、帧率、界面大小、视场角、画质开关…）
- Modrinth 搜索（模组 / 整合包 / 资源包 / 光影，可只看适配当前实例的）
- 账户：离线账户、微软正版设备码登录；3D 皮肤预览、上传、按正版玩家名导入
- 游戏文件出现在系统「文件」应用侧栏，可直接放模组、备份存档
- 主题：樱花 / 纯白 / 纯黑 / 跟随系统

**游戏内**
- 虚拟键位（类似基岩版的布局）：移动摇杆（推过外圈自动疾跑）、跳跃、潜行开关、攻击 / 使用、背包、聊天…
- 键位编辑器：拖动、大小、宽高比、形状、组合键（最多 3 个）、开关模式、滑入触发、仅游戏中 / 菜单中显示、另存多套布局
- 触控：滑动转视角、轻点使用、长按挖掘、点物品栏选物品；菜单中手指即鼠标、双指轻点右键
- 帧率显示：简洁胶囊，或完整面板（帧率、帧时间曲线、渲染分辨率、内存、电池温度、温控状态）
- 「控制中心」（点屏幕顶部的胶囊）：实时调整渲染倍率、帧率上限、灵敏度、按键透明度，开关垂直同步 / 动态分辨率 / 陀螺仪 / 大核调度，截图、F3、键盘、编辑键位、退出
- 渲染倍率 25%–100%：画面按较低分辨率绘制，由屏幕硬件放大，几乎没有额外开销
- 动态分辨率：掉帧时自动降低渲染倍率，有余量时再恢复
- 键盘（含中文输入法）、鼠标（游戏中自动锁定指针）、手柄、陀螺仪瞄准

**性能**
- 一键超级优化：按机型（内存、CPU 频率、GPU）分级，设定渲染倍率、帧率上限、视距与画质，并可安装手机适用的性能模组（Lithium、FerriteCore、ModernFix…；极致模式在中高端机上加 Sodium）
- 帧率限制器（按绝对时间表休眠，帧间隔平稳）+ 告诉系统合成器目标帧率
- 持续性能模式、渲染线程绑定大核、ADPF 性能提示（Android 13+）、最高刷新率显示模式
- 按机型自动分配内存，给系统留足空间以免被杀后台

## 架构

```
core/            Go 核心（与 Windows 版同一份代码）
  mc/android*.go   安卓：JRE 安装与识别、LWJGL / 渲染器、启动描述（spec）、DNS
  mc/superopt.go   一键超级优化：机型分级与方案
  app/android.go   安卓专用 RPC（运行时、渲染器、优化、游戏选项、键位）
  app/mobile/      iOS 风格界面（安卓版提供；ERIRILILY_UI=mobile 可在电脑浏览器预览）
android/         安卓应用
  app/src/main/cpp/  libelbridge.so：Android 上的 GLFW 3.4 实现（EGL + ANativeWindow）、
                     进程内 JVM 启动器、帧统计 / 限帧 / 大核 / ADPF
  app/src/main/java/ Kotlin：WebView 外壳、核心进程、游戏界面、键位、HUD、控制中心
  scripts/           CI 用：编译 gl4es、MobileGlues、OpenAL Soft、LWJGL 原生库，获取安卓 JRE
```

- 核心作为子进程运行（打包成 `liberirilily_core.so`），在 127.0.0.1 提供界面和 RPC，通过标准输出通知应用「启动游戏」「打开文件夹」。
- 启动时核心照常补全文件、选 Java，然后把桌面版的启动参数改写成安卓启动描述：LWJGL 换成安卓构建（3.3.3），GLFW 指向 `libelbridge.so`，OpenGL 指向所选渲染器。
- 游戏进程（`:game`）里用 `JNI_CreateJavaVM` 启动 JVM；LWJGL 通过我们的 GLFW 拿到窗口、输入和上下文。日志、PID、退出码写到文件，核心据此显示日志、判断就绪与诊断崩溃。

## 构建

推送到 GitHub 后由 Actions（`.github/workflows/android.yml`）自动构建，APK 在运行记录的 Artifacts 里（`EririLily-android`）。推送 `v*` 标签会建 Release 并附上 APK 和 Windows exe。

本地：
```
cd core && go test ./mc ./app
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -o ../android/app/src/main/jniLibs/arm64-v8a/liberirilily_core.so .
bash android/scripts/build-runtime.sh android/app/src/main/jniLibs/arm64-v8a   # 需要 NDK
cd android && ./gradlew assembleRelease
```
预览手机界面：`ERIRILILY_UI=mobile ERIRILILY_FAKE_ANDROID=1 ERIRILILY_DATA=/tmp/el go run ./core`，用浏览器手机模式打开输出的地址。

正式签名：在仓库 Secrets 里设置 `EL_KEYSTORE_B64`（keystore 的 base64）、`EL_KEYSTORE_PASSWORD`、`EL_KEY_ALIAS`、`EL_KEY_PASSWORD`；没有时用调试签名。

## 已知限制

- 只支持 64 位 ARM（arm64-v8a）手机。
- 1.12.2 及更早的版本使用 LWJGL 2，暂不支持。
- Java 运行时来自 PojavLauncherTeam 的安卓 OpenJDK 构建，CI 能取到时内置在安装包里；取不到时在「我的 › Java 与内存」导入 .tar.xz，或填写下载地址。
- 渲染器：1.17 起默认 MobileGlues，更早用 GL4ES；也可导入其他启动器的渲染器插件（.so）。
- 微软正版登录需要已获批的 Azure 应用 ID（不内置、不借用其他启动器的 ID）。

## 开源组件

OpenJDK（GPLv2 + Classpath Exception）、LWJGL 3（BSD）、GL4ES（MIT）、MobileGlues（LGPL-2.1）、OpenAL Soft（LGPL-2.0）、FreeType（FTL）、libffi（MIT）。
