#!/usr/bin/env bash
# 在 Android TV 模拟器里安装并操作 Aura 桌面,用 adb 截取真实画面。
set -u
mkdir -p shots

cap() { sleep "${2:-1.5}"; adb exec-out screencap -p > "shots/$1.png"; }
key() { adb shell input keyevent "$1"; sleep 0.8; }

adb wait-for-device
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm size > shots/wm-size.txt 2>&1 || true
adb shell wm density >> shots/wm-size.txt 2>&1 || true

APK=$(ls apk/release/*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && APK=$(ls apk/*.apk 2>/dev/null | head -1)
echo "installing $APK"
adb install -r "$APK" 2>&1 | tee shots/install.txt

adb logcat -c
adb shell am start -n com.aura.launcher/.MainActivity 2>&1 | tee shots/start.txt
sleep 10

# 1 启动画面(第一个磁贴自动聚焦,保留时钟)
cap 01-home 1
# 2 向右移动焦点,顶部显示被选中应用名
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_RIGHT
cap 02-home-selected 1.5

# 3 回到顶栏,切到「全部应用」
key KEYCODE_DPAD_UP
for i in 1 2 3 4 5 6; do key KEYCODE_DPAD_LEFT; done
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_RIGHT
cap 03-apps 1.5

# 4 应用商店
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_CENTER
cap 04-store 2
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_RIGHT
cap 05-store-focus 1.5

# 5 本地安装
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_CENTER
cap 06-install 2

# 6 设置
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_CENTER
cap 07-settings 2
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_RIGHT
cap 08-settings-wallpaper 1.5

# 7 长按 OK 的应用菜单:回到主页第一个磁贴
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell am start -n com.aura.launcher/.MainActivity
sleep 4
adb shell input keyevent --longpress KEYCODE_DPAD_CENTER
cap 09-app-menu 2

adb logcat -d > shots/logcat-full.txt 2>&1 || true
grep -E "AndroidRuntime|FATAL|com.aura.launcher" shots/logcat-full.txt | tail -200 > shots/logcat-aura.txt || true
adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" > shots/focus.txt || true
exit 0
