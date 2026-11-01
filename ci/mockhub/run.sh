#!/usr/bin/env bash
# 在 CI 里启动"模拟家庭中心":先用 ffmpeg 生成演示影片,再按 fixtures/media-map.json 命名成 <id>.mp4,最后在后台启动 server.py。
# 需要 ffmpeg 和 python3。日志写到 $MOCK_WORK/server.log。
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
WORK="${MOCK_WORK:-/tmp/mockhub}"
mkdir -p "$WORK/src" "$WORK/media"
bash "$HERE/make_media.sh" "$WORK/src" > "$WORK/make_media.log" 2>&1 || { cat "$WORK/make_media.log"; exit 1; }
python3 - "$HERE" "$WORK" <<'PY'
import json, shutil, sys
here, work = sys.argv[1], sys.argv[2]
for iid, rel in json.load(open(here + "/fixtures/media-map.json", encoding="utf-8")).items():
    shutil.copyfile("%s/src/%s" % (work, rel), "%s/media/%s.mp4" % (work, iid))
PY
nohup python3 "$HERE/server.py" --port 8080 --media "$WORK/media" > "$WORK/server.log" 2>&1 &
echo $! > "$WORK/pid"
for _ in $(seq 1 20); do
  curl -fs http://127.0.0.1:8080/api/tv/hello >/dev/null 2>&1 && { echo "mock hub up"; exit 0; }
  sleep 0.5
done
echo "mock hub failed to start"; cat "$WORK/server.log"; exit 1
