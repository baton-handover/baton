package com.example.pleosprobe

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.tts.TextToSpeech
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * E안 제품 화면 3개: 탑승 브리핑 / 운행 목록 / 하차 메모.
 * 정차(P) 중 사용을 전제로 한다. distractionOptimized를 주지 않아 주행 중에는 플랫폼이 화면을 막는다(B1 확인).
 * 앱이 화면을 스스로 띄울 수 없으므로(P10), 사용자가 열면 상태에 맞는 화면부터 보여 준다:
 * 종료 10분 안의 메모 없는 운행이면 하차 메모, 아니면 탑승 브리핑. 시간은 보조 조건일 뿐이라
 * 메모 화면은 대상 운행을 밝히고 "이 운행의 운전자가 아닙니다"로 빠져나갈 수 있게 하며, 운전자 선택 없이는 저장하지 않는다.
 * 텍스트 키보드 입력은 두지 않는다(선택지 + 음성만).
 */
private const val MEMO_FIRST_MS = 10 * 60 * 1000L

class HandoverActivity : Activity() {

    // 색·크기 토큰 (Pleos 기본 화면이 밝은 테마라 맞춘다)
    private val cBg = Color.parseColor("#F8FAFC")
    private val cCard = Color.WHITE
    private val cText = Color.parseColor("#0F172A")
    private val cMuted = Color.parseColor("#475569")
    private val cBorder = Color.parseColor("#E2E8F0")
    private val cPrimary = Color.parseColor("#1E293B")
    private val cWarn = Color.parseColor("#DC2626")
    private val cWarnBg = Color.parseColor("#FEF2F2")
    private val drivers = listOf("운전자 A", "운전자 B", "운전자 C")
    private val noteItems = listOf("흠집 발견", "실내 오염", "분실물 있음", "충전 필요", "이상 소음")
    private val telltaleNames = mapOf(
        "TPMS" to "타이어 공기압 경고", "DOOR_OPEN" to "문 열림", "LOW_EV_BATTERY" to "배터리 부족",
        "LOW_FUEL" to "연료 부족", "ENGINE" to "엔진 점검", "LOW_VOLT_BATTERY" to "보조 배터리 경고",
    )
    private val dateFmt = SimpleDateFormat("M월 d일 (E) HH:mm", Locale.KOREA)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.KOREA)

    private lateinit var body: LinearLayout
    private val tabs = mutableListOf<TextView>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var stt: SpeechToText? = null

    // 하차 메모 입력 상태
    private var selDriver: String? = null
    private val selItems = linkedSetOf<String>()
    private var voiceText: String? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 기록 서비스가 아직 없으면 깨운다(부팅 직후 화면부터 연 경우)
        startForegroundService(Intent(this, RecorderService::class.java).putExtra("from", "handover"))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg); setPadding(dp(32), dp(24), dp(32), dp(24)) }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("탑승 브리핑", "운행 목록", "하차 메모").forEachIndexed { i, label ->
            val t = TextView(this).apply {
                text = label; textSize = 30f; gravity = Gravity.CENTER; setPadding(dp(24), 0, dp(24), 0)
                setOnClickListener { show(i) }
            }
            tabs += t
            tabRow.addView(t, LinearLayout.LayoutParams(0, dp(104), 1f).apply { marginEnd = if (i < 2) dp(12) else 0 })
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(24), 0, 0) }
        root.addView(tabRow)
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        initTts()
        B1.onLeavePark += stopSpeech
    }

    // 기록 파일이 바뀌면 다시 읽는다. 주행 중 차단 화면이 걷힐 때 생명주기 콜백(onResume·포커스)이 오지 않는 경우가 있어
    // 이벤트에 기대지 않고 화면이 떠 있는 동안 2초마다 파일 수정 시각만 확인한다
    private var lastModified = -1L
    private val poll = object : Runnable {
        override fun run() {
            val m = java.io.File(filesDir, "b1_records.jsonl").lastModified()
            if (m != lastModified) { lastModified = m; refresh() }
            body.postDelayed(this, 2000)
        }
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.i("PleosProbe", "E 화면 onResume")
        refresh()
        body.removeCallbacks(poll); body.post(poll)
    }

    override fun onPause() {
        android.util.Log.i("PleosProbe", "E 화면 onPause")
        super.onPause()
    }

    override fun onStop() {
        body.removeCallbacks(poll)
        super.onStop()
    }

    // 주행 중 차단 화면이 걷히거나(P 복귀) 앱 아이콘으로 다시 열 때는 onResume이 오지 않을 수 있어 포커스·새 인텐트에서도 다시 읽는다
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        android.util.Log.i("PleosProbe", "E 화면 포커스=$hasFocus")
        if (hasFocus) refresh()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        refresh()
    }

    private var shownTripTs: Long? = null

    /** 새 운행이 생겼을 때만 시작 화면을 다시 고른다. 같은 운행이면 사용자가 보던 탭과 선택을 그대로 둔다 */
    private fun refresh() {
        B1.recorder?.purgeOldNotes()  // 서비스가 오래 떠 있어도 열람 전에 만료 메모를 지운다(TripLog도 만료 메모는 읽지 않는다)
        val log = TripLog(filesDir)
        val latest = log.latest
        if (shownTripTs != null && shownTripTs == latest?.startTs) return
        shownTripTs = latest?.startTs
        selDriver = null; selItems.clear(); voiceText = null
        // 방금 주차한 운전자는 하차 메모부터, 시간이 지난 뒤 연 다음 운전자는 브리핑부터(메모 미작성은 브리핑에 표시)
        val justEnded = latest != null && System.currentTimeMillis() - latest.endTs < MEMO_FIRST_MS
        show(if (justEnded && log.notes[latest!!.startTs] == null) 2 else 0)
    }

    private fun show(tab: Int) {
        tabs.forEachIndexed { i, t ->
            t.setTextColor(if (i == tab) Color.WHITE else cText)
            t.background = rounded(if (i == tab) cPrimary else cCard, if (i == tab) cPrimary else cBorder)
            t.setTypeface(null, if (i == tab) Typeface.BOLD else Typeface.NORMAL)
        }
        body.removeAllViews()
        val log = TripLog(filesDir)
        when (tab) {
            0 -> briefing(log)
            1 -> tripList(log)
            2 -> memo(log)
        }
    }

    // ---------- 탑승 브리핑 ----------
    private fun briefing(log: TripLog) {
        val t = log.latest
        if (t == null) { body.addView(label("아직 기록된 운행이 없습니다.", 34f, cMuted)); return }
        val note = log.notes[t.startTs]
        body.addView(label("직전 운행", 40f, cText, bold = true))
        body.addView(label("${dateFmt.format(Date(t.endTs))} 운행 종료 때 기록한 값입니다. 지금 차량 상태와 다를 수 있습니다.", 26f, cMuted))
        val card = card()
        card.addView(row("운전자", note?.driver ?: "선택 안 함"))
        card.addView(row("종료", dateFmt.format(Date(t.endTs))))
        card.addView(row("주행거리", t.km?.let { "%.1f km".format(it) } ?: "확인 안 됨"))
        card.addView(row("배터리", t.socEnd?.let { soc -> "$soc%" + (t.socDelta?.let { " (운행 중 %+.0f%%p)".format(it) } ?: "") } ?: "확인 안 됨"))
        card.addView(row("주행가능거리", t.rangeEndM?.let { "%.0f km".format(it / 1000) } ?: "확인 안 됨"))
        body.addView(card)

        val warnings = t.telltalesEnd.map { telltaleNames[it] ?: it }
        if (warnings.isNotEmpty()) {
            val newOnes = t.telltalesOn.map { telltaleNames[it] ?: it }.toSet()
            val w = card(cWarnBg, cWarn)
            w.addView(label("켜져 있는 경고등", 32f, cWarn, bold = true))
            warnings.forEach { w.addView(label(it + if (it in newOnes) "  (운행 시작 때는 없었음)" else "", 30f, cText)) }
            body.addView(w)
        }
        val m = card()
        m.addView(label("이전 운전자 메모", 32f, cText, bold = true))
        when {
            note == null -> m.addView(label("미작성", 30f, cMuted))
            note.items.isEmpty() && note.text == null -> m.addView(label("남길 말 없음", 30f, cMuted))
            else -> {
                if (note.items.isNotEmpty()) m.addView(label(note.items.joinToString(", "), 30f, cText))
                note.text?.let { m.addView(label("\"$it\"", 30f, cText)) }
            }
        }
        if (note == null) m.addView(button("이전 운행에 메모 남기기", primary = false) { show(2) })
        else m.addView(button("이 메모 삭제", primary = false) {
            when (B1.recorder?.deleteNotes(t.startTs)) {
                null -> toast("기록 서비스가 아직 준비되지 않았습니다.")
                true -> { toast("메모를 삭제했습니다."); show(0) }
                false -> toast("삭제하지 못했습니다. 메모는 그대로 있습니다. 다시 눌러 주세요.")
            }
        })
        body.addView(m)
        val gapKm = log.gapsAfter(t).sumOf { it.km }
        if (gapKm > 0) body.addView(label("앱이 꺼져 있는 동안 %.1f km 운행이 기록되지 않았습니다.".format(gapKm), 26f, cMuted))
        body.addView(button("음성으로 듣기", primary = true) { speak(briefingSentence(t, note, warnings)) })
    }

    private fun briefingSentence(t: TripLog.Trip, note: TripLog.Note?, warnings: List<String>): String {
        val parts = mutableListOf("직전 운행은 ${timeFmt.format(Date(t.endTs))}에 끝났습니다. 아래는 그때 기록한 값입니다.")
        t.socEnd?.let { parts += "종료 당시 배터리는 ${it}퍼센트였습니다." }
        if (warnings.isNotEmpty()) parts += "종료 당시 확인된 경고등은 ${warnings.joinToString(", ")}입니다."
        if (note == null) parts += "이전 운전자 메모는 없습니다."
        note?.items?.takeIf { it.isNotEmpty() }?.let { parts += "이전 운전자 메모, ${it.joinToString(", ")}." }
        note?.text?.let { parts += it }
        return parts.joinToString(" ")
    }

    // ---------- 운행 목록 ----------
    private fun tripList(log: TripLog) {
        val (trips, gaps) = log.thisMonth()
        val total = trips.sumOf { it.km ?: 0.0 }
        val gapKm = gaps.sumOf { it.km }
        body.addView(label("이번 달 운행 ${trips.size}건 · 합계 %.1f km".format(total), 40f, cText, bold = true))
        if (gapKm > 0) body.addView(label("기록되지 않은 거리 %.1f km (앱이 꺼져 있던 구간)".format(gapKm), 26f, cMuted))
        if (trips.isEmpty()) { body.addView(label("이번 달 기록이 없습니다.", 30f, cMuted)); return }
        val qrBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        body.addView(button("QR로 월 요약 보기", primary = false) {
            if (qrBox.childCount > 0) { qrBox.removeAllViews(); return@button }
            val text = monthSummary(trips, gaps, log)
            qrBox.addView(android.widget.ImageView(this).apply { setImageBitmap(qr(text, dp(440))) }, LinearLayout.LayoutParams(dp(440), dp(440)))
            qrBox.addView(label("휴대폰 카메라로 찍으면 이번 달 요약이 글자로 나옵니다.", 26f, cMuted))
        })
        body.addView(qrBox)
        // 최신 운행이 위로. 누락 구간은 그 구간이 감지된 운행 바로 위에 끼운다
        val items = (trips.map { it.startTs to it as Any } + gaps.map { it.detectedTs to it as Any }).sortedByDescending { it.first }
        items.forEach { (_, it) ->
            when (it) {
                is TripLog.Trip -> body.addView(tripRow(it, log.notes[it.startTs]))
                is TripLog.Gap -> body.addView(card(cWarnBg, cBorder).apply {
                    addView(label("기록 누락 구간  %.1f km".format(it.km), 26f, cMuted))
                })
            }
        }
    }

    /** QR에 담을 월 요약. QR 하나에 들어가도록 최근 운행은 10건까지만 넣는다(그 이상은 건수·합계만) */
    private fun monthSummary(trips: List<TripLog.Trip>, gaps: List<TripLog.Gap>, log: TripLog): String {
        val month = SimpleDateFormat("yyyy-MM", Locale.KOREA).format(Date(trips.first().startTs))
        val lines = mutableListOf(
            "바통 운행 요약 $month",
            "운행 ${trips.size}건, 합계 %.1f km".format(trips.sumOf { it.km ?: 0.0 }),
        )
        val gapKm = gaps.sumOf { it.km }
        if (gapKm > 0) lines += "기록되지 않은 거리 %.1f km".format(gapKm)
        val f = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA)
        trips.takeLast(10).reversed().forEach { t ->
            val n = log.notes[t.startTs]
            lines += listOfNotNull(f.format(Date(t.startTs)), t.km?.let { "%.1fkm".format(it) } ?: "거리?", n?.driver,
                n?.items?.takeIf { it.isNotEmpty() }?.joinToString("/")).joinToString(" ")
        }
        return lines.joinToString("\n")
    }

    private fun qr(text: String, size: Int): android.graphics.Bitmap {
        val hints = mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8", com.google.zxing.EncodeHintType.MARGIN to 2)
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
        return android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888)
    }

    private fun tripRow(t: TripLog.Trip, note: TripLog.Note?): View = card().apply {
        val head = "${dateFmt.format(Date(t.startTs))} ~ ${timeFmt.format(Date(t.endTs))}   ${t.km?.let { "%.1f km".format(it) } ?: "거리 확인 안 됨"}"
        addView(label(head, 30f, cText, bold = true))
        val sub = mutableListOf(note?.driver ?: "운전자 미선택")
        t.socDelta?.let { sub += if (it == 0.0) "배터리 변화 없음" else "배터리 %+.0f%%p".format(it) }
        if (t.telltalesOn.isNotEmpty()) sub += "새 경고등: " + t.telltalesOn.joinToString(", ") { telltaleNames[it] ?: it }
        if ("resumed_after_restart" in t.flags || "started_at_startup" in t.flags) sub += "앱 재시작 포함"
        addView(label(sub.joinToString("   "), 26f, cMuted))
        note?.let { n ->
            val memo = (n.items + listOfNotNull(n.text)).joinToString(", ")
            if (memo.isNotEmpty()) addView(label("메모: $memo", 26f, cText))
        }
    }

    // ---------- 하차 메모 ----------
    private fun memo(log: TripLog) {
        val t = log.latest
        if (t == null) { body.addView(label("메모를 남길 운행이 없습니다.", 34f, cMuted)); return }
        val done = log.notes[t.startTs]
        // 앞사람이 고른 운전자·항목이 남아 있으면 다음 사람이 그대로 저장할 수 있어, 메모 화면을 열 때마다 비운다
        selDriver = null; selItems.clear(); voiceText = null
        body.addView(label("다음 운전자에게 남길 말이 있나요?", 40f, cText, bold = true))
        body.addView(label("대상 운행: ${dateFmt.format(Date(t.startTs))} ~ ${timeFmt.format(Date(t.endTs))} 종료, ${t.km?.let { "%.1f km".format(it) } ?: "거리 확인 안 됨"}" +
            if (done != null) "  (이미 저장됨, 다시 저장하면 새 메모로 남습니다)" else "", 30f, cText))
        body.addView(label("이 운행을 운전한 분만 작성해 주세요.", 26f, cMuted))
        body.addView(button("이 운행의 운전자가 아닙니다 · 탑승 브리핑 보기", primary = false) { show(0) })

        body.addView(label("운전자", 30f, cText, bold = true))
        body.addView(chips(drivers, single = true))
        body.addView(label("차량 상태", 30f, cText, bold = true))
        body.addView(chips(noteItems, single = false))

        val voice = label(voiceText?.let { "음성 메모: \"$it\"" } ?: "음성 메모 없음", 26f, cMuted)
        body.addView(voice)
        body.addView(button("음성으로 남기기", primary = false) { listen(voice) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, 0) }
        actions.addView(button("남길 말 없음", primary = false) { saveNote(empty = true) }, LinearLayout.LayoutParams(0, dp(112), 1f).apply { marginEnd = dp(16) })
        actions.addView(button("저장", primary = true) { saveNote(empty = false) }, LinearLayout.LayoutParams(0, dp(112), 1f))
        body.addView(actions)
    }

    private fun chips(options: List<String>, single: Boolean): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(16)) }
        val views = mutableListOf<TextView>()
        fun paint() = views.forEach { v ->
            val on = if (single) v.text == selDriver else v.text in selItems
            v.setTextColor(if (on) Color.WHITE else cText)
            v.background = rounded(if (on) cPrimary else cCard, if (on) cPrimary else cBorder)
        }
        options.forEach { o ->
            val v = TextView(this).apply {
                text = o; textSize = 26f; gravity = Gravity.CENTER; setPadding(dp(20), 0, dp(20), 0)
                setOnClickListener {
                    if (single) selDriver = if (selDriver == o) null else o
                    else if (!selItems.remove(o)) selItems += o
                    paint()
                }
            }
            views += v
            row.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, dp(100)).apply { marginEnd = dp(12) })
        }
        paint()
        return row
    }

    private fun saveNote(empty: Boolean) {
        val rec = B1.recorder ?: return toast("기록 서비스가 아직 준비되지 않았습니다. 잠시 뒤 다시 눌러 주세요.")
        if (!rec.isParked()) return toast("메모는 정차(P) 상태에서만 저장할 수 있습니다.")
        val trip = shownTripTs ?: return toast("메모를 남길 운행이 없습니다.")
        if (selDriver == null) return toast("이 운행을 운전한 분을 선택해 주세요.")
        if (empty) rec.addNote(selDriver, emptyList(), null, trip) else rec.addNote(selDriver, selItems.toList(), voiceText, trip)
        selItems.clear(); voiceText = null
        toast("저장했습니다.")
        show(0)
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

    // ---------- 음성 (Pleos TTS·STT SDK, ON_DEVICE) ----------
    private fun initTts() {
        runCatching {
            tts = TextToSpeech(this, ai.pleos.playground.tts.constant.Mode.ON_DEVICE).also { t ->
                t.initialize(object : ai.pleos.playground.tts.listener.OnInitializeListener {
                    override fun onSuccess() {
                        ttsReady = true
                        runCatching { t.setLocale(Locale.KOREA) }
                        t.addEventListener(object : ai.pleos.playground.tts.listener.EventListener {
                            override fun onStart() { android.util.Log.i("PleosProbe", "E 화면 TTS 발화 시작") }
                            override fun onReady() {}
                            override fun onDone() { android.util.Log.i("PleosProbe", "E 화면 TTS 발화 끝") }
                            override fun onError(e: ai.pleos.playground.tts.constant.ErrorType) { android.util.Log.i("PleosProbe", "E 화면 TTS 오류 $e") }
                            override fun onStop() { android.util.Log.i("PleosProbe", "E 화면 TTS 중지됨") }
                            override fun onUpdatedRms(rms: Double) {}
                        })
                    }
                    override fun onFailure(e: ai.pleos.playground.tts.constant.ErrorType) { ttsReady = false }
                })
            }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return toast("음성 안내를 준비하지 못했습니다.")
        runCatching { tts?.speak(text, true) }
    }

    private fun listen(out: TextView) {
        out.text = "음성 인식 준비 중..."
        runCatching {
            stt?.release()
            stt = SpeechToText(this, ai.pleos.playground.stt.constant.Mode.ON_DEVICE).also { s ->
                s.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
                    override fun onSuccess() {
                        // addListener는 initialize 성공 뒤에 해야 한다(P10 확인)
                        s.addListener(object : ai.pleos.playground.stt.listener.ResultListener {
                            override fun onUpdated(text: String, isFinal: Boolean) = runOnUiThread {
                                out.text = "음성 메모: \"$text\""
                                if (isFinal) voiceText = text
                            }
                            override fun onUpdatedEpdData(a: Long, b: Long) {}
                            override fun onStartedRecognition() = runOnUiThread { out.text = "듣고 있습니다..." }
                            override fun onEndedRecognition() {}
                            override fun onError(e: ai.pleos.playground.stt.constant.ErrorType) = runOnUiThread { out.text = "음성 인식 실패: $e" }
                            override fun onReady() = runOnUiThread { out.text = "말씀해 주세요." }
                            override fun onUpdatedRms(rms: Double) {}
                        })
                        runCatching { s.setLocale(Locale.KOREA); s.request() }
                    }
                    override fun onFailure(e: ai.pleos.playground.stt.constant.ErrorType) = runOnUiThread { out.text = "음성 인식을 시작하지 못했습니다: $e" }
                })
            }
        }.onFailure { out.text = "음성 인식을 시작하지 못했습니다." }
    }

    // 주행 중에는 화면만 막히고 이미 시작한 음성은 계속될 수 있어 직접 멈춘다
    // shortcut: 오디오 포커스 상실은 처리하지 않음(Pleos TTS의 포커스 요청 여부를 에뮬레이터에서 확인 못 함), 실차·SDK 문서로 확인되면 추가
    private val stopSpeech: () -> Unit = {
        android.util.Log.i("PleosProbe", "E 화면 P 이탈: 중지 요청")
        runCatching { tts?.stop() }
        runCatching { stt?.stop() }
    }

    override fun onDestroy() {
        B1.onLeavePark -= stopSpeech
        runCatching { stt?.release() }
        runCatching { tts?.release() }
        super.onDestroy()
    }

    // ---------- 뷰 도우미 ----------
    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(fill); setStroke(dp(2), stroke) }

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); setPadding(0, dp(6), 0, dp(6))
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun card(fill: Int = cCard, stroke: Int = cBorder) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = rounded(fill, stroke); setPadding(dp(24), dp(16), dp(24), dp(16))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(16) }
    }

    private fun row(k: String, v: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(label(k, 30f, cMuted), LinearLayout.LayoutParams(dp(280), WRAP_CONTENT))
        addView(label(v, 30f, cText, bold = true))
    }

    private fun button(s: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = s; textSize = 30f; gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else cText)
        background = rounded(if (primary) cPrimary else cCard, if (primary) cPrimary else cBorder)
        setTypeface(null, Typeface.BOLD)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(112)).apply { topMargin = dp(8) }
    }
}
