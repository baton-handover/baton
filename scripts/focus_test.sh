#!/bin/bash
# 오디오 포커스 점검(debug 빌드 전용). 정차 안내 TTS가 포커스를 요청·반납하는지, 다른 소리(통화 흉내)에 포커스를 잃으면 멈추는지,
# 통화 흉내 중에는 안내를 시작하지 않는지 본다. 결과는 logcat의 "PleosProbe" 줄. 차량 제어(set) API는 쓰지 않는다
set -uo pipefail
ADB="adb -s ${DEVICE:-emulator-5554}"
C=http://127.0.0.1:8088
$ADB forward tcp:8088 tcp:8088 >/dev/null
( while true; do curl -s -X POST "$C/browser/session?client=focus-test" -o /dev/null; sleep 1; done ) & HB=$!
trap 'kill $HB 2>/dev/null' EXIT
sleep 2
gear() { curl -sf -X PUT "$C/vehicle/gear?gear=$1" -o /dev/null; }
ft() { $ADB shell am broadcast --user 10 -a app.baton.FOCUS_TEST --es usage "$1" --el ms "$2" >/dev/null; }
show() { echo "--- $1"; $ADB logcat -d | grep "PleosProbe" | grep -E "P10 |FT |포커스" | sed 's/.*PleosProbe: //'; $ADB logcat -c; }

gear Park; sleep 2; $ADB logcat -c
echo "== 1 정상: 주차 → 안내 → 끝나면 반납"; gear Drive; sleep 3; gear Park; sleep 9; show 1
echo "== 2 상실: 안내 중 통화 흉내"; gear Drive; sleep 3; gear Park; sleep 1.5; ft call 3000; sleep 6; show 2
echo "== 3 거부: 통화 흉내 중 주차"; gear Drive; sleep 3; ft call 8000; sleep 1; gear Park; sleep 9; show 3
echo "== 4 내비 안내 흉내(바통 안내가 양보해 멈춤)"; gear Drive; sleep 3; gear Park; sleep 1.5; ft nav 3000; sleep 8; show 4
