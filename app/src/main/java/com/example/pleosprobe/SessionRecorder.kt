package com.example.pleosprobe

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * B1 운행 세션 기록 스파이크.
 * 운행 = 기어 P 이탈부터 P 복귀까지, 거리 = 누적 주행거리(km) 차이.
 * 상태는 b1_state.json, 기록은 b1_records.jsonl(추가만)에 남긴다. 프로세스가 죽었다 살아나도 이어서 판단하기 위함.
 * 정책(짧은 P 병합 등)은 넣지 않고 원자료와 플래그만 남긴다. 검증 결과를 보고 정한다.
 * E안: 운행 시작·종료에 차량 상태 스냅샷(VehicleSnapshot)을 붙이고 차이를 trip 레코드에 넣는다.
 * 메모·운전자는 note 레코드로 덧붙인다(trip 레코드는 고치지 않는다).
 */
class SessionRecorder(
    dir: File,
    private val log: (String) -> Unit,
    private val readOdo: ((Float?) -> Unit) -> Unit,
    private val readSnapshot: ((JSONObject) -> Unit) -> Unit = { it(JSONObject()) },
) {
    companion object {
        const val GEAR_P = 4
        const val GAP_KM = 0.05f
        const val NOTE_KEEP_MS = 30L * 24 * 60 * 60 * 1000  // 메모 보관 30일
    }

    private val stateFile = File(dir, "b1_state.json")
    private val recordFile = File(dir, "b1_records.jsonl")

    private var lastGear: Int? = null
    private var open: JSONObject? = null
    private var lastEndOdo: Float? = null

    init {
        runCatching {
            if (stateFile.exists()) {
                val s = JSONObject(stateFile.readText())
                lastGear = if (s.isNull("lastGear")) null else s.getInt("lastGear")
                open = s.optJSONObject("open")
                lastEndOdo = if (s.isNull("lastEndOdo")) null else s.getDouble("lastEndOdo").toFloat()
            }
        }.onFailure { log("B1 상태 파일 읽기 실패: $it") }
        log("B1 상태 로드: lastGear=$lastGear open=$open lastEndOdo=$lastEndOdo")
    }

    @Synchronized
    private fun save() {
        val s = JSONObject()
            .put("lastGear", lastGear ?: JSONObject.NULL)
            .put("open", open ?: JSONObject.NULL)
            .put("lastEndOdo", lastEndOdo?.toDouble() ?: JSONObject.NULL)
        stateFile.writeText(s.toString())
    }

    private fun append(rec: JSONObject) {
        recordFile.appendText(rec.toString() + "\n")
        log("B1 기록 추가: ${forLog(rec)}")
    }

    /** 로그(probe_log.txt·logcat)에는 메모 내용을 남기지 않는다. 기록 파일에서 지워도 로그에 원문이 남지 않게 하기 위함 */
    private fun forLog(rec: JSONObject): String =
        if (rec.optString("type") != "note") rec.toString()
        else "{type=note, tripStartTs=${rec.optLong("tripStartTs")}, ts=${rec.optLong("ts")}, 선택지 ${rec.optJSONArray("items")?.length() ?: 0}개, 음성 ${if (rec.isNull("text")) "없음" else "있음"}}"

    /** 앱 시작 시 한 번. 현재 기어와 저장된 상태를 대조해 열린 세션을 닫거나 이어간다. */
    @Synchronized
    fun recover(currentGear: Int?) {
        log("B1 시작 복구: 저장 lastGear=$lastGear 현재 기어=$currentGear open=${open != null}")
        val o = open
        when {
            o != null && currentGear == GEAR_P -> endTrip(listOf("recovered_at_startup", "end_time_uncertain"))
            o != null -> {
                o.getJSONArray("flags").put("resumed_after_restart")
                log("B1 열린 세션을 이어감 (재시작 중 P를 거쳤는지는 알 수 없음)")
            }
            currentGear != null && currentGear != GEAR_P -> beginTrip(listOf("started_at_startup", "start_time_uncertain"))
            else -> readOdo { odo -> synchronized(this) { checkGap(odo, "app_down_or_idle") } }
        }
        lastGear = currentGear
        save()
    }

    @Synchronized
    fun onGear(gear: Int) {
        if (gear == lastGear) {
            log("B1 같은 기어 반복 무시: $gear")
            return
        }
        val prev = lastGear
        lastGear = gear
        log("B1 기어 변화: $prev -> $gear")
        // 시작·종료 판단은 이벤트 순서대로 즉시 하고, 거리는 비동기로 채운다
        if (gear != GEAR_P && open == null) beginTrip(emptyList())
        else if (gear == GEAR_P && open != null) endTrip(emptyList())
        save()
    }

    private fun checkGap(odo: Float?, reason: String) {
        val last = lastEndOdo ?: return
        if (odo != null && last - odo > GAP_KM) {
            // 누적 주행거리는 줄지 않아야 한다. 줄면 계기·차량 교체·시뮬레이터 초기화 등 이상으로 남기고 기준을 새 값으로 옮긴다
            append(JSONObject().put("type", "odo_anomaly").put("fromOdo", last.toDouble()).put("toOdo", odo.toDouble())
                .put("detectedTs", System.currentTimeMillis()).put("reason", reason))
            lastEndOdo = odo
            save()
            return
        }
        if (odo != null && odo - last > GAP_KM) {
            append(JSONObject().put("type", "gap").put("fromOdo", last.toDouble()).put("toOdo", odo.toDouble())
                .put("km", (odo - last).toDouble()).put("detectedTs", System.currentTimeMillis()).put("reason", reason))
            lastEndOdo = odo  // 같은 누락 구간을 다음 운행 시작 때 다시 기록하지 않도록 기준을 옮긴다
            save()
        }
    }

    private fun beginTrip(flags: List<String>) {
        val o = JSONObject().put("startTs", System.currentTimeMillis()).put("startOdo", JSONObject.NULL).put("flags", JSONArray(flags))
        open = o
        save()
        log("B1 세션 시작: $o")
        readOdo { odo ->
            synchronized(this) {
                checkGap(odo, "between_trips")
                if (odo != null) o.put("startOdo", odo.toDouble()) else o.getJSONArray("flags").put("start_odo_missing")
                save()
                log("B1 시작 거리 확정: $odo")
            }
        }
        readSnapshot { snap ->
            synchronized(this) {
                o.put("startSnap", snap)
                save()
                log("E 시작 스냅샷: $snap")
            }
        }
    }

    private fun endTrip(flags: List<String>) {
        val o = open ?: return
        open = null
        save()
        val endTs = System.currentTimeMillis()
        readOdo { odo ->
            synchronized(this) {
                // 다음 운행 시작의 누락 판단이 이 값을 쓰므로 스냅샷을 기다리지 않고 먼저 옮긴다
                if (odo != null) lastEndOdo = odo
                save()
            }
            readSnapshot { endSnap ->
                synchronized(this) {
                    val startOdo = if (o.isNull("startOdo")) null else o.getDouble("startOdo").toFloat()
                    val all = o.getJSONArray("flags")
                    flags.forEach { all.put(it) }
                    if (odo == null) all.put("end_odo_missing")
                    val startSnap = o.optJSONObject("startSnap")
                    if (startSnap == null) all.put("start_snapshot_missing")
                    val km = if (odo != null && startOdo != null) (odo - startOdo).toDouble() else JSONObject.NULL
                    append(JSONObject().put("type", "trip").put("startTs", o.getLong("startTs")).put("endTs", endTs)
                        .put("startOdo", startOdo?.toDouble() ?: JSONObject.NULL).put("endOdo", odo?.toDouble() ?: JSONObject.NULL)
                        .put("km", km).put("flags", all)
                        .put("startSnap", startSnap ?: JSONObject.NULL).put("endSnap", endSnap)
                        .put("delta", diff(startSnap, endSnap)))
                }
            }
        }
    }

    /** 시작·종료 스냅샷 차이. 한쪽이라도 값이 없으면 그 항목은 null. 경고등은 "운행 시작과 끝의 차이"일 뿐 운행 중 켜졌다 꺼진 것은 모른다. */
    private fun diff(a: JSONObject?, b: JSONObject): JSONObject {
        fun num(o: JSONObject?, k: String): Double? = if (o == null || o.isNull(k) || !o.has(k)) null else o.optDouble(k).takeIf { !it.isNaN() }
        fun d(k: String): Any = num(a, k)?.let { x -> num(b, k)?.let { y -> y - x } } ?: JSONObject.NULL
        fun set(o: JSONObject?): Set<String>? = o?.optJSONArray("telltales")?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
        val ta = set(a); val tb = set(b)
        return JSONObject().put("soc", d("soc")).put("rangeM", d("rangeM")).put("fuelPct", d("fuelPct"))
            .put("telltalesOn", if (ta != null && tb != null) JSONArray((tb - ta).sorted()) else JSONObject.NULL)
            .put("telltalesOff", if (ta != null && tb != null) JSONArray((ta - tb).sorted()) else JSONObject.NULL)
    }

    /** 정차(P) 중에만, 목적이 없는 가장 최근 운행에 목적을 붙인다. */
    @Synchronized
    fun classifyLatest(purpose: String) {
        if (lastGear != GEAR_P) return log("B1 목적 분류는 정차(P) 중에만 가능. 현재 기어=$lastGear")
        val lines = if (recordFile.exists()) recordFile.readLines().map { JSONObject(it) } else emptyList()
        val done = lines.filter { it.optString("type") == "purpose" }.map { it.getLong("tripStartTs") }.toSet()
        val target = lines.lastOrNull { it.optString("type") == "trip" && it.getLong("startTs") !in done }
            ?: return log("B1 목적을 붙일 운행 없음")
        append(JSONObject().put("type", "purpose").put("tripStartTs", target.getLong("startTs")).put("purpose", purpose))
    }

    @Synchronized
    fun isParked() = lastGear == GEAR_P

    /** 정차(P) 중에만, 가장 최근 운행에 하차 메모를 붙인다. items는 프리셋 선택지, text는 음성 인식 결과(없으면 null). */
    @Synchronized
    fun addNote(driver: String?, items: List<String>, text: String?, tripStartTs: Long? = null) {
        if (lastGear != GEAR_P) return log("E 메모는 정차(P) 중에만 가능. 현재 기어=$lastGear")
        val lines = if (recordFile.exists()) recordFile.readLines().map { JSONObject(it) } else emptyList()
        // 화면이 보여 준 운행을 지정하면 그 운행에 붙인다(화면 갱신과 기록 추가 사이 경합 방지). 없으면 최신 운행
        val target = lines.lastOrNull { it.optString("type") == "trip" && (tripStartTs == null || it.getLong("startTs") == tripStartTs) }
            ?: return log("E 메모를 붙일 운행 없음 (지정=$tripStartTs)")
        append(JSONObject().put("type", "note").put("tripStartTs", target.getLong("startTs")).put("ts", System.currentTimeMillis())
            .put("driver", driver ?: JSONObject.NULL).put("items", JSONArray(items)).put("text", text ?: JSONObject.NULL))
    }

    /** 해당 운행의 메모를 기록 파일에서 실제로 지운다(공용차 메모의 개인정보 대응). 실패하면 false, 원본은 그대로 */
    @Synchronized
    fun deleteNotes(tripStartTs: Long) = rewriteDropping { it.optString("type") == "note" && it.optLong("tripStartTs") == tripStartTs }

    /** 보관 기간이 지난 메모를 지운다. 운행 기록은 남긴다. 서비스 시작과 화면 갱신 때 부른다 */
    @Synchronized
    fun purgeOldNotes(now: Long = System.currentTimeMillis()) =
        rewriteDropping { it.optString("type") == "note" && now - it.optLong("ts") > NOTE_KEEP_MS }

    /** 원본을 직접 덮어쓰지 않고 임시 파일에 쓴 뒤 바꿔 끼운다. 쓰기·교체가 실패하면 원본을 건드리지 않고 false */
    private fun rewriteDropping(drop: (JSONObject) -> Boolean): Boolean {
        if (!recordFile.exists()) return true
        val lines = recordFile.readLines()
        val kept = lines.filterNot { runCatching { drop(JSONObject(it)) }.getOrDefault(false) }
        if (kept.size == lines.size) return true
        val tmp = File(recordFile.parentFile, recordFile.name + ".tmp")
        val ok = runCatching {
            java.io.FileOutputStream(tmp).use { out -> out.write(kept.joinToString("") { it + "\n" }.toByteArray()); out.fd.sync() }
            check(tmp.renameTo(recordFile)) { "renameTo false" }
        }.onFailure { log("E 메모 삭제 실패, 원본 유지: $it"); tmp.delete() }.isSuccess
        if (ok) log("E 메모 ${lines.size - kept.size}건 삭제")
        return ok
    }

    @Synchronized
    fun dump() {
        log("B1 상태: lastGear=$lastGear open=$open lastEndOdo=$lastEndOdo")
        if (recordFile.exists()) recordFile.readLines().forEach { log("B1 | ${runCatching { forLog(JSONObject(it)) }.getOrDefault("(읽기 실패)")}") } else log("B1 기록 없음")
    }

    @Synchronized
    fun reset() {
        stateFile.delete(); recordFile.delete()
        lastGear = null; open = null; lastEndOdo = null
        log("B1 기록 초기화")
    }
}
