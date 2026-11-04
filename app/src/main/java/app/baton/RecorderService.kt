package app.baton

import ai.pleos.playground.vehicle.Vehicle
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 프로세스에 하나뿐인 B1 기록기. 서비스가 만들고 화면 버튼이 같이 쓴다. */
object B1 {
    var recorder: SessionRecorder? = null
    /** 기어가 P를 벗어날 때 부른다. 화면·안내 음성을 멈추는 데 쓴다 */
    val onLeavePark = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
}

/**
 * B1 자동 시작 스파이크. 부팅 수신 또는 화면 실행 시 시작되어, 화면이 없어도 기어 변화를 기록한다.
 * 기어 구독과 SessionRecorder 소유는 여기 한 곳에만 둔다(중복 기록 방지).
 */
class RecorderService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var cpm: CarPropertyManager? = null
    private var p10: P10Probe? = null
    private var lastGear: Int? = null

    private fun slog(msg: String) {
        Log.i("PleosProbe", "S $msg")
        runCatching { File(filesDir, "probe_log.txt").appendText("${timeFmt.format(Date())} S $msg\n") }
    }

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            (value.value as? Int)?.let { g ->
                // 음성 중단을 기록 처리보다 먼저 한다(기록 처리가 앞서면 중단 요청이 0.7초 늦어졌다, 11/4 측정)
                if (g != SessionRecorder.GEAR_P && lastGear == SessionRecorder.GEAR_P) { slog("P 이탈 수신 gear=$g"); p10?.stopSpeech(); B1.onLeavePark.forEach { it() } }
                B1.recorder?.onGear(g)
                if (g == SessionRecorder.GEAR_P && lastGear != null && lastGear != g) p10?.onPark()
                lastGear = g
            }
        }
        override fun onErrorEvent(propId: Int, zone: Int) = slog("B1 기어 오류 이벤트 prop=$propId zone=$zone")
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("b1", "운행 기록", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "b1").setContentTitle("운행 기록 중").setSmallIcon(android.R.drawable.ic_menu_mylocation).build()
        runCatching { startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
            .onSuccess { slog("서비스 onCreate, 포그라운드 시작 성공") }
            .onFailure { slog("서비스 포그라운드 시작 실패: $it") }

        runCatching { vehicle = Vehicle(applicationContext).also { it.initialize() } }.onFailure { slog("Vehicle 초기화 예외: $it") }
        runCatching {
            car = Car.createCar(this)
            cpm = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        }.onFailure { slog("AAOS Car 연결 예외: $it") }

        val snapshot = VehicleSnapshot({ vehicle }, { cpm })
        val rec = SessionRecorder(filesDir, ::slog, { cb ->
            val odo = vehicle?.getOdometer()
            if (odo == null) cb(null)
            else odo.getCurrentDistanceDriven({ v: Float? -> cb(v) }, { e: Exception -> slog("B1 거리 조회 실패: ${e.message}"); cb(null) })
        }, snapshot::collect)
        B1.recorder = rec
        rec.purgeOldNotes()
        p10 = P10Probe(this, { vehicle }, ::slog)
        // Vehicle 서비스 연결(IPC)이 끝날 시간을 준 뒤 복구·구독
        main.postDelayed({
            rec.recover(runCatching { cpm?.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0) }.getOrNull())
            runCatching { cpm?.registerCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
                .onSuccess { slog("B1 기어 구독 결과: $it") }
                .onFailure { slog("B1 기어 구독 실패: $it") }
        }, 2500)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        slog("서비스 onStartCommand from=${intent?.getStringExtra("from") ?: "null(시스템 재시작)"} flags=$flags")
        return START_STICKY
    }

    override fun onDestroy() {
        slog("서비스 onDestroy")
        runCatching { cpm?.unregisterCallback(gearCallback) }
        p10?.release()
        runCatching { vehicle?.release() }
        runCatching { car?.disconnect() }
        B1.recorder = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
