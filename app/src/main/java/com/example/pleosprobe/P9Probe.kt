package com.example.pleosprobe

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager

/**
 * P9 E안(공용차 인수인계)에 필요한 Vehicle 읽기 점검. 조회·구독만 하고 set API는 호출하지 않는다.
 * 각 항목: capability -> get 1회 -> 구독(콘솔에서 값 바꾸면 콜백으로 반영 확인).
 * P9-5는 getTelltalesInfo(공개 권한)를 두 번 불러 차이를 낸다. 구독(P3, private)에 의존하지 않는지 보기 위함.
 */
class P9Probe(
    private val vehicle: () -> Vehicle?,
    private val cpm: () -> CarPropertyManager?,
    private val log: (String) -> Unit,
) {
    private var lastTelltales: Set<String>? = null
    private var subscribed = false

    private fun fail(tag: String) = { e: Exception -> log("$tag 실패: ${e.javaClass.simpleName} ${e.message}") }

    /** 모든 항목 capability + get 1회. 처음 한 번은 구독도 건다. */
    fun runAll() {
        val v = vehicle() ?: return log("P9 Vehicle 없음")
        fuel(v); evBattery(v); tire(v); door(v); aaos()
        telltaleSnapshot()
        turnSignalSteeringBrake(v)
        if (!subscribed) { subscribe(v); subscribed = true }
    }

    // ---------- P9-1 Fuel ----------
    private fun fuel(v: Vehicle) {
        val f = v.getFuel()
        f.checkFuelPercentCapabilities({ c -> log("P9-1 연료% capability: $c") }, fail("P9-1 연료% capability"))
        f.getFuelPercent({ p -> log("P9-1 get 연료%: $p") }, fail("P9-1 getFuelPercent"))
    }

    // ---------- P9-2 EvBattery ----------
    private fun evBattery(v: Vehicle) {
        val b = v.getEvBattery()
        b.checkEvBatteryStateOfChargeCapabilities({ c -> log("P9-2 SOC capability: $c") }, fail("P9-2 SOC capability"))
        b.getEvBatteryStateOfCharge({ p -> log("P9-2 get SOC%: $p") }, fail("P9-2 getEvBatteryStateOfCharge"))
        b.getEvChargeSwitchState({ s -> log("P9-2 get 충전 스위치: $s") }, fail("P9-2 getEvChargeSwitchState"))
        b.isEvChargePortOpen({ s -> log("P9-2 get 충전구 열림: $s") }, fail("P9-2 isEvChargePortOpen"))
        b.getEvBatteryCapacity({ s -> log("P9-2 get 배터리 용량(Wh): $s") }, fail("P9-2 getEvBatteryCapacity(CAR_INFO)"))
    }

    // ---------- P9-3 Tire ----------
    private fun tire(v: Vehicle) {
        val t = v.getTire()
        t.checkLowTirePressureCapabilities({ c, areas ->
            log("P9-3 저압 임계값 capability: $c areas=${areas.map { it.values.toList() }}")
        }, fail("P9-3 저압 capability"))
        t.getLowTirePressure(WheelArea(WheelValue.LEFT_FRONT), { p -> log("P9-3 get 저압 임계값 LF(kPa): $p") }, fail("P9-3 getLowTirePressure"))
        // 실제 공기압은 PRIVATE_CAR_SAFETY + @hide API. 에뮬레이터 부여 여부만 기록한다
        @Suppress("DEPRECATION")
        runCatching {
            t.getTirePressure(WheelArea(WheelValue.LEFT_FRONT), { p -> log("P9-3 get 공기압 LF(kPa, private): $p") }, fail("P9-3 getTirePressure(private)"))
        }.onFailure { log("P9-3 getTirePressure 예외: $it") }
    }

    // ---------- P9-4 Door ----------
    private val doorAreas = listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT, DoorValue.REAR)

    private fun door(v: Vehicle) {
        val d = v.getDoor()
        d.checkDoorPositionCapabilities({ c, m ->
            log("P9-4 도어 위치 capability: $c areas=${m.map { (k, b) -> "${k.values.toList()}:${b.minValue}..${b.maxValue}" }}")
        }, fail("P9-4 도어 capability"))
        doorAreas.forEach { a ->
            d.getCurrentDoorPosition(DoorArea(a), { p -> log("P9-4 get 도어 $a 위치: $p") }, fail("P9-4 getCurrentDoorPosition $a"))
        }
    }

    // ---------- AAOS 표준 속성 (Pleos에 없는 잔량 L·주행가능거리 보조) ----------
    private fun aaos() {
        val m = cpm() ?: return log("P9 AAOS CarPropertyManager 없음")
        listOf(
            "INFO_FUEL_TYPE" to VehiclePropertyIds.INFO_FUEL_TYPE,
            "INFO_EV_CONNECTOR_TYPE" to VehiclePropertyIds.INFO_EV_CONNECTOR_TYPE,
            "INFO_FUEL_CAPACITY(ml)" to VehiclePropertyIds.INFO_FUEL_CAPACITY,
            "INFO_EV_BATTERY_CAPACITY(Wh)" to VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY,
            "FUEL_LEVEL(ml)" to VehiclePropertyIds.FUEL_LEVEL,
            "FUEL_LEVEL_LOW" to VehiclePropertyIds.FUEL_LEVEL_LOW,
            "EV_BATTERY_LEVEL(Wh)" to VehiclePropertyIds.EV_BATTERY_LEVEL,
            "EV_CHARGE_STATE" to VehiclePropertyIds.EV_CHARGE_STATE,
            "RANGE_REMAINING(m)" to VehiclePropertyIds.RANGE_REMAINING,
            "TIRE_PRESSURE(kPa)" to VehiclePropertyIds.TIRE_PRESSURE,
        ).forEach { (name, id) ->
            runCatching {
                val cfg = m.getCarPropertyConfig(id) ?: return@runCatching log("P9 AAOS $name: 설정 없음(미지원 또는 권한 없음)")
                cfg.areaIds.forEach { area ->
                    val pv = m.getProperty<Any>(id, area)
                    log("P9 AAOS $name area=$area = ${show(pv?.value)} status=${pv?.status}")
                }
            }.onFailure { log("P9 AAOS $name 실패: ${it.javaClass.simpleName} ${it.message}") }
        }
    }

    private fun show(v: Any?): String = when (v) {
        is IntArray -> v.toList().toString()
        is Array<*> -> v.toList().toString()
        else -> v.toString()
    }

    // ---------- P9-5 경고등 조회 두 번으로 변화 만들기 ----------
    fun telltaleSnapshot() {
        val s = vehicle()?.getSafety() ?: return log("P9-5 Vehicle 없음")
        s.getTelltalesInfo({ t: Telltales ->
            val now = t.telltales.filter { it.state.toString() != "OFF" }.map { "${it.type}:${it.state}" }.toSet()
            val prev = lastTelltales
            log("P9-5 get 경고등 ${t.telltales.size}개 중 OFF 아닌 것 ${now.size}개: $now")
            if (prev != null) log("P9-5 이전 조회 대비 새로 켜짐=${now - prev} 꺼짐=${prev - now}")
            else log("P9-5 첫 조회. 콘솔에서 경고등을 바꾼 뒤 'P9-5 경고등 다시 조회'로 비교")
            lastTelltales = now
        }, fail("P9-5 getTelltalesInfo"))
    }

    // ---------- P9-6 I 대비 ----------
    private fun turnSignalSteeringBrake(v: Vehicle) {
        v.getTurnSignal().let { ts ->
            ts.checkTurnSignalStateCapabilities({ c -> log("P9-6 방향지시등 capability: $c") }, fail("P9-6 방향지시등 capability"))
            ts.getCurrentTurnSignalState({ s -> log("P9-6 get 방향지시등: $s") }, fail("P9-6 getCurrentTurnSignalState"))
        }
        v.getSteeringWheel().let { sw ->
            sw.checkSteeringWheelAngleCapabilities({ c, b -> log("P9-6 조향각 capability: $c ${b.minValue}..${b.maxValue}") }, fail("P9-6 조향각 capability"))
            sw.getCurrentSteeringWheelAngle({ a -> log("P9-6 get 조향각: $a") }, fail("P9-6 getCurrentSteeringWheelAngle"))
        }
        v.getBrake().let { br ->
            br.checkBrakePedalStatusCapabilities({ c -> log("P9-6 브레이크 capability: $c") }, fail("P9-6 브레이크 capability"))
            br.getCurrentBrakePedalStatus({ s -> log("P9-6 get 브레이크 페달: $s") }, fail("P9-6 getCurrentBrakePedalStatus"))
        }
    }

    // ---------- 구독: 콘솔 조작 반영 확인 ----------
    private fun subscribe(v: Vehicle) {
        runCatching {
            v.getFuel().registerFuelPercent(object : FuelPercentListener {
                override fun onFuelPercentUpdated(value: Int?) = log("P9-1 콜백 연료%: $value")
                override fun onFailed(e: Exception) = fail("P9-1 연료% 구독")(e)
            })
        }.onFailure { log("P9-1 구독 예외: $it") }
        runCatching {
            v.getEvBattery().registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(value: Int?) = log("P9-2 콜백 SOC%: $value")
                override fun onFailed(e: Exception) = fail("P9-2 SOC 구독")(e)
            })
        }.onFailure { log("P9-2 구독 예외: $it") }
        doorAreas.forEach { a ->
            runCatching {
                v.getDoor().registerDoorPosition(DoorArea(a), object : DoorPositionListener {
                    override fun onPositionUpdated(pos: Int?) = log("P9-4 콜백 도어 $a 위치: $pos")
                    override fun onFailed(e: Exception) = fail("P9-4 도어 $a 구독")(e)
                })
            }.onFailure { log("P9-4 $a 구독 예외: $it") }
        }
        runCatching {
            v.getTurnSignal().registerTurnSignalState(object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = log("P9-6 콜백 방향지시등: $state")
                override fun onFailed(e: Exception) = fail("P9-6 방향지시등 구독")(e)
            })
        }.onFailure { log("P9-6 방향지시등 구독 예외: $it") }
        runCatching {
            v.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(angle: Float?) = log("P9-6 콜백 조향각: $angle")
                override fun onFailed(e: Exception) = fail("P9-6 조향각 구독")(e)
            })
        }.onFailure { log("P9-6 조향각 구독 예외: $it") }
        runCatching {
            v.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(status: Boolean?) = log("P9-6 콜백 브레이크 페달: $status")
                override fun onFailed(e: Exception) = fail("P9-6 브레이크 구독")(e)
            })
        }.onFailure { log("P9-6 브레이크 구독 예외: $it") }
        // AAOS 잔량·주행가능거리 변화
        cpm()?.let { m ->
            val cb = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) =
                    log("P9 AAOS 콜백 ${VehiclePropertyIds.toString(value.propertyId)} area=${value.areaId} = ${show(value.value)}")
                override fun onErrorEvent(propId: Int, zone: Int) = log("P9 AAOS 오류 이벤트 prop=$propId")
            }
            listOf(VehiclePropertyIds.FUEL_LEVEL, VehiclePropertyIds.EV_BATTERY_LEVEL, VehiclePropertyIds.RANGE_REMAINING, VehiclePropertyIds.TIRE_PRESSURE)
                .forEach { id ->
                    runCatching { m.registerCallback(cb, id, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
                        .onSuccess { log("P9 AAOS 구독 ${VehiclePropertyIds.toString(id)}: $it") }
                        .onFailure { log("P9 AAOS 구독 실패 ${VehiclePropertyIds.toString(id)}: ${it.javaClass.simpleName} ${it.message}") }
                }
        }
        log("P9 구독 등록 끝. 이제 가상 콘솔에서 값을 바꾼다")
    }
}
