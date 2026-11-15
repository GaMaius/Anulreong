package io.infotive.anulleong.sdk

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.content.Context
import android.util.Log
import io.infotive.anulleong.care.VentRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs

data class Capability(val access: AccessType?, val value: Any?, val min: Number? = null, val max: Number? = null) {
    val writable get() = access == AccessType.READ_WRITE || access == AccessType.WRITE
}

data class VehicleState(
    val link: Link = Link.CONNECTING,
    val gear: String? = null,
    val steeringDeg: Float? = null,
    val brakeOn: Boolean? = null,
    val drivingMode: String? = null,
    val recirc: Capability? = null,      // Boolean (true = recirculation / inside air)
    val window: Capability? = null,      // Int position
    val windowName: String? = null,
    val fan: Capability? = null,         // Int level
    val temp: Capability? = null,        // Float °C
    val ambient: Capability? = null,     // Int colour
    /** Rear seats that report a passenger (e.g. "왼쪽" → true). */
    val rearSeats: Map<String, Boolean> = emptyMap(),
    /** Recent sharp steering / brake presses (timestamps, last 10 min) for driving smoothness. */
    val steerEvents: List<Long> = emptyList(),
    val brakeEvents: List<Long> = emptyList(),
    val lastError: String? = null,
) {
    val childSeat: String? get() = rearSeats.entries.filter { it.value }.map { it.key }.let { if (it.size == 1) it.first() else null }
}

enum class StepResult { CONFIRMED, NOT_CONFIRMED, SKIPPED_ALREADY, SKIPPED_HVAC_OFF, UNSUPPORTED, FAILED }

data class CareStep(val label: String, val result: StepResult, val detail: String? = null)

data class VentReport(val steps: List<CareStep>) {
    val anyConfirmed get() = steps.any { it.result == StepResult.CONFIRMED }
}

/**
 * Wraps the Pleos Vehicle SDK. Every write is followed by a separate read, and only the
 * read decides whether we tell the family "done" (an ACK alone is not treated as success).
 */
class VehicleSource(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private var vehicle: Vehicle? = null
    private val _state = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = _state

    private var recircArea: SeatArea? = null
    private var windowArea: WindowArea? = null
    private var fanArea: SeatArea? = null
    private var tempArea: SeatArea? = null
    private var ambientArea: SeatArea? = null
    private val seatListeners = mutableListOf<Pair<SeatArea, SeatOccupancyListener>>()

    private data class Original(val recirc: Boolean?, val window: Int?, val fan: Int?, val temp: Float?, val ambient: Int?)
    private var original: Original? = null

    private var lastSteer: Pair<Float, Long>? = null
    private var lastSharpAt = 0L

    private val gearL = object : GearSelectionListener {
        override fun onSelected(p0: VehicleGear?) { _state.update { it.copy(gear = p0?.name) } }
        override fun onFailed(e: Exception) {}
    }
    private val steerL = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(p0: Float?) {
            val now = System.currentTimeMillis()
            if (p0 != null) {
                // A "sharp" steering input: > 90°/s while the wheel is turned more than 20°.
                lastSteer?.let { (a, t) ->
                    val dt = (now - t) / 1000f
                    if (dt in 0.05f..2f && abs(p0 - a) / dt > 90f && abs(p0) > 20f && now - lastSharpAt > 2_000) {
                        lastSharpAt = now
                        addEvent(steer = true, now)
                    }
                }
                lastSteer = p0 to now
            }
            _state.update { it.copy(steeringDeg = p0) }
        }
        override fun onFailed(e: Exception) {}
    }
    private val brakeL = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(p0: Boolean?) {
            val was = _state.value.brakeOn
            if (p0 == true && was == false) addEvent(steer = false, System.currentTimeMillis())
            _state.update { it.copy(brakeOn = p0) }
        }
        override fun onFailed(e: Exception) {}
    }
    private val modeL = object : DrivingModeStateListener {
        override fun onDrivingModeStateUpdated(p0: DrivingModeType?) { _state.update { it.copy(drivingMode = p0?.name) } }
        override fun onFailed(e: Exception) {}
    }

    private fun addEvent(steer: Boolean, at: Long) = _state.update { s ->
        val keep = { l: List<Long> -> (l + at).filter { at - it < 10 * 60_000 } }
        if (steer) s.copy(steerEvents = keep(s.steerEvents)) else s.copy(brakeEvents = keep(s.brakeEvents))
    }

    /** Demo only: feed synthetic driving events so the coaching card can be shown without a car. */
    fun injectSyntheticEvent(steer: Boolean) = addEvent(steer, System.currentTimeMillis())

    fun start() {
        try {
            vehicle = Vehicle(app).also { it.initialize() }
        } catch (e: Throwable) {
            Log.w(TAG, "vehicle unavailable", e)
            _state.update { it.copy(link = Link.UNAVAILABLE, lastError = e.message ?: e.javaClass.simpleName) }
            return
        }
        // initialize() has no callback; the service binds asynchronously, so retry discovery.
        scope.launch {
            repeat(12) { attempt ->
                delay(if (attempt == 0) 1_500 else 5_000)
                if (discover()) return@launch
            }
            _state.update { it.copy(link = Link.FAILED, lastError = it.lastError ?: "차량 서비스 응답 없음") }
        }
    }

    private suspend fun discover(): Boolean {
        val v = vehicle ?: return false
        // "Connected" = the service answered a capability query (recirculation/window were
        // verified on the emulator). Gear is optional: its API is deprecated in Vehicle 2.3.2.
        if (!refreshControls()) return false
        _state.update { it.copy(link = Link.CONNECTED, lastError = null) }
        (call<VehicleGear?> { ok, err -> @Suppress("DEPRECATION") v.getGear().getCurrentGearSelectionState(ok, err) } as? Res.Ok)
            ?.let { g -> _state.update { it.copy(gear = g.v?.name) } }
        (call<Float?> { ok, err -> v.getSteeringWheel().getCurrentSteeringWheelAngle(ok, err) } as? Res.Ok)
            ?.let { s -> _state.update { it.copy(steeringDeg = s.v) } }
        (call<Boolean?> { ok, err -> v.getBrake().getCurrentBrakePedalStatus(ok, err) } as? Res.Ok)
            ?.let { b -> _state.update { it.copy(brakeOn = b.v) } }
        (call<DrivingModeType?> { ok, err -> v.getDrivingMode().getCurrentDrivingModeState(ok, err) } as? Res.Ok)
            ?.let { m -> _state.update { it.copy(drivingMode = m.v?.name) } }
        runCatching { @Suppress("DEPRECATION") v.getGear().registerGearSelection(gearL) }
        runCatching { v.getSteeringWheel().registerSteeringWheelAngle(steerL) }
        runCatching { v.getBrake().registerBrakePedalStatus(brakeL) }
        runCatching { v.getDrivingMode().registerDrivingModeState(modeL) }
        discoverSeats()
        refreshControls()
        return true
    }

    /** Which rear seat the child sits in: used to open the window next to them. */
    private suspend fun discoverSeats() {
        val v = vehicle ?: return
        val seat = v.getSeat()
        val areas = (call<List<SeatArea>> { ok, err -> seat.checkSeatOccupancyCapabilities({ _: CapabilityConfig, l: List<SeatArea> -> ok(l) }, err) } as? Res.Ok)?.v
            ?: return
        for (a in areas) {
            val label = seatLabel(a) ?: continue
            (call<Boolean?> { ok, err -> seat.getCurrentSeatOccupancy(a, ok, err) } as? Res.Ok)?.v?.let { occ ->
                _state.update { it.copy(rearSeats = it.rearSeats + (label to occ)) }
            }
            val l = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(p0: Boolean?) {
                    if (p0 != null) _state.update { it.copy(rearSeats = it.rearSeats + (label to p0)) }
                }
                override fun onFailed(e: Exception) {}
            }
            runCatching { seat.registerSeatOccupancy(a, l); seatListeners += a to l }
        }
    }

    /** Re-reads control capabilities/values. Returns true if the service answered at all. */
    suspend fun refreshControls(): Boolean {
        val v = vehicle ?: return false
        val hvac = v.getHVAC()
        val rc = call<Pair<AccessType?, List<SeatArea>>> { ok, err ->
            hvac.checkHvacRecirculationCapabilities({ c, areas -> ok(c.accessType to areas) }, err)
        }
        if (rc is Res.Ok) {
            val area = rc.v.second.firstOrNull()
            recircArea = area
            val value = area?.let { a -> (call<Boolean?> { ok, err -> hvac.getHvacRecirculationState(a, ok, err) } as? Res.Ok)?.v }
            _state.update { it.copy(recirc = Capability(if (area == null) AccessType.NONE else rc.v.first, value)) }
        } else _state.update { it.copy(recirc = Capability(AccessType.NONE, null)) }

        val win = v.getWindow()
        val wc = call<Pair<AccessType?, Map<WindowArea, AreaBounds<Int>>>> { ok, err ->
            win.checkWindowPositionCapabilities({ c, bounds -> ok(c.accessType to bounds) }, err)
        }
        if (wc is Res.Ok) {
            // Open the window next to the child: occupied rear seat first, then any rear window.
            val seats = _state.value.rearSeats
            val left = seats["왼쪽"] == true; val right = seats["오른쪽"] == true
            val prefer = when {
                left && !right -> listOf(WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)
                else -> listOf(WindowValue.ROW_2_RIGHT, WindowValue.ROW_2_LEFT)
            } + listOf(WindowValue.ROW_1_RIGHT, WindowValue.ROW_1_LEFT)
            val entry = prefer.firstNotNullOfOrNull { p -> wc.v.second.entries.firstOrNull { e -> e.key.values.contains(p) } }
                ?: wc.v.second.entries.firstOrNull()
            windowArea = entry?.key
            val value = entry?.key?.let { a -> (call<Int?> { ok, err -> win.getCurrentWindowPosition(a, ok, err) } as? Res.Ok)?.v }
            _state.update {
                it.copy(
                    window = Capability(if (entry == null) AccessType.NONE else wc.v.first, value, entry?.value?.minValue, entry?.value?.maxValue),
                    windowName = entry?.key?.values?.joinToString { v2 -> windowLabel(v2) },
                )
            }
        } else _state.update { it.copy(window = Capability(AccessType.NONE, null)) }

        val fc = call<Pair<AccessType?, Map<SeatArea, AreaBounds<Int>>>> { ok, err ->
            hvac.checkHvacFanSpeedCapabilities({ c: CapabilityConfig, m: Map<SeatArea, AreaBounds<Int>> -> ok(c.accessType to m) }, err)
        }
        if (fc is Res.Ok) {
            val e = fc.v.second.entries.firstOrNull(); fanArea = e?.key
            val value = e?.key?.let { a -> (call<Int?> { ok, err -> hvac.getHvacFanSpeed(a, ok, err) } as? Res.Ok)?.v }
            _state.update { it.copy(fan = Capability(if (e == null) AccessType.NONE else fc.v.first, value, e?.value?.minValue, e?.value?.maxValue)) }
        }

        val tc = call<Pair<AccessType?, Map<SeatArea, AreaBounds<Float>>>> { ok, err ->
            hvac.checkHvacTemperatureCapabilities({ c: CapabilityConfig, m: Map<SeatArea, AreaBounds<Float>> -> ok(c.accessType to m) }, err)
        }
        if (tc is Res.Ok) {
            val e = tc.v.second.entries.firstOrNull(); tempArea = e?.key
            val value = e?.key?.let { a -> (call<Float?> { ok, err -> hvac.getHvacTemperature(a, TemperatureUnit.CELSIUS, ok, err) } as? Res.Ok)?.v }
            _state.update { it.copy(temp = Capability(if (e == null) AccessType.NONE else tc.v.first, value, e?.value?.minValue, e?.value?.maxValue)) }
        }

        if (rc is Res.Err && wc is Res.Err) _state.update { it.copy(lastError = rc.msg) }
        return rc is Res.Ok || wc is Res.Ok
    }

    /**
     * 환기 케어: fresh air, the window next to the child a little open, one fan step up and a slightly
     * cooler cabin. Each step is verified separately and every original
     * value is remembered for [restore].
     */
    suspend fun ventilate(): VentReport {
        val v = vehicle ?: return VentReport(listOf(CareStep("차량 연결", StepResult.UNSUPPORTED)))
        refreshControls()
        val s = _state.value
        if (original == null) original = Original(
            s.recirc?.value as? Boolean, s.window?.value as? Int, s.fan?.value as? Int, s.temp?.value as? Float, s.ambient?.value as? Int)
        val steps = mutableListOf<CareStep>()

        // 1. outside air
        steps += run {
            val area = recircArea; val cur = s.recirc?.value as? Boolean
            CareStep("바깥 공기 모드", when {
                area == null || s.recirc?.writable != true || cur == null -> StepResult.UNSUPPORTED
                !cur -> StepResult.SKIPPED_ALREADY
                else -> writeVerify({ ok, err -> v.getHVAC().setHvacRecirculation(area, false, { _, _ -> ok(Unit) }, err) },
                    { ok, err -> v.getHVAC().getHvacRecirculationState(area, ok, err) }) { it == false }
            })
        }
        // 2. window next to the child
        steps += run {
            val area = windowArea; val w = s.window; val cur = w?.value as? Int
            val target = if (w?.min != null && w.max != null && cur != null) VentRules.slightlyOpenTarget(w.min.toInt(), w.max.toInt(), cur) else null
            var after: Int? = cur
            val r = when {
                area == null || w?.writable != true || cur == null -> StepResult.UNSUPPORTED
                target == null -> StepResult.SKIPPED_ALREADY
                else -> writeVerify({ ok, err -> v.getWindow().setWindowPosition(area, target, { _, _ -> ok(Unit) }, err) },
                    { ok, err -> v.getWindow().getCurrentWindowPosition(area, { x -> after = x; ok(x) }, err) }) { it == target }
            }
            val pct = if (w?.max != null && after != null && w.max.toInt() > 0) "${after!! * 100 / w.max.toInt()}% 열림" else null
            CareStep((s.windowName ?: "창문") + " 조금 열기", r, pct)
        }
        // 3. one fan step up
        steps += run {
            val area = fanArea; val f = s.fan; val cur = f?.value as? Int; val max = f?.max?.toInt()
            CareStep("송풍 한 단계 올리기", when {
                area == null || f?.writable != true || cur == null || max == null -> StepResult.UNSUPPORTED
                cur >= max -> StepResult.SKIPPED_ALREADY
                hvacIsOff(v) -> StepResult.SKIPPED_HVAC_OFF
                else -> writeVerify({ ok, err -> v.getHVAC().setHvacFanSpeed(area, cur + 1, { _, _ -> ok(Unit) }, err) },
                    { ok, err -> v.getHVAC().getHvacFanSpeed(area, ok, err) }) { it == cur + 1 }
            }, if (cur != null && max != null && cur < max) "${cur}단 → ${cur + 1}단" else null)
        }
        // 4. slightly cooler (only from 20 °C up, never below the car's minimum)
        steps += run {
            val area = tempArea; val t = s.temp; val cur = t?.value as? Float; val min = t?.min?.toFloat()
            val target = if (cur != null && min != null) maxOf(min, cur - 1.5f) else null
            CareStep("온도 조금 낮추기", when {
                area == null || t?.writable != true || cur == null || target == null -> StepResult.UNSUPPORTED
                cur < 20f || target >= cur -> StepResult.SKIPPED_ALREADY
                else -> writeVerify({ ok, err -> v.getHVAC().setHvacTemperature(area, target, TemperatureUnit.CELSIUS, { _, _ -> ok(Unit) }, err) },
                    { ok, err -> v.getHVAC().getHvacTemperature(area, TemperatureUnit.CELSIUS, ok, err) }) { it != null && abs(it - target) < 0.3f }
            }, if (cur != null && target != null && target < cur) "%.1f℃ → %.1f℃".format(cur, target) else null)
        }
        // (Ambient-light colour was tried as a 5th step, but changing it made the Pleos emulator's
        //  system UI restart, so it is left out until it can be verified on a real car.)
        refreshControls()
        return VentReport(steps)
    }

    /** Put every changed control back to what it was before [ventilate]. */
    suspend fun restore(): Boolean {
        val v = vehicle ?: return false
        val o = original ?: return true
        var ok = true
        recircArea?.let { a -> o.recirc?.let { x -> ok = writeVerify({ s, e -> v.getHVAC().setHvacRecirculation(a, x, { _, _ -> s(Unit) }, e) },
            { s, e -> v.getHVAC().getHvacRecirculationState(a, s, e) }) { it == x } == StepResult.CONFIRMED && ok } }
        windowArea?.let { a -> o.window?.let { x -> ok = writeVerify({ s, e -> v.getWindow().setWindowPosition(a, x, { _, _ -> s(Unit) }, e) },
            { s, e -> v.getWindow().getCurrentWindowPosition(a, s, e) }) { it == x } == StepResult.CONFIRMED && ok } }
        fanArea?.let { a -> o.fan?.let { x -> if (_state.value.fan?.value != x) ok = writeVerify({ s, e -> v.getHVAC().setHvacFanSpeed(a, x, { _, _ -> s(Unit) }, e) },
            { s, e -> v.getHVAC().getHvacFanSpeed(a, s, e) }) { it == x } == StepResult.CONFIRMED && ok } }
        tempArea?.let { a -> o.temp?.let { x -> if ((_state.value.temp?.value as? Float)?.let { abs(it - x) < 0.3f } != true) ok = writeVerify({ s, e -> v.getHVAC().setHvacTemperature(a, x, TemperatureUnit.CELSIUS, { _, _ -> s(Unit) }, e) },
            { s, e -> v.getHVAC().getHvacTemperature(a, TemperatureUnit.CELSIUS, s, e) }) { it != null && abs(it - x) < 0.3f } == StepResult.CONFIRMED && ok } }
        if (ok) original = null
        refreshControls()
        return ok
    }

    /**
     * The fan only accepts a new speed while the climate system is on (error code 2 otherwise).
     * We do not switch the climate on ourselves: toggling HVAC power made the Pleos emulator's
     * system UI crash, and turning the whole climate on is the driver's decision.
     */
    private suspend fun hvacIsOff(v: Vehicle): Boolean {
        val hvac = v.getHVAC()
        val areas = call<List<SeatArea>> { ok, err -> hvac.checkHvacPowerCapabilities({ l: List<SeatArea> -> ok(l) }, err) }
        val a = (areas as? Res.Ok)?.v?.firstOrNull() ?: return false
        return (call<Boolean?> { ok, err -> hvac.getHvacPowerState(a, ok, err) } as? Res.Ok)?.v == false
    }

    val hasPendingRestore get() = original != null

    private suspend fun <T> writeVerify(
        write: ((Unit) -> Unit, (Exception) -> Unit) -> Unit,
        read: ((T?) -> Unit, (Exception) -> Unit) -> Unit,
        matches: (T?) -> Boolean,
    ): StepResult {
        if (call<Unit>(write) !is Res.Ok) return StepResult.FAILED
        // The car may take a moment to move the window / flap: read back a few times.
        repeat(4) {
            delay(1_500)
            val r = call(read)
            if (r is Res.Ok && matches(r.v)) return StepResult.CONFIRMED
        }
        return StepResult.NOT_CONFIRMED
    }

    fun stop() {
        runCatching {
            @Suppress("DEPRECATION") vehicle?.getGear()?.unregisterGearSelection(gearL)
            vehicle?.getSteeringWheel()?.unregisterSteeringWheelAngle(steerL)
            vehicle?.getBrake()?.unregisterBrakePedalStatus(brakeL)
            vehicle?.getDrivingMode()?.unregisterDrivingModeState(modeL)
            seatListeners.forEach { (a, l) -> vehicle?.getSeat()?.unregisterSeatOccupancy(a, l) }
            vehicle?.release()
        }
        vehicle = null
    }

    private sealed class Res<out T> { data class Ok<T>(val v: T) : Res<T>(); data class Err(val msg: String) : Res<Nothing>() }

    private suspend fun <T> call(block: ((T) -> Unit, (Exception) -> Unit) -> Unit): Res<T> =
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<Res<T>> { cont ->
                try {
                    block({ v -> if (cont.isActive) cont.resume(Res.Ok(v)) },
                        { e -> if (cont.isActive) cont.resume(Res.Err(e.message ?: e.javaClass.simpleName)) })
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resume(Res.Err(e.message ?: e.javaClass.simpleName))
                }
            }
        } ?: Res.Err("응답 시간 초과")

    companion object {
        private const val TAG = "AnulVehicle"

        fun windowLabel(v: WindowValue) = when (v) {
            WindowValue.ROW_1_LEFT -> "운전석 창"
            WindowValue.ROW_1_RIGHT -> "조수석 창"
            WindowValue.ROW_2_LEFT -> "뒷좌석 왼쪽 창"
            WindowValue.ROW_2_RIGHT -> "뒷좌석 오른쪽 창"
            else -> v.name
        }

        fun seatLabel(a: SeatArea): String? = a.values.firstNotNullOfOrNull {
            when (it) { SeatValue.ROW_2_LEFT -> "왼쪽"; SeatValue.ROW_2_CENTER -> "가운데"; SeatValue.ROW_2_RIGHT -> "오른쪽"; else -> null }
        }
    }
}
