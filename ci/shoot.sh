#!/usr/bin/env bash
# 在 Android TV 模拟器里安装并操作 Aura 桌面,用 adb 截取真实画面。
set -u
mkdir -p shots

cap() { sleep "${2:-1.5}"; adb exec-out screencap -p > "shots/$1.png"; }
key() { adb shell input keyevent "$1"; sleep 0.8; }
rep() { local n=$1 k=$2; for _ in $(seq 1 "$n"); do key "$k"; done; }

# 绝对定位到第 N 个顶栏标签(0 主页 / 1 影视 / 2 直播 / 3 全部应用 / 4 应用商店 / 5 本地安装 / 6 设置)并确认。
# 先连按"上"回到顶栏,再连按"左"到最左边的标签,最后向右走 N 步。
goto_tab() {
  rep 6 KEYCODE_DPAD_UP
  rep 6 KEYCODE_DPAD_LEFT
  [ "$1" -gt 0 ] && rep "$1" KEYCODE_DPAD_RIGHT
  key KEYCODE_DPAD_CENTER
}

# 连发一串按键后立刻截图(用于只出现不到一秒的界面,例如快进预览)
burst() { adb shell "input keyevent $2 && screencap -p /sdcard/fast.png" && adb pull /sdcard/fast.png "shots/$1.png" > /dev/null; }

adb wait-for-device
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm size > shots/wm-size.txt 2>&1 || true
adb shell wm density >> shots/wm-size.txt 2>&1 || true

APK=$(ls apk/release/*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && APK=$(ls apk/*.apk 2>/dev/null | head -1)
echo "installing $APK"
adb install -r "$APK" 2>&1 | tee shots/install.txt

# 启动耗时:先测冷启动(未预编译),再按 baseline profile 预编译后再测
measure() {
  for i in 1 2 3; do
    adb shell am force-stop com.aura.launcher; sleep 1
    adb shell am start -W -n com.aura.launcher/.MainActivity | grep -E "TotalTime|WaitTime" | tr '\n' ' '; echo
    sleep 3
  done
}
{ echo "== before profile compile"; measure
  adb shell cmd package compile -f -m speed-profile com.aura.launcher
  echo "== after speed-profile compile"; measure; } > shots/startup.txt 2>&1 || true

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

# 2c 影视:"模拟家庭中心"已在工作流里启动(真实家庭中心的接口输出 + ffmpeg 生成的演示影片)。
#     模拟器里电脑固定是 10.0.2.2,Aura 用 UDP 广播自动找到它
curl -s http://127.0.0.1:8080/api/tv/hello > shots/mockhub-hello.txt 2>&1 || true

goto_tab 1
cap 30-movies-setup 2
key KEYCODE_DPAD_DOWN
cap 31-movies-setup-focus 1
key KEYCODE_DPAD_CENTER
cap 32-connect-menu 1.5
key KEYCODE_DPAD_CENTER           # 自动搜索;连上后焦点应自动落在片库第一张磁贴
sleep 7
cap 33-movies-library 3
key KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_RIGHT
cap 34-movies-selected 1.5
key KEYCODE_DPAD_DOWN
cap 35-movies-row2 2
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_LEFT
key KEYCODE_DPAD_LEFT
key KEYCODE_DPAD_CENTER           # 打开详情(第一部:星河漫游)
cap 36-movie-detail 2
key KEYCODE_DPAD_CENTER           # 播放
cap 37-player-overlay 2.5
cap 38-player-clean 6
burst 39-player-seek-preview "KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT"
cap 40-player-after-seek 2.5
key KEYCODE_DPAD_CENTER           # 暂停
cap 41-player-paused 1
key KEYCODE_DPAD_CENTER           # 继续
sleep 2
adb shell input keyevent KEYCODE_BACK
sleep 2
cap 42-after-player 2             # 回到详情,应显示"继续播放"
adb shell input keyevent KEYCODE_BACK
cap 43-library-continue 2.5       # 片库最上面多出"继续观看"一行

# 剧集:走到「电影与剧集」行的最右端(剧集磁贴),打开剧集弹窗,播放第一集并拖到片尾,看"下一集"倒数
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_DOWN
for _ in 1 2 3 4 5 6 7 8; do adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 0.25; done
cap 44-movies-poster-row 2
key KEYCODE_DPAD_CENTER
cap 45-series-dialog 2
key KEYCODE_DPAD_CENTER           # 播放当前选中的一集
sleep 4
adb shell "input keyevent KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT"
cap 46-next-episode-card 3.5
cap 47-next-episode-playing 9
adb shell input keyevent KEYCODE_BACK
sleep 2
adb shell input keyevent KEYCODE_BACK
sleep 1

# 播放器的"等待转码"和"出错"界面:直接拉起播放器(条目 3 是"转码中 42%",条目 99 不存在)
adb root > shots/adb-root.txt 2>&1 || true
sleep 3
adb wait-for-device
adb shell am start -n com.aura.launcher/.ui.PlayerActivity --ei id 3 --ez resume false > shots/player-waiting-start.txt 2>&1 || true
cap 48-player-waiting 4
adb shell input keyevent KEYCODE_BACK
sleep 1.5
adb shell am start -n com.aura.launcher/.ui.PlayerActivity --ei id 99 --ez resume false > shots/player-failed-start.txt 2>&1 || true
cap 49-player-failed 4
adb shell input keyevent KEYCODE_BACK
sleep 1.5
cp /tmp/mockhub/server.log shots/mockhub-server.log 2>/dev/null || true

# 2 直播(官方央视页;模拟器在海外网络下可能打不开,主要看列表和换台提示)
goto_tab 2
cap 50-live 2
key KEYCODE_DPAD_DOWN
cap 51-live-focus 1
key KEYCODE_DPAD_CENTER
cap 52-live-opening 3
cap 52b-live-wait 20
adb shell input keyevent KEYCODE_DPAD_DOWN
cap 53-live-next 4
adb logcat -d -s AuraLive > shots/live-log.txt 2>&1 || true
cap 54-live-later 20
adb shell input keyevent KEYCODE_BACK
sleep 2

# 3 全部应用
goto_tab 3
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_RIGHT
cap 03-apps 1.5

# 4 应用商店
goto_tab 4
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
  goto_tab 4
  cap 13-store-preinstall 3
  key KEYCODE_DPAD_DOWN
  cap 14-store-preinstall-focus 1.5
  key KEYCODE_DPAD_DOWN
  cap 21-store-preinstall-down 1.5
fi

# 5 本地安装:向下进入"授予文件访问权限"按钮,再按右键,焦点应留在按钮上
goto_tab 5
cap 06-install 2
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_RIGHT
cap 07-install-focus 1.5

# 6 设置:聚焦壁纸,按 OK 切换
goto_tab 6
cap 08-settings 2
key KEYCODE_DPAD_DOWN
cap 09-settings-wallpaper 1.5
key KEYCODE_DPAD_CENTER
cap 10-wallpaper-applied 2
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_DOWN
cap 22-settings-hub 1.5
key KEYCODE_DPAD_CENTER
cap 23-settings-hub-menu 1.5
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_CENTER
cap 24-address-keypad 2
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell input keyevent KEYCODE_BACK
sleep 1

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
