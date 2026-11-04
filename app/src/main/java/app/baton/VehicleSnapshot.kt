package app.baton

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * E안 운행 시작·종료 시점의 차량 상태 묶음. P9·P10에서 공개 권한으로 확인한 항목만 읽는다.
 * 항목은 병렬로 읽고, 제한 시간 안에 안 온 항목과 실패한 항목은 null로 두고 사유를 errors에 남긴다.
 * 결과: {ts, soc, rangeM, fuelPct, telltales:[...], driverDoor, errors:{항목:사유}}
 */
class VehicleSnapshot(
    private val vehicle: () -> Vehicle?,
    private val cpm: () -> CarPropertyManager?,
    private val timeoutMs: Long = 2000,
) {
    private val main = Handler(Looper.getMainLooper())

    fun collect(done: (JSONObject) -> Unit) {
        val snap = JSONObject().put("ts", System.currentTimeMillis())
        val errors = JSONObject()
        val keys = listOf("soc", "rangeM", "fuelPct", "telltales", "driverDoor")
        var pending = keys.size
        var finished = false

        fun finish() {
            if (finished) return
            finished = true
            keys.forEach { if (!snap.has(it)) { snap.put(it, JSONObject.NULL); if (!errors.has(it)) errors.put(it, "timeout") } }
            done(snap.put("errors", errors))
        }
        fun set(key: String, value: Any?, error: String? = null) = synchronized(snap) {
            if (finished) return@synchronized
            snap.put(key, value ?: JSONObject.NULL)
            if (error != null) errors.put(key, error)
            if (--pending == 0) finish()
        }
        fun fail(key: String) = { e: Exception -> set(key, null, "${e.javaClass.simpleName} ${e.message}") }

        main.postDelayed({ synchronized(snap) { finish() } }, timeoutMs)

        val v = vehicle()
        if (v == null) { keys.filter { it != "rangeM" }.forEach { set(it, null, "no_vehicle") } }
        else {
            runCatching { v.getEvBattery().getEvBatteryStateOfCharge({ set("soc", it) }, fail("soc")) }.onFailure { set("soc", null, it.toString()) }
            runCatching { v.getFuel().getFuelPercent({ set("fuelPct", it) }, fail("fuelPct")) }.onFailure { set("fuelPct", null, it.toString()) }
            runCatching {
                v.getSafety().getTelltalesInfo({ t: Telltales ->
                    set("telltales", JSONArray(t.telltales.filter { it.state.toString() != "OFF" }.map { it.type.toString() }.sorted()))
                }, fail("telltales"))
            }.onFailure { set("telltales", null, it.toString()) }
            runCatching { v.getDoor().getCurrentDoorPosition(DoorArea(DoorValue.ROW_1_LEFT), { set("driverDoor", it) }, fail("driverDoor")) }
                .onFailure { set("driverDoor", null, it.toString()) }
        }
        // 주행가능거리는 Pleos에 공개 API가 없어 AAOS 속성으로 읽는다(동기 호출)
        runCatching { cpm()?.getFloatProperty(VehiclePropertyIds.RANGE_REMAINING, 0) }
            .onSuccess { set("rangeM", it, if (it == null) "no_cpm" else null) }
            .onFailure { set("rangeM", null, "${it.javaClass.simpleName} ${it.message}") }
    }
}
