# 바통

여러 사람이 공용 프로필로 함께 쓰는 Pleos 차량에서, 운행 전후의 주행거리·배터리·주행가능거리를 차량에서 직접 읽어 하차 메모와 함께 기록하고, 다음 운전자가 정차 중 같은 차량 화면에서 직전 운행의 인계 내용을 확인하는 차량용 앱입니다.

## APK 위치

`apk/바통_바통_release.apk` (release 빌드, 패키지 `app.baton`)

## 테스트 환경

- Pleos Connect v2.1.0 에뮬레이터(AAOS, AVD `Pleos_Connect_v2.1.0`). 앱은 Android 사용자 10(운전자 프로필)에서 실행됩니다
- Pleos Playground SDK: Vehicle 2.3.2, TextToSpeech 3.3.1, SpeechToText 3.3.1
- 호스트 도구: adb(platform-tools). 시연 스크립트는 bash, curl, python3가 필요합니다. 빌드에는 JDK 17과 Android SDK 35가 필요합니다
- 차량 값은 에뮬레이터 Virtual Console(`adb forward tcp:8088 tcp:8088` 후 http://127.0.0.1:8088)로 바꿉니다. 앱은 차량 값을 읽기만 하고 차량 제어(set) API는 호출하지 않습니다

## 실행 방법

1. 에뮬레이터를 켜고 `adb devices`로 장치 이름(예: `emulator-5554`)을 확인한 뒤 APK를 설치합니다. 장치가 여러 대면 `-s 장치이름`을 붙입니다.

   ```
   adb install apk/바통_바통_release.apk
   ```

2. 런처에서 "바통"을 엽니다(명령으로는 `adb shell am start --user 10 -n app.baton/.HandoverActivity`). 처음 한 번 자동차 정보(주행가능거리), 알림(기록 서비스 표시), 마이크(음성 메모) 권한을 허용합니다. 이후에는 차량이 켜질 때 화면을 열지 않아도 기록 서비스가 시작됩니다.
3. Virtual Console에서 기어를 D로 바꾸고 주행거리·배터리 값을 바꾼 뒤 P로 돌아오면 한 운행이 기록됩니다. 주행 중에는 플랫폼이 앱 화면을 가립니다.
4. 정차 중 바통을 열면 다음 화면을 볼 수 있습니다.
   - 하차 메모: 방금 끝난 운행에 운전자를 선택하고 상태 선택지를 남깁니다
   - 탑승 브리핑: 직전 운행 종료 시점의 배터리·주행가능거리와 이전 운전자 메모(경고등은 조회될 때만)
   - 운행 목록: 이번 달 운행과 월 요약 QR

## 시연 재현

`scripts/demo_scenario.sh`는 Virtual Console을 HTTP로 조작해 시연 장면(운행 3건, 내용 있는 메모 2건과 "남길 말 없음" 응답 1건, 운행 목록과 QR)을 처음부터 다시 만듭니다. 앱 데이터를 지우므로 기존 기록이 필요 없는 시험 환경에서 실행합니다. 앱은 사용자 10을 쓰고, 장치가 기본값 `emulator-5554`와 다르면 `DEVICE=장치이름`으로 지정합니다. 영상 합성(`make_video.py`)은 선택 절차로, ASS 자막을 지원하는 ffmpeg와 한글 폰트가 필요합니다(스크립트는 macOS의 Apple SD Gothic Neo 경로를 씁니다).

```
scripts/demo_scenario.sh                 # 단계마다 Enter, 메모는 화면에서 직접 누름
scripts/demo_scenario.sh --auto OUT      # 자동 진행, 단계별 캡처를 OUT에 저장
VIDEO=1 PAUSE=7 scripts/demo_scenario.sh --auto OUT && FFMPEG=ffmpeg python3 scripts/make_video.py OUT
                                         # 화면 녹화 + 자막 합성(OUT/baton_demo.mp4)
```

## 빌드

```
./gradlew assembleRelease   # app/build/outputs/apk/release/ (서명 키 파일이 없으면 서명 없는 APK가 되어 그대로 설치할 수 없음. 설치 시연에는 apk/ 폴더의 서명본을 씁니다)
./gradlew assembleDebug     # 개발용. 점검 화면 "Pleos Probe"가 함께 설치됨
```

Pleos SDK는 `settings.gradle.kts`의 Pleos Playground Maven 저장소에서 받습니다.

## 구조

| 파일 | 역할 |
|---|---|
| `RecorderService.kt` | 포그라운드 기록 서비스. 기어(AAOS GEAR_SELECTION) 변화로 운행 시작·종료를 판단하고, P 복귀 때 음성 안내, P 이탈 때 음성 중지 |
| `BootReceiver.kt` | 부팅·사용자 잠금 해제·앱 갱신 때 기록 서비스 시작 |
| `SessionRecorder.kt` | 운행 기록(누적 주행거리 차이), 재시작 복구, 기록 누락 구간, 메모 저장·삭제·30일 보관 |
| `VehicleSnapshot.kt` | 운행 시작·종료 때 배터리·주행가능거리·경고등·운전석 문 상태를 읽음. 읽지 못한 항목은 null과 사유 |
| `TripLog.kt` | 기록 파일을 화면용 운행·메모·누락 구간으로 읽음 |
| `HandoverActivity.kt` | 바통 화면 3개(탑승 브리핑, 운행 목록, 하차 메모)와 음성 안내·음성 메모 |
| `MainActivity.kt`, `P9Probe.kt`, `P10Probe.kt` | 개발 중 SDK 동작을 확인한 점검 화면. release APK에도 클래스는 남지만 런처와 외부 진입을 막았고 debug 빌드에서만 열 수 있음. `P10Probe`의 정차 음성 안내는 제품 기능으로 씀 |

기록은 앱 내부 저장소(`b1_records.jsonl`)에만 남고 서버로 보내지 않습니다. 메모 내용은 앱 로그에 남기지 않습니다.

## 권한

| 권한 | 용도 |
|---|---|
| `android.car.permission.CAR_POWERTRAIN` | 기어 상태 |
| `pleos.car.permission.CAR_DRIVE` | 누적 주행거리 |
| `pleos.car.permission.CAR_ENERGY`, `android.car.permission.CAR_ENERGY` | 배터리 잔량, 주행가능거리 |
| `pleos.car.permission.CAR_SAFETY` | 경고등 조회(공개 문서 기준). release는 공개 문서에 없는 PRIVATE_CAR_SAFETY를 선언하지 않음 |
| `pleos.car.permission.CAR_DOORS`, `CAR_INFO` | 운전석 문 상태, 차량 정보 |
| `pleos.car.permission.TTS_SERVICE`, `STT_SERVICE`, `android.permission.RECORD_AUDIO` | 정차 음성 안내, 음성 메모 |
| `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS` | 화면 없이 운행 기록 |

## 확인된 제약 (에뮬레이터 기준)

- 앱이 정차 시 화면을 스스로 띄우는 것은 백그라운드 실행 제한으로 막혀, 음성으로 안내하고 사용자가 엽니다
- 에뮬레이터에서 경고등 조회(`getTelltalesInfo`)는 공개 문서에 없는 PRIVATE_CAR_SAFETY를 요구합니다. App Market 심사는 승인된 권한만 허용하므로 release는 이 권한을 빼고, 브리핑에 "경고등: 확인 안 됨"으로 표시합니다(점검용 debug 빌드는 선언)
- 에뮬레이터 차량이 전기차라 내연기관 연료 잔량은 확인하지 못했습니다
- 음성 인식은 인식 대기까지 확인했습니다. 오디오 포커스 상실 처리는 아직 없습니다
- 실차의 전원 차단·절전 후 복귀는 확인하지 못했습니다

## 점검 화면 (debug 빌드)

개발 중 SDK와 플랫폼 동작을 확인하는 데 쓴 화면입니다. release 빌드에는 런처에 나오지 않습니다.

| 버튼 | 확인 내용 |
|---|---|
| P1 권한 | CAR_SAFETY, PRIVATE_CAR_SAFETY, CAR_DRIVE, NAVI_*, AAOS CAR_POWERTRAIN/CAR_SPEED 부여 상태 |
| P2 경고등 조회 | Safety.getTelltalesInfo(점검 환경에서 PRIVATE_CAR_SAFETY 필요), DRIVER_ATTENTION 노출 여부 |
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
| P9-5 경고등 다시 조회 | getTelltalesInfo(점검 환경에서 PRIVATE_CAR_SAFETY 필요)를 다시 불러 직전 조회 대비 새로 켜짐·꺼짐 계산 |
| P10 (자동) | 서비스가 기어 P 복귀를 받으면 경고등 조회 + Pleos TTS 한 문장 + 화면 띄우기 시험(1 startActivity / 2 전체 화면 알림 / 3 헤드업 알림, 순번 files/p10_trial) |
| P10 STT | Pleos SpeechToText(ON_DEVICE) 초기화·인식 요청, Android SpeechRecognizer 비교 |

로그: Logcat 태그 `PleosProbe`, 앱 내부 파일 `files/probe_log.txt`(debug 빌드에서 `adb exec-out run-as app.baton --user 10 cat files/probe_log.txt`)
