# 樱梨启动器 · 安卓版（EririLily for Android）

**这是 [Zalith Launcher 2](https://github.com/ZalithLauncher/ZalithLauncher2) 的非官方修改版**，按 GPL-3.0 协议及其附加条款发布：
名称已改为「樱梨启动器 / EririLily」，启动页和主界面都标注了「非官方修改版」，原有版权声明全部保留。
遇到问题请反馈给 EririLily，不要打扰 Zalith Launcher 团队。

制作：D0cK × Claude ｜ 原项目作者：MovTery 及 Zalith Launcher 贡献者

## 相对原版的修改（2026-10-26）

- 名称、图标、应用 ID（`cn.eririlily.launcher.android`，可和原版共存）、默认樱花粉主题色
- 启动页、主界面加「非官方修改版」标注；关于页里原作者署名改为「Zalith Launcher 2 的作者」
- **按键显示**（游戏内）：W A S D、Shift、空格、鼠标左右键，按下时亮起樱花粉；可显示每秒点击数（CPS）；可拖动、调大小和透明度
  - 输入在 `CallbackBridge` 里统一采集，触屏按键、键盘、鼠标、手柄映射都会显示
- **手机状态条**（游戏内）：帧率、电量（充电标记）、电池温度（38°C 变黄、42°C 变红）、时间、本次游玩时长；可拖动
- 以上开关和滑块都在游戏内菜单里
- 原版已有并保留：触屏虚拟按键与布局编辑、帧率数字/曲线、内存显示、手柄、多种渲染器等

新增文件：`game/input/KeystrokeTracker.kt`、`ui/screens/game/elements/EririOverlays.kt`、`res/values*/strings_eririlily.xml`、`res/drawable-nodpi/el_launcher_*.png`

## 构建

推送到 GitHub 后，Actions 会自动构建 Debug 版 APK（arm64 和全架构），在 Actions 页面的运行记录里下载。
