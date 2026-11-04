package com.example.pleosprobe

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale
import java.io.File

/**
 * P10 E안 화면 진입 경로 점검. 서비스가 기어 P 복귀를 받았을 때(앱 화면은 뒤에 있음)
 *  - 경고등 조회(getTelltalesInfo)가 서비스에서도 되는지
 *  - TTS 한 문장이 나오는지 (Pleos TextToSpeech SDK, ON_DEVICE. Android 표준 TextToSpeech는 엔진을 못 찾아 status=-1)
 *  - 화면을 띄우는 세 방법을 P 복귀마다 하나씩: 1 startActivity 직접, 2 전체 화면 알림, 3 헤드업 알림
 * 시험 순번은 files/p10_trial 에 저장한다. 차량 제어(set) API는 호출하지 않는다.
 */
private const val SCREEN_TRIALS = false

class P10Probe(
    private val ctx: Context,
    private val vehicle: () -> Vehicle?,
    private val log: (String) -> Unit,
) {
    private val trialFile = File(ctx.filesDir, "p10_trial")
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        nm.createNotificationChannel(NotificationChannel("p10", "정차 알림 점검", NotificationManager.IMPORTANCE_HIGH))
        log("P10 알림 권한 허용=${nm.areNotificationsEnabled()} 전체화면 알림 허용=${nm.canUseFullScreenIntent()}")
        runCatching {
            tts = TextToSpeech(ctx, Mode.ON_DEVICE).also { t ->
                // addEventListener는 initialize 성공 뒤에 해야 한다(먼저 부르면 SDK 내부 lateinit 예외)
                t.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        ttsReady = true
                        t.addEventListener(object : EventListener {
                            override fun onStart() = log("P10 TTS 발화 시작")
                            override fun onReady() = log("P10 TTS onReady")
                            override fun onDone() = log("P10 TTS 발화 끝")
                            override fun onError(e: ErrorType) = log("P10 TTS 오류 $e")
                            override fun onStop() = log("P10 TTS 중지")
                            override fun onUpdatedRms(rms: Double) {}
                        })
                        runCatching { t.setLocale(Locale.KOREA) }
                        log("P10 Pleos TTS 초기화 성공")
                    }
                    override fun onFailure(e: ErrorType) = log("P10 Pleos TTS 초기화 실패 $e")
                })
            }
        }.onFailure { log("P10 Pleos TTS 생성 예외: $it") }
    }

    fun onPark() {
        val trial = (runCatching { trialFile.readText().trim().toInt() }.getOrNull() ?: 0) % 3 + 1
        trialFile.writeText(trial.toString())
        log("P10 P 복귀 수신(서비스). 시험 $trial")

        vehicle()?.getSafety()?.getTelltalesInfo(
            { t: Telltales -> log("P10 서비스에서 경고등 조회 성공: ${t.telltales.filter { it.state.toString() != "OFF" }.map { it.type }}") },
            { e: Exception -> log("P10 서비스에서 경고등 조회 실패: ${e.javaClass.simpleName} ${e.message}") }
        ) ?: log("P10 Vehicle 없음")

        if (ttsReady) {
            runCatching { tts?.speak("운행이 끝났습니다. 다음 운전자에게 남길 말이 있으면 바통을 열어 주세요.", true) }
                .onSuccess { log("P10 TTS speak 호출") }.onFailure { log("P10 TTS speak 예외: $it") }
        } else log("P10 TTS 준비 안 됨")

        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("from", "p10-trial-$trial")
        // P10 시험은 끝났다(점검결과 문서 P10 절). 제품 화면(HandoverActivity)을 덮지 않도록 화면 띄우기 시험은 끈다
        if (!SCREEN_TRIALS) return
        when (trial) {
            1 -> runCatching { ctx.startActivity(open) }
                .onSuccess { log("P10 시험1 startActivity 호출 예외 없음 (실제 표시는 화면 로그로 확인)") }
                .onFailure { log("P10 시험1 startActivity 예외: $it") }
            2 -> notify(open, trial, fullScreen = true)
            3 -> notify(open, trial, fullScreen = false)
        }
    }

    private fun notify(open: Intent, trial: Int, fullScreen: Boolean) {
        val pi = PendingIntent.getActivity(ctx, trial, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(ctx, "p10")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("운행이 끝났습니다")
            .setContentText("다음 운전자에게 남길 말이 있나요?")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(pi)
            .setAutoCancel(true)
        if (fullScreen) b.setFullScreenIntent(pi, true)
        runCatching { nm.notify(100 + trial, b.build()) }
            .onSuccess { log("P10 시험$trial ${if (fullScreen) "전체 화면" else "헤드업"} 알림 게시") }
            .onFailure { log("P10 시험$trial 알림 예외: $it") }
    }

    fun stopSpeech() {
        log("P10 P 이탈: TTS 중지 요청"); runCatching { tts?.stop() }.onFailure { log("P10 TTS 중지 요청 실패: $it") }
    }

    fun release() {
        runCatching { tts?.release() }
    }
}
