#!/usr/bin/env bash
# 在 Android TV 模拟器里安装并操作 Aura 桌面,用 adb 截取真实画面。
set -u
mkdir -p shots

cap() { sleep "${2:-1.5}"; adb exec-out screencap -p > "shots/$1.png"; }
key() { adb shell input keyevent "$1"; sleep 0.8; }
rep() { local n=$1 k=$2; for _ in $(seq 1 "$n"); do key "$k"; done; }

# 绝对定位到第 N 个顶栏标签(0 主页 / 1 全部应用 / 2 应用商店 / 3 本地安装 / 4 设置)并确认。
# 先连按"上"回到顶栏,再连按"左"到最左边的标签,最后向右走 N 步。
goto_tab() {
  rep 6 KEYCODE_DPAD_UP
  rep 6 KEYCODE_DPAD_LEFT
  [ "$1" -gt 0 ] && rep "$1" KEYCODE_DPAD_RIGHT
  key KEYCODE_DPAD_CENTER
}

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

# 2b 主页上下键(此时焦点在第三个磁贴 Play Store):
#    上 → 回到当前选中的「主页」标签;下 → 应回到同一个磁贴;再下 → 第二行;再下 → 到底不动;上 → 回第一行;再上 → 回标签
key KEYCODE_DPAD_UP
cap 15-home-up-to-tab 1.2
key KEYCODE_DPAD_DOWN
cap 16-home-down-restore 1.5
key KEYCODE_DPAD_DOWN
cap 17-home-row2 1.5
key KEYCODE_DPAD_DOWN
cap 18-home-bottom-noop 1
key KEYCODE_DPAD_UP
cap 19-home-back-row1 1.5
key KEYCODE_DPAD_UP
cap 20-home-up-again 1.2

# 3 全部应用
goto_tab 1
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_RIGHT
cap 03-apps 1.5

# 4 应用商店
goto_tab 2
cap 04-store 3
key KEYCODE_DPAD_DOWN
cap 05-store-focus 1.5

# 4b 内置应用:把示例 APK 推到外部"内置应用"目录,重新进入商店页后应出现「内置应用」区
if [ -f stub-out/demo.apk ]; then
  PRE=/sdcard/Android/data/com.aura.launcher/files/preinstall
  adb shell mkdir -p "$PRE" 2>&1 | tee -a shots/preinstall.txt
  adb push stub-out/demo.apk "$PRE/demo.apk" 2>&1 | tee -a shots/preinstall.txt
  adb shell ls -l "$PRE" 2>&1 | tee -a shots/preinstall.txt
  goto_tab 0
  goto_tab 2
  cap 13-store-preinstall 3
  key KEYCODE_DPAD_DOWN
  cap 14-store-preinstall-focus 1.5
  key KEYCODE_DPAD_DOWN
  cap 21-store-preinstall-down 1.5
fi

# 5 本地安装:向下进入"授予文件访问权限"按钮,再按右键,焦点应留在按钮上
goto_tab 3
cap 06-install 2
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_RIGHT
cap 07-install-focus 1.5

# 6 设置:聚焦壁纸,按 OK 切换
goto_tab 4
cap 08-settings 2
key KEYCODE_DPAD_DOWN
cap 09-settings-wallpaper 1.5
key KEYCODE_DPAD_CENTER
cap 10-wallpaper-applied 2

# 7 模拟遥控器主页键:HOME 意图应把桌面切回「主页」并聚焦第一个应用
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.aura.launcher/.MainActivity
cap 11-back-home 3

# 8 长按 OK 的应用菜单
adb shell input keyevent --longpress KEYCODE_DPAD_CENTER
cap 12-app-menu 2

adb logcat -d > shots/logcat-full.txt 2>&1 || true
grep -E "AndroidRuntime|FATAL|com.aura.launcher" shots/logcat-full.txt | tail -200 > shots/logcat-aura.txt || true
adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" > shots/focus.txt || true
exit 0
