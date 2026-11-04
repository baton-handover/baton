package app.baton

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import android.app.Activity
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pleos 공모전 사전 점검 앱.
 * 확인 대상
 *  P1 일반 앱 권한 부여 상태 (CAR_SAFETY, PRIVATE_CAR_SAFETY 등)
 *  P2 Safety.getTelltalesInfo (공개 권한) 결과와 DRIVER_ATTENTION 노출 여부
 *  P3 Safety.registerTelltales (비공개 권한) 성공/실패
 *  P4 Odometer.registerTripCurrentDrivingTime (공개 권한) 콜백
 *  P5 기어: AAOS GEAR_SELECTION, Pleos Gear(deprecated) 비교, 차속 PERF_VEHICLE_SPEED
 *  P6 NaviHelper 경로 요청 후 onTBTInfo 에 TURN_RESTAREA / TURN_ENTER_SERVICEAREA 가 오는지
 *  B1 운행 세션 기록 스파이크 (SessionRecorder): 기어 + 누적 주행거리
 *  P10 E안 화면 진입: 서비스의 P 복귀 처리(RecorderService/P10Probe)로 열린 화면 기록, STT 호출
 *  P9 E안 Vehicle 읽기 (P9Probe): 연료·배터리·타이어·도어·경고등 재조회·방향지시등/조향/브레이크
 * 모든 결과는 Logcat 태그 PleosProbe 와 앱 내부 파일 probe_log.txt 에 남는다.
 * 차량 제어(set) API는 호출하지 않는다.
 */
class MainActivity : Activity() {

    private val tag = "PleosProbe"
    private val main = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView
    private lateinit var logFile: File
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var car: Car? = null
    private var cpm: CarPropertyManager? = null

    private val p9 = P9Probe({ vehicle }, { cpm }, ::log)

    private val restTurnTypes = setOf(TurnType.TURN_RESTAREA, TurnType.TURN_ENTER_SERVICEAREA)

    // ---------- 로그 ----------
    private fun log(msg: String) {
        val line = "${timeFmt.format(Date())} $msg"
        Log.i(tag, msg)
        runCatching { logFile.appendText(line + "\n") }
        main.post {
            logView.append(line + "\n")
        }
    }

    // ---------- 화면 ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logFile = File(filesDir, "probe_log.txt")

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, row: LinearLayout, action: () -> Unit) {
            row.addView(Button(this).apply { text = label; textSize = 18f; setOnClickListener { action() } })
        }
        btn("P1 권한", row1) { probePermissions() }
        btn("P2 경고등 조회", row1) { probeTelltaleGet() }
        btn("P3 경고등 구독", row1) { probeTelltaleRegister() }
        btn("P4 트립 주행시간", row1) { probeTripTime() }
        btn("P5 기어·차속", row2) { probeGearAndSpeed() }
        btn("P6 경로 요청(대전역)", row2) { probeRoute() }
        btn("P6 TBT 다시 조회", row2) { navi?.getTBTInfo() }
        btn("로그 지우기", row2) { logView.text = ""; runCatching { logFile.writeText("") } }
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btn("P7 경유지 추가(기흥휴게소)", row3) { probeAddWaypoint() }
        btn("P7 경유지 조회", row3) { navi?.getWaypointInfo() }
        btn("P7 경유지 삭제", row3) { navi?.removeWaypoint(WaypointIndex.ALL); log("P7 removeWaypoint(ALL) 호출") }
        btn("P8 주행거리 조회", row3) { probeDistance() }
        val row4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btn("B1 기록 보기", row4) { showB1() }
        btn("B1 목적: 업무", row4) { B1.recorder?.classifyLatest("업무") ?: log("B1 서비스 없음") }
        btn("B1 목적: 개인", row4) { B1.recorder?.classifyLatest("개인") ?: log("B1 서비스 없음") }
        btn("B1 기록 초기화", row4) { B1.recorder?.reset() ?: log("B1 서비스 없음") }
        btn("E 메모: 충전 필요", row4) { B1.recorder?.addNote("운전자A", listOf("충전 필요"), null) ?: log("B1 서비스 없음") }
        val row5 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btn("P9 차량 상태 조회", row5) { p9.runAll() }
        btn("P9-5 경고등 다시 조회", row5) { p9.telltaleSnapshot() }
        btn("P10 STT", row5) { probeStt() }

        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 14f }
        val scroll = ScrollView(this).apply { addView(logView) }
        root.addView(row1)
        root.addView(row2)
        root.addView(row3)
        root.addView(row4)
        root.addView(row5)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        log("=== Pleos Probe 시작 === from=${intent?.getStringExtra("from")}")
        // B1 기록은 RecorderService가 맡는다. 화면은 서비스를 깨우기만 한다
        startForegroundService(android.content.Intent(this, RecorderService::class.java).putExtra("from", "activity"))
        requestPermissions(arrayOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_ENERGY",
            "android.permission.POST_NOTIFICATIONS", "android.permission.RECORD_AUDIO"), 1)
        initSdks()
        // 서비스 연결(IPC)이 끝날 시간을 준 뒤 자동 점검 1회 실행
        main.postDelayed({
            probePermissions(); probeTelltaleGet(); probeTelltaleRegister(); probeTripTime()
            probeGearAndSpeed()
        }, 2500)
    }

    private fun initSdks() {
        runCatching {
            vehicle = Vehicle(applicationContext).also { it.initialize() }
            log("Vehicle.initialize 호출")
        }.onFailure { log("Vehicle 초기화 예외: $it") }

        runCatching {
            navi = NaviHelper(applicationContext).also { nh ->
                nh.initialize(
                    {
                        log("NaviHelper 연결 성공")
                        nh.addListener(naviListener)
                    },
                    { t -> log("NaviHelper 연결 실패: $t") }
                )
            }
        }.onFailure { log("NaviHelper 초기화 예외: $it") }

        runCatching {
            car = Car.createCar(this)
            cpm = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            log("AAOS Car 연결: ${car != null}, CarPropertyManager: ${cpm != null}")
        }.onFailure { log("AAOS Car 연결 예외: $it") }
    }

    // ---------- P1 ----------
    private fun probePermissions() {
        listOf(
            "pleos.car.permission.CAR_SAFETY",
            "pleos.car.permission.PRIVATE_CAR_SAFETY",
            "pleos.car.permission.CAR_DRIVE",
            "pleos.car.permission.NAVI_ROUTE",
            "pleos.car.permission.NAVI_ROUTE_SEARCH",
            "pleos.car.permission.CAR_ENERGY",
            "pleos.car.permission.CAR_DOORS",
            "pleos.car.permission.CAR_LIGHTS",
            "pleos.car.permission.CAR_INFO",
            "android.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_INFO",
            "android.car.permission.CAR_TIRES",
            "android.car.permission.CAR_POWERTRAIN",
            "android.car.permission.CAR_SPEED",
        ).forEach { p ->
            val granted = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            val defined = runCatching { packageManager.getPermissionInfo(p, 0) }.getOrNull()
            val level = defined?.protection?.let { "protection=$it" } ?: "정의 없음"
            log("P1 $p -> ${if (granted) "GRANTED" else "DENIED"} ($level)")
        }
    }

    // ---------- P2 ----------
    private fun probeTelltaleGet() {
        val safety = vehicle?.getSafety() ?: return log("P2 Vehicle 없음")
        safety.getTelltalesInfo(
            { t: Telltales -> dumpTelltales("P2 get", t) },
            { e: Exception -> log("P2 getTelltalesInfo 실패: ${e.javaClass.simpleName} ${e.message}") }
        )
    }

    // ---------- P3 ----------
    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(telltales: Telltales?) {
            if (telltales == null) log("P3 콜백: null") else dumpTelltales("P3 콜백", telltales)
        }
        override fun onFailed(e: Exception) = log("P3 registerTelltales 실패: ${e.javaClass.simpleName} ${e.message}")
    }

    private fun probeTelltaleRegister() {
        val safety = vehicle?.getSafety() ?: return log("P3 Vehicle 없음")
        runCatching { safety.registerTelltales(telltaleListener); log("P3 registerTelltales 호출 완료 (콜백 대기)") }
            .onFailure { log("P3 registerTelltales 예외: $it") }
    }

    private fun dumpTelltales(prefix: String, t: Telltales) {
        val list = t.telltales
        log("$prefix 경고등 ${list.size}개")
        list.forEach { d ->
            val mark = if (d.type == TelltaleType.DRIVER_ATTENTION) "  <<< DRIVER_ATTENTION" else ""
            log("  ${d.type} state=${d.state} color=${d.color} prio=${d.priority} count=${d.warningCount}$mark")
        }
        if (list.none { it.type == TelltaleType.DRIVER_ATTENTION }) log("$prefix DRIVER_ATTENTION 항목 없음")
    }

    // ---------- P4 ----------
    private val drivingTimeListener = object : DrivingTimeListener {
        override fun onDrivingTimeUpdated(value: Int?) = log("P4 트립 현재 주행시간 콜백: $value")
        override fun onFailed(e: Exception) = log("P4 registerTripCurrentDrivingTime 실패: ${e.javaClass.simpleName} ${e.message}")
    }

    private fun probeTripTime() {
        val odo = vehicle?.getOdometer() ?: return log("P4 Vehicle 없음")
        odo.getTripCurrentDrivingTime(
            { v: Int? -> log("P4 get 트립 현재 주행시간: $v") },
            { e: Exception -> log("P4 getTripCurrentDrivingTime 실패: ${e.javaClass.simpleName} ${e.message}") }
        )
        runCatching { odo.registerTripCurrentDrivingTime(drivingTimeListener) }
            .onFailure { log("P4 register 예외: $it") }
    }

    private fun showB1() {
        log("B1 서비스 실행 중: ${B1.recorder != null}")
        File(filesDir, "b1_records.jsonl").takeIf { it.exists() }?.readLines()?.forEach { log("B1 | $it") } ?: log("B1 기록 없음")
    }

    // ---------- P8 ----------
    private fun probeDistance() {
        val odo = vehicle?.getOdometer() ?: return log("P8 Vehicle 없음")
        odo.checkCurrentDistanceDrivenCapabilities(
            { c -> log("P8 누적 주행거리 capability: $c") },
            { e: Exception -> log("P8 capability 실패: ${e.javaClass.simpleName} ${e.message}") }
        )
        odo.getCurrentDistanceDriven(
            { v: Float? -> log("P8 get 누적 주행거리: $v") },
            { e: Exception -> log("P8 getCurrentDistanceDriven 실패: ${e.javaClass.simpleName} ${e.message}") }
        )
        odo.getTripCurrentDrivingDistance(
            { v: Float? -> log("P8 get 트립 현재 주행거리: $v") },
            { e: Exception -> log("P8 getTripCurrentDrivingDistance 실패: ${e.javaClass.simpleName} ${e.message}") }
        )
    }

    // ---------- P5 ----------
    private val carCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val name = if (value.propertyId == VehiclePropertyIds.GEAR_SELECTION) "GEAR_SELECTION" else "PERF_VEHICLE_SPEED"
            if (value.propertyId != VehiclePropertyIds.GEAR_SELECTION) return  // 차속 콜백은 B1 검증 로그를 덮으므로 기록하지 않는다
            log("P5 AAOS 콜백 $name = ${value.value}")
        }
        override fun onErrorEvent(propId: Int, zone: Int) = log("P5 AAOS 오류 이벤트 prop=$propId zone=$zone")
    }

    private val pleosGearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = log("P5 Pleos Gear(deprecated) 콜백: $gear")
        override fun onFailed(e: Exception) = log("P5 Pleos Gear 실패: ${e.javaClass.simpleName} ${e.message}")
    }

    @Suppress("DEPRECATION")
    private fun probeGearAndSpeed() {
        val m = cpm
        if (m == null) {
            log("P5 CarPropertyManager 없음")
        } else {
            runCatching { log("P5 AAOS GEAR_SELECTION = ${m.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0)} (4=P, 8=D, 2=R, 1=N)") }
                .onFailure { log("P5 AAOS GEAR_SELECTION 실패: $it") }
            runCatching { m.registerCallback(carCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
                .onFailure { log("P5 GEAR_SELECTION 구독 실패: $it") }
            runCatching { log("P5 AAOS PERF_VEHICLE_SPEED = ${m.getFloatProperty(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)} m/s") }
                .onFailure { log("P5 AAOS PERF_VEHICLE_SPEED 실패: $it") }
            runCatching { m.registerCallback(carCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL) }
                .onFailure { log("P5 PERF_VEHICLE_SPEED 구독 실패: $it") }
        }
        val gear = vehicle?.getGear() ?: return log("P5 Vehicle 없음")
        gear.getCurrentGearSelectionState(
            { g: VehicleGear? -> log("P5 Pleos Gear(deprecated) get: $g") },
            { e: Exception -> log("P5 Pleos Gear get 실패: ${e.javaClass.simpleName} ${e.message}") }
        )
        runCatching { gear.registerGearSelection(pleosGearListener) }.onFailure { log("P5 Pleos Gear 구독 예외: $it") }
    }

    // ---------- P6 ----------
    private val naviListener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = log("P6 경로 탐색 시작: $routeStartInfo")
        override fun onRoutingCompleted() {
            log("P6 경로 탐색 완료 -> getTBTInfo 호출")
            navi?.getTBTInfo()
        }
        override fun onError(errorCode: NaviErrorCode) = log("P6 내비 오류: $errorCode")
        override fun onNavigationStatus(status: NavigationStatus) = log("P6 내비 상태: $status")
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            log("P6 TBT ${tbtInfoList.size}개 수신")
            tbtInfoList.forEach { t ->
                val mark = if (t.type in restTurnTypes) "  <<< 휴게소/졸음쉼터" else ""
                log("  ${t.type} dist=${t.distance} desc=${t.description}$mark")
            }
            if (tbtInfoList.none { it.type in restTurnTypes }) log("P6 이번 TBT에는 휴게소/쉼터 없음")
        }
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) =
            log("P7 경유지 변경: index=${waypointChangedInfo.index} cmd=${waypointChangedInfo.cmdType} route=${waypointChangedInfo.routeStartInfo}")
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            log("P7 경유지 ${waypointInfoList.size}개")
            waypointInfoList.forEach { w ->
                log("  ${w.name} lat=${w.latitude} lon=${w.longitude} 남은거리=${w.remainDistance} 남은시간=${w.remainTime}")
            }
        }
        override fun onRouteCancelled() = log("P7 경로 취소됨")
    }

    // ---------- P7 ----------
    private fun probeAddWaypoint() {
        val nh = navi ?: return log("P7 NaviHelper 없음")
        // OpenStreetMap 기흥휴게소(경부선, 하행 추정) 좌표. 방향은 경로 거리 변화로 간접 확인
        runCatching {
            nh.addWaypoint(
                RequestWaypointInfo(
                    latitude = 37.2351768,
                    longitude = 127.1050929,
                    poiName = "기흥휴게소",
                    waypointIndex = WaypointIndex.FIRST,
                    poiId = "0",
                    address = "경기도 용인시 기흥구 공세동",
                    poiSubId = "0",
                    poiProvider = "",
                )
            )
            log("P7 addWaypoint(기흥휴게소) 호출. 결과는 콜백으로 확인")
        }.onFailure { log("P7 addWaypoint 예외: $it") }
    }

    private fun probeRoute() {
        val nh = navi ?: return log("P6 NaviHelper 없음")
        // 고속도로 구간이 포함되도록 서울에서 먼 목적지(대전역)로 경로 요청
        runCatching {
            nh.requestRoute(
                RouteInfo(
                    latitude = 36.3326,
                    longitude = 127.4342,
                    displayLatitude = null,
                    displayLongitude = null,
                    poiName = "대전역",
                    poiId = "0",
                    address = "대전광역시 동구 중앙로 215",
                    poiSubId = "0",
                    routeOption = RouteOption.RECOMMENDED,
                )
            )
            log("P6 requestRoute(대전역) 호출. 결과는 콜백으로 확인")
        }.onFailure { log("P6 requestRoute 예외: $it") }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        log("P10 화면 열림(onNewIntent) from=${intent?.getStringExtra("from")}")
    }

    override fun onResume() {
        super.onResume()
        log("화면 onResume from=${intent?.getStringExtra("from")}")
    }

    // ---------- P10 STT ----------
    private var stt: android.speech.SpeechRecognizer? = null

    private var pleosStt: ai.pleos.playground.stt.SpeechToText? = null

    /** Pleos SpeechToText SDK(ON_DEVICE). 에뮬레이터 마이크 입력이 없으면 준비·오류 단계까지만 확인된다. */
    private fun probePleosStt() {
        runCatching {
            pleosStt?.release()
            pleosStt = ai.pleos.playground.stt.SpeechToText(this, ai.pleos.playground.stt.constant.Mode.ON_DEVICE).also { s ->
                s.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
                    override fun onSuccess() {
                        log("P10 Pleos STT 초기화 성공 -> request")
                        s.addListener(object : ai.pleos.playground.stt.listener.ResultListener {
                            override fun onUpdated(text: String, isFinal: Boolean) = log("P10 Pleos STT 결과(final=$isFinal): $text")
                            override fun onUpdatedEpdData(a: Long, b: Long) {}
                            override fun onStartedRecognition() = log("P10 Pleos STT 인식 시작")
                            override fun onEndedRecognition() = log("P10 Pleos STT 인식 끝")
                            override fun onError(e: ai.pleos.playground.stt.constant.ErrorType) = log("P10 Pleos STT 오류 $e")
                            override fun onReady() = log("P10 Pleos STT 준비됨(말하기 대기)")
                            override fun onUpdatedRms(rms: Double) {}
                        })
                        runCatching { s.setLocale(Locale.KOREA); s.request() }.onFailure { log("P10 Pleos STT request 예외: $it") }
                    }
                    override fun onFailure(e: ai.pleos.playground.stt.constant.ErrorType) = log("P10 Pleos STT 초기화 실패 $e")
                })
            }
        }.onFailure { log("P10 Pleos STT 예외: $it") }
    }

    private fun probeStt() {
        probePleosStt()
        val ok = android.speech.SpeechRecognizer.isRecognitionAvailable(this)
        log("P10 STT isRecognitionAvailable=$ok RECORD_AUDIO=${checkSelfPermission("android.permission.RECORD_AUDIO") == PackageManager.PERMISSION_GRANTED}")
        if (!ok) return
        runCatching {
            stt?.destroy()
            stt = android.speech.SpeechRecognizer.createSpeechRecognizer(this).also { r ->
                r.setRecognitionListener(object : android.speech.RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = log("P10 STT 준비됨(말하기 대기)")
                    override fun onBeginningOfSpeech() = log("P10 STT 음성 시작")
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() = log("P10 STT 음성 끝")
                    override fun onError(error: Int) = log("P10 STT 오류 code=$error")
                    override fun onResults(results: Bundle?) =
                        log("P10 STT 결과: ${results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)}")
                    override fun onPartialResults(partialResults: Bundle?) =
                        log("P10 STT 부분 결과: ${partialResults?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)}")
                    override fun onEvent(eventType: Int, params: Bundle?) = log("P10 STT 이벤트 $eventType")
                })
                r.startListening(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                    .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
                log("P10 STT startListening 호출")
            }
        }.onFailure { log("P10 STT 예외: $it") }
    }

    override fun onDestroy() {
        runCatching { stt?.destroy() }
        runCatching { pleosStt?.release() }
        runCatching { vehicle?.getSafety()?.unregisterTelltales(telltaleListener) }
        runCatching { vehicle?.getOdometer()?.unregisterTripCurrentDrivingTime(drivingTimeListener) }
        @Suppress("DEPRECATION")
        runCatching { vehicle?.getGear()?.unregisterGearSelection(pleosGearListener) }
        runCatching { cpm?.unregisterCallback(carCallback) }
        runCatching { navi?.removeListener(naviListener); navi?.release() }
        runCatching { vehicle?.release() }
        runCatching { car?.disconnect() }
        super.onDestroy()
    }
}
