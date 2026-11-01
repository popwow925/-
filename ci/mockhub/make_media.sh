#!/usr/bin/env bash
# 生成演示用的"影片"(渐变动画 + 当前时间码 + 正弦音频),不含任何受版权保护的内容。
# 用法: make_media.sh <输出目录>      FONT=字体文件路径(可选;默认找 DejaVu Sans)
set -eu
OUT="${1:?usage: make_media.sh <outdir>}"
FONT="${FONT:-}"
if [ -z "$FONT" ]; then
  for f in /usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf /usr/share/fonts/dejavu/DejaVuSans-Bold.ttf \
           /usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf; do
    [ -f "$f" ] && FONT="$f" && break
  done
fi
[ -f "$FONT" ] || { echo "找不到字体,请设置 FONT=..." >&2; exit 1; }

# mk <文件> <秒数> <色1> <色2> <标题> [副标题]
mk() {
  local file="$1" dur="$2" c0="$3" c1="$4" title="$5" sub="${6:-}"
  mkdir -p "$(dirname "$file")"
  [ -f "$file" ] && return 0
  local vf="drawtext=fontfile=$FONT:text='$title':fontsize=84:fontcolor=white:shadowcolor=black@0.5:shadowx=3:shadowy=3:x=(w-tw)/2:y=h*0.38"
  if [ -n "$sub" ]; then
    vf="$vf,drawtext=fontfile=$FONT:text='$sub':fontsize=40:fontcolor=white@0.85:x=(w-tw)/2:y=h*0.38+110"
  fi
  vf="$vf,drawtext=fontfile=$FONT:text='%{pts\\:hms}':fontsize=44:fontcolor=white@0.9:x=40:y=h-80"
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i "gradients=s=1280x720:r=24:d=$dur:c0=$c0:c1=$c1:x0=0:y0=0:x1=1280:y1=720:speed=0.015" \
    -f lavfi -i "sine=f=220:r=44100:d=$dur" \
    -vf "$vf" -c:v libx264 -preset veryfast -crf 27 -pix_fmt yuv420p -profile:v high -g 48 \
    -c:a aac -b:a 64k -ac 2 -shortest -movflags +faststart "$file"
}

PIDS=""
bg() { "$@" & PIDS="$PIDS $!"; }
bg mk "$OUT/星河漫游 (2021).mp4"            180 0x1b2a6b 0xe8744f "GALAXY DRIFT"  "2021"
bg mk "$OUT/夜航船 (2019).mp4"              150 0x0d3b4f 0xf2b46d "NIGHT FERRY"   "2019"
bg mk "$OUT/山海之间 (2023).mp4"            30  0x2d5a3d 0xa3c9a8 "BETWEEN MOUNTAIN AND SEA" "2023"
bg mk "$OUT/雾中列车 (2020).mp4"            30  0x3b3b58 0xb8a1c9 "TRAIN IN THE FOG" "2020"
bg mk "$OUT/海边的钢琴 (2022).mp4"          30  0x14527a 0xf7d6a0 "PIANO BY THE SEA" "2022"
bg mk "$OUT/北纬三十度 (2018).mp4"          30  0x7a2e2e 0xf0c987 "30 DEGREES NORTH" "2018"
bg mk "$OUT/小城故事/Season 1/小城故事 S01E01.mp4" 120 0x4a2c6b 0xf08a8a "LITTLE TOWN" "S01E01"
bg mk "$OUT/小城故事/Season 1/小城故事 S01E02.mp4" 120 0x4a2c6b 0xf0a58a "LITTLE TOWN" "S01E02"
bg mk "$OUT/小城故事/Season 1/小城故事 S01E03.mp4" 120 0x4a2c6b 0xf0c08a "LITTLE TOWN" "S01E03"
for p in $PIDS; do wait "$p" || exit 1; done
ls -la "$OUT"
