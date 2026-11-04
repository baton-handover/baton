package app.baton

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** b1_records.jsonl을 화면용으로 읽는다. 기록 파일은 SessionRecorder만 쓰고, 여기서는 읽기만 한다. */
class TripLog(dir: File) {

    data class Trip(
        val startTs: Long, val endTs: Long, val km: Double?, val flags: List<String>,
        val socEnd: Int?, val socDelta: Double?, val rangeEndM: Double?,
        val telltalesEnd: List<String>, val telltalesOn: List<String>, val telltalesOff: List<String>,
    val telltalesKnown: Boolean,  // 종료 때 경고등을 읽었는지. 못 읽었으면 빈 목록이 "경고 없음"이 아니다
    )
    data class Note(val tripStartTs: Long, val ts: Long, val driver: String?, val items: List<String>, val text: String?)
    data class Gap(val detectedTs: Long, val km: Double)

    val trips = mutableListOf<Trip>()
    val notes = mutableMapOf<Long, Note>()   // 운행 시작 시각 -> 그 운행의 마지막 메모
    val gaps = mutableListOf<Gap>()

    init {
        val now = System.currentTimeMillis()  // 보관 기간이 지난 메모는 아직 파일에서 안 지워졌어도 보여 주지 않는다
        val f = File(dir, "b1_records.jsonl")
        if (f.exists()) f.readLines().forEach { line ->
            val r = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            when (r.optString("type")) {
                "trip" -> trips += parseTrip(r)
                "note" -> if (now - r.optLong("ts") <= SessionRecorder.NOTE_KEEP_MS) notes[r.getLong("tripStartTs")] = Note(r.getLong("tripStartTs"), r.optLong("ts"),
                    r.optStringOrNull("driver"), r.optJSONArray("items").strings(), r.optStringOrNull("text"))
                "gap" -> gaps += Gap(r.optLong("detectedTs"), r.optDouble("km"))
            }
        }
    }

    private fun parseTrip(r: JSONObject): Trip {
        val end = r.optJSONObject("endSnap")
        val d = r.optJSONObject("delta")
        return Trip(
            r.getLong("startTs"), r.getLong("endTs"), r.optDoubleOrNull("km"), r.optJSONArray("flags").strings(),
            end?.optDoubleOrNull("soc")?.toInt(), d?.optDoubleOrNull("soc"), end?.optDoubleOrNull("rangeM"),
            end?.optJSONArray("telltales").strings(), d?.optJSONArray("telltalesOn").strings(), d?.optJSONArray("telltalesOff").strings(),
            end?.optJSONArray("telltales") != null,
        )
    }

    val latest: Trip? get() = trips.lastOrNull()

    /** 가장 최근 운행 이후에 감지된 누락 구간(앱이 꺼져 있던 동안 움직인 거리) */
    fun gapsAfter(trip: Trip?): List<Gap> = gaps.filter { trip == null || it.detectedTs > trip.endTs }

    fun thisMonth(now: Long = System.currentTimeMillis()): Pair<List<Trip>, List<Gap>> {
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val from = c.timeInMillis
        return trips.filter { it.startTs >= from } to gaps.filter { it.detectedTs >= from }
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
    private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k)
    private fun JSONObject.optDoubleOrNull(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }
}
