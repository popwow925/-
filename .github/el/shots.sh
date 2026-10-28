#!/bin/bash
# v2 smoke test on the emulator: install, open, capture screens + logcat. Edit to change what runs.
set -x
PKG=cn.eririlily.launcher
OUT=shots; mkdir -p $OUT
n=0
shot(){ n=$((n+1)); f=$(printf "%02d_%s" $n "$1"); adb exec-out screencap -p > $OUT/$f.png; }
adb root; sleep 3
adb shell "setprop persist.sys.locale zh-CN; setprop ctl.restart zygote"; sleep 25
adb wait-for-device; adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell getprop ro.product.cpu.abilist > $OUT/abilist.txt
adb install -r -g apk/*.apk > $OUT/install.txt 2>&1
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 20; shot start
sleep 25; shot later
adb shell ps -A | grep -i eririlily > $OUT/ps.txt
adb logcat -d > $OUT/logcat_full.txt
grep -iE "eririlily|AndroidRuntime|FATAL|DEBUG|libc|crash" $OUT/logcat_full.txt | tail -400 > $OUT/logcat.txt
