# 游戏运行层 · 来源说明

本模块是樱梨启动器（EririLily）安卓版里**唯一**来自其他项目的部分：

- `src/main/jni/`：游戏运行层原生代码（进程内 JVM 启动、EGL/GLFW 桥接、输入、AWT/Cacio），
  来自 [Zalith Launcher 2](https://github.com/ZalithLauncher/ZalithLauncher2)（GPL-3.0，含附加条款），
  其上游为 PojavLauncher。
- `src/main/jniLibs/`、`../app/libs/*.aar`：渲染器与运行库预编译包（Krypton Wrapper / NG-GL4ES、GL4ES、
  Mesa Zink / Freedreno / Panfrost / VirGL、LWJGL 原生库、OpenAL Soft、SDL），随 Zalith Launcher 2 分发，
  各自遵循其原有许可证。
- `src/main/assets/`：安卓版 Java 运行时、LWJGL（Zalith 修改版，GLFW 由 Java 实现）、Caciocavallo、
  MioLaunchWrapper / MioLibPatcher、authlib-injector。
- `src/main/java/` 中 `com.movtery.*`、`org.lwjgl.glfw.*`、`org.libsdl.*`、`com.oracle.dalvik.*`：
  原生代码按名字回调的 Java 类，类名与方法签名必须保持不变；其中与界面、设置相关的依赖已删去。

`src/main/java/cn/eririlily/runtime/` 是樱梨自己写的启动层（运行时解包、渲染器选择、启动参数与环境），
启动流程参照 Zalith 的 Launcher / GameLauncher / LaunchArgs，以保证原生层拿到它期望的环境。

因为包含 GPL-3.0 代码，整个安卓版以 GPL-3.0 开源发布。这是非官方作品，与 Zalith Launcher 团队无关；
遇到问题请反馈给 EririLily。
