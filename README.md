# pleos-probe

제품 화면: 런처의 "바통"(HandoverActivity) — 탑승 브리핑 / 운행 목록 / 하차 메모. 점검 화면은 "Pleos Probe"(MainActivity).

시연 시나리오: `scripts/demo_scenario.sh`(단계마다 Enter) 또는 `scripts/demo_scenario.sh --auto 캡처폴더`(자동 실행·캡처). 앱의 운행 기록을 지우고 시작한다.

Pleos 공모전 사전 점검 앱. 차량 제어(set) API는 호출하지 않는다.

| 버튼 | 확인 내용 |
|---|---|
| P1 권한 | CAR_SAFETY, PRIVATE_CAR_SAFETY, CAR_DRIVE, NAVI_*, AAOS CAR_POWERTRAIN/CAR_SPEED 부여 상태 |
| P2 경고등 조회 | Safety.getTelltalesInfo (공개 권한), DRIVER_ATTENTION 노출 여부 |
| P3 경고등 구독 | Safety.registerTelltales (PRIVATE_CAR_SAFETY) 성공/실패 |
| P4 트립 주행시간 | Odometer.registerTripCurrentDrivingTime (CAR_DRIVE) |
| P5 기어·차속 | AAOS GEAR_SELECTION, PERF_VEHICLE_SPEED, Pleos Gear(deprecated) 비교 |
| P6 경로 요청 | NaviHelper.requestRoute(대전역) 후 onTBTInfo에 TURN_RESTAREA / TURN_ENTER_SERVICEAREA 수신 여부 |
| P7 경유지 | addWaypoint(기흥휴게소) / getWaypointInfo / removeWaypoint(ALL), onWaypointChanged 수신 여부. P6 경로 요청 뒤에 실행 |
| P8 주행거리 | Odometer 누적 주행거리 capability·조회, 트립 현재 주행거리 조회 (조회만) |
| B1 운행 기록 | SessionRecorder: 기어 P 이탈~복귀를 운행으로, 누적 주행거리 차이를 거리로 기록. 재시작 복구·누락 구간 표시·정차 후 목적 분류. 상태 files/b1_state.json, 기록 files/b1_records.jsonl |
| B2 자동 시작 | RecorderService(포그라운드, START_STICKY)가 기록을 맡고 BootReceiver가 BOOT_COMPLETED·USER_UNLOCKED·MY_PACKAGE_REPLACED에서 서비스를 시작. 화면은 서비스를 깨우기만 함 |
| E 스냅샷 | VehicleSnapshot: 운행 시작·종료에 SOC·주행가능거리·연료%·경고등·운전석 도어를 읽어 trip 레코드에 startSnap/endSnap/delta로 남김. 실패·시간 초과 항목은 null + errors |
| E 메모: 충전 필요 | 정차(P) 중 최근 운행에 note 레코드(운전자, 선택지, 음성 텍스트) 추가. 시험용 고정 값 |
| P9 차량 상태 조회 | P9Probe: 연료%·배터리 SOC/용량·충전 상태, AAOS 연료/배터리 잔량·주행가능거리, 타이어 저압 임계값(공개)·실제 공기압(private), 도어 위치, 방향지시등·조향각·브레이크 조회 + 구독 |
| P9-5 경고등 다시 조회 | getTelltalesInfo(공개 권한)를 다시 불러 직전 조회 대비 새로 켜짐·꺼짐 계산 |
| P10 (자동) | 서비스가 기어 P 복귀를 받으면 경고등 조회 + Pleos TTS 한 문장 + 화면 띄우기 시험(1 startActivity / 2 전체 화면 알림 / 3 헤드업 알림, 순번 files/p10_trial) |
| P10 STT | Pleos SpeechToText(ON_DEVICE) 초기화·인식 요청, Android SpeechRecognizer 비교 |

로그: Logcat 태그 `PleosProbe`, 앱 내부 파일 `files/probe_log.txt`

```
adb logcat -s PleosProbe
adb exec-out run-as app.baton cat files/probe_log.txt > probe_log.txt
```
