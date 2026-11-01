把要内置的 APK 放进这个文件夹(例如 bilibili-tv.apk),重新编译即可:
桌面的「应用商店」页顶部会出现「内置应用」区,选中后按 OK 键安装。

- 只认 .apk 结尾的文件;包名、名称、版本号、图标都从 APK 里读取。
- 同一个包名放了多个版本时,取版本号最高的。
- .gitignore 默认忽略 *.apk,避免把第三方应用误提交到公开仓库。
- 不想重新编译时,也可以用 adb 直接推送(桌面运行过一次后目录才存在):
    adb push xxx.apk /sdcard/Android/data/com.aura.launcher/files/preinstall/
