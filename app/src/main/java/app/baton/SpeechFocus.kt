package app.baton

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * 음성 안내·음성 인식 전에 앱이 오디오 포커스를 요청한다.
 * Pleos TTS 서비스는 재생(AudioTrack, usage=MEDIA) 중에도 포커스를 잡지 않았다(11/6 에뮬레이터, 포커스 보유자 없음).
 * 정책(Pleos Connect 오디오 포커스 가이드): 요청이 거부되면 시작하지 않고, 포커스를 잃으면 즉시 멈춘다.
 * shortcut: LOSS_TRANSIENT_CAN_DUCK도 감쇄 대신 중지한다. TTS 서비스의 볼륨을 앱이 낮출 수 없어서이며, SDK가 볼륨 API를 내면 감쇄로 바꾼다
 */
class SpeechFocus(ctx: Context, usage: Int, gain: Int, private val name: String, private val log: (String) -> Unit, private val onLost: () -> Unit) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    @Volatile private var held = false
    private val req: AudioFocusRequest = AudioFocusRequest.Builder(gain)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setWillPauseWhenDucked(true)  // 시스템이 대신 감쇄하지 말고 CAN_DUCK을 앱에 알려 달라
        .setOnAudioFocusChangeListener { change ->
            log("$name 포커스 변경 $change")
            // 멈춘 뒤에도 잡고 있으면 상대가 끝날 때 포커스가 되돌아와 다음 요청과 엉킨다(11/6 확인). 잃으면 바로 반납한다
            if (change < 0) { held = false; am.abandonAudioFocusRequest(req); onLost() }
        }
        .build()

    /** 허가되면 true. 거부(R)·지연이면 false이고 호출한 쪽은 음성을 시작하지 않는다 */
    fun acquire(): Boolean {
        val r = am.requestAudioFocus(req)
        held = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        log("$name 포커스 요청 결과 $r")
        return held
    }

    fun release() {
        if (!held) return
        held = false
        am.abandonAudioFocusRequest(req)
        log("$name 포커스 반납")
    }

    companion object {
        /** 포커스를 잃으면 부른다. 서비스와 화면이 각자의 TTS 중지를 등록한다 */
        val ttsStoppers = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
        @Volatile private var shared: SpeechFocus? = null

        // 안내 음성 포커스는 앱 전체에 하나. Pleos TTS 서비스는 앱 단위로만 구분해, 화면에서 낸 음성의 끝 신호가
        // 서비스 쪽 TTS 리스너로 가기도 한다(11/6 확인). 어느 리스너가 받아도 같은 포커스를 반납하게 한다.
        // 용도는 실제 재생 용도(MEDIA)에 맞추고, 짧은 안내라 다른 소리는 잠시 멈췄다가 돌아오게 한다
        fun forSpeech(ctx: Context): SpeechFocus = shared ?: synchronized(this) {
            shared ?: SpeechFocus(ctx.applicationContext, AudioAttributes.USAGE_MEDIA, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, "TTS",
                { android.util.Log.i("PleosProbe", "TTS $it") }) { ttsStoppers.forEach { it() } }.also { shared = it }
        }
        // 음성 인식: 듣는 동안 다른 소리를 멈춘다(음성 명령 용도)
        fun forListen(ctx: Context, log: (String) -> Unit, onLost: () -> Unit) =
            SpeechFocus(ctx, AudioAttributes.USAGE_ASSISTANT, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, "STT", log, onLost)
    }
}
