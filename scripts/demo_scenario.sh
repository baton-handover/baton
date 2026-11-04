#!/usr/bin/env bash
# 바통 시연 시나리오. Pleos 에뮬레이터 가상 콘솔을 HTTP로 조작해 공용차 인수인계 장면을 처음부터 다시 만든다.
# 차량 제어(set) API는 호출하지 않는다. 바꾸는 것은 가상 콘솔(시뮬레이터) 값뿐이다.
#
# 사용법
#   scripts/demo_scenario.sh            단계마다 Enter를 기다린다(녹화할 때. 메모는 화면에서 직접 누른다)
#   scripts/demo_scenario.sh --auto OUT 멈추지 않고 메모도 자동으로 눌러, 단계별 캡처를 OUT 폴더에 남긴다(검증용)
#
# 전제: AVD Pleos_Connect_v2.1.0 이 emulator-5554 로 떠 있고 바통 앱이 설치돼 있다.
# 주의: 앱의 운행 기록(b1_records.jsonl, b1_state.json)을 지우고 시작한다.
set -euo pipefail

ADB="adb -s ${DEVICE:-emulator-5554}"
PKG=com.example.pleosprobe
C=http://127.0.0.1:8088
AUTO=0; OUT=""
if [ "${1:-}" = "--auto" ]; then AUTO=1; OUT="${2:?캡처 폴더를 지정하세요}"; mkdir -p "$OUT"; fi

$ADB forward tcp:8088 tcp:8088 >/dev/null
# 가상 콘솔은 브라우저 세션 하트비트가 있어야 쓰기를 받는다
( while true; do curl -s -X POST "$C/browser/session?client=demo-script" -o /dev/null; sleep 1; done ) &
HB=$!
trap 'kill $HB 2>/dev/null' EXIT
sleep 2

post() { curl -sf -X POST "$C$1" -o /dev/null || { echo "콘솔 요청 실패: $1" >&2; exit 1; }; }
gear() { curl -sf -X PUT "$C/vehicle/gear?gear=$1" -o /dev/null || { echo "기어 변경 실패: $1" >&2; exit 1; }; }
soc() { post "/vehicle/ev-battery?field=soc&value=$1"; }
# 콘솔은 누적 주행거리 감소를 거부한다(traveled_distance_must_not_decrease). 시작 값 기준으로 더한다
BASE=0
odo() { post "/vehicle/odometer?traveled_distance_km=$(python3 -c "print(round($BASE + $1, 1))")"; }
range_km() { post "/vehicle/odometer?remaining_range_m=$(( $1 * 1000 ))"; }
tpms() { post "/vehicle/telltales/simulator?type=TPMS&state=$1&color=Yellow"; }

step=0
pause() {
  step=$((step + 1))
  echo "[$step] $1"
  if [ $AUTO = 1 ]; then
    sleep 4
    $ADB exec-out screencap -p > "$OUT/$(printf %02d $step)_$2.png"
  else
    read -r -p "    다음으로 넘어가려면 Enter " _
  fi
}

# 화면의 글자로 버튼을 찾아 누른다(--auto 전용)
tap() {
  $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local xy
  xy=$($ADB shell cat /sdcard/ui.xml | python3 -c '
import re, sys
x = sys.stdin.read()
m = re.search(r"text=\"" + re.escape(sys.argv[1]) + r"\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", x)
print("%d %d" % ((int(m[1]) + int(m[3])) // 2, (int(m[2]) + int(m[4])) // 2) if m else "")' "$1")
  [ -n "$xy" ] || { echo "화면에서 '$1'을 찾지 못함" >&2; exit 1; }
  $ADB shell input tap $xy
  sleep 1
}

memo() {  # memo 운전자 [선택지...]  — 화면에서 직접 누를 내용을 안내하거나(--auto면 직접 누른다)
  local who=$1; shift
  if [ $AUTO = 1 ]; then
    tap "$who"
    if [ $# -eq 0 ]; then tap "남길 말 없음"; else for i in "$@"; do tap "$i"; done; tap "저장"; fi
  else
    echo "    화면에서: $who 선택, ${*:-남길 말 없음} 후 저장"
  fi
}

echo "== 준비: 기록 초기화, 차량 값 맞추기"
gear Park; sleep 1
$ADB shell am force-stop --user 10 $PKG
$ADB shell run-as $PKG --user 10 rm -f files/b1_records.jsonl files/b1_state.json
BASE=$(curl -sf "$C/vehicle/state" | python3 -c "import json,sys; print(json.load(sys.stdin)['odometer']['traveled_distance_km'])")
soc 82; range_km 410; tpms Off
post "/vehicle/telltales/simulator?type=DoorOpen&state=Off&color=Red"
$ADB shell am start --user 10 -n $PKG/.HandoverActivity >/dev/null
sleep 5   # 기록 서비스가 기어를 구독할 시간

echo "== 장면 1: 어제 저녁, 운전자 B의 운행 (도중에 타이어 공기압 경고)"
gear Drive; sleep 3
odo 21; soc 73; range_km 365; sleep 2
tpms On; odo 42.6; soc 64; range_km 320; sleep 2
gear Park
pause "운행 종료. 바통이 하차 메모 화면을 보여 준다" "하차메모_B"
memo "운전자 B" "충전 필요"
pause "메모 저장 후 탑승 브리핑(다음 운전자가 볼 화면)" "탑승브리핑"

echo "== 장면 2: 오늘 오전, 운전자 A가 거래처 두 곳을 들른다"
gear Drive; sleep 3
odo 61; soc 58; range_km 290; sleep 2
gear Park
pause "첫 번째 거래처 도착" "거래처1"
memo "운전자 A"
gear Drive; sleep 3
odo 84.9; soc 50; range_km 250; sleep 2
gear Park
pause "회사 주차장 도착" "하차메모_A"
memo "운전자 A" "흠집 발견"
pause "운전자 A가 메모를 남긴 뒤의 탑승 브리핑" "탑승브리핑2"

echo "== 장면 3: 월말, 운행 목록과 QR"
if [ $AUTO = 1 ]; then tap "운행 목록"; else echo "    화면에서: 운행 목록 탭"; fi
pause "이번 달 운행 목록" "운행목록"
if [ $AUTO = 1 ]; then tap "QR로 월 요약 보기"; else echo "    화면에서: QR로 월 요약 보기"; fi
pause "월 요약 QR" "QR"
echo "끝"
