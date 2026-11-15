package io.infotive.anulleong.sdk

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
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

data class Capability(val access: AccessType?, val value: Any?, val min: Int? = null, val max: Int? = null) {
    val writable get() = access == AccessType.READ_WRITE || access == AccessType.WRITE
}

data class VehicleState(
    val link: Link = Link.CONNECTING,
    val gear: String? = null,
    val steeringDeg: Float? = null,
    val brakeOn: Boolean? = null,
    val recirc: Capability? = null,      // value: Boolean (true = recirculation / inside air)
    val window: Capability? = null,      // value: Int position
    val windowName: String? = null,
    val lastError: String? = null,
)

enum class StepResult { CONFIRMED, NOT_CONFIRMED, SKIPPED_ALREADY, UNSUPPORTED, FAILED }

data class VentReport(val recirc: StepResult, val window: StepResult, val windowValue: Int?) {
    val anyConfirmed get() = recirc == StepResult.CONFIRMED || window == StepResult.CONFIRMED
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
    private var original: Pair<Boolean?, Int?>? = null

    private val gearL = object : GearSelectionListener {
        override fun onSelected(p0: VehicleGear?) { _state.update { it.copy(gear = p0?.name) } }
        override fun onFailed(e: Exception) {}
    }
    private val steerL = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(p0: Float?) { _state.update { it.copy(steeringDeg = p0) } }
        override fun onFailed(e: Exception) {}
    }
    private val brakeL = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(p0: Boolean?) { _state.update { it.copy(brakeOn = p0) } }
        override fun onFailed(e: Exception) {}
    }

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
        runCatching { @Suppress("DEPRECATION") v.getGear().registerGearSelection(gearL) }
        runCatching { v.getSteeringWheel().registerSteeringWheelAngle(steerL) }
        runCatching { v.getBrake().registerBrakePedalStatus(brakeL) }
        return true
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
        val wc = call<Pair<AccessType?, Map<WindowArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Int>>>> { ok, err ->
            win.checkWindowPositionCapabilities({ c, bounds -> ok(c.accessType to bounds) }, err)
        }
        if (wc is Res.Ok) {
            // Prefer a rear passenger window (where the child sits), then any side window.
            val prefer = listOf(WindowValue.ROW_2_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_1_LEFT)
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
        if (rc is Res.Err && wc is Res.Err) _state.update { it.copy(lastError = rc.msg) }
        return rc is Res.Ok || wc is Res.Ok
    }

    /** Fresh air + rear window open a little. Remembers original values for [restore]. */
    suspend fun ventilate(): VentReport {
        val v = vehicle ?: return VentReport(StepResult.UNSUPPORTED, StepResult.UNSUPPORTED, null)
        refreshControls()
        val s = _state.value
        if (original == null) original = (s.recirc?.value as? Boolean) to (s.window?.value as? Int)

        val recircResult = run {
            val area = recircArea
            val cur = s.recirc?.value as? Boolean
            when {
                area == null || s.recirc?.writable != true || cur == null -> StepResult.UNSUPPORTED
                !cur -> StepResult.SKIPPED_ALREADY // already fresh air
                else -> writeVerify(
                    write = { ok, err -> v.getHVAC().setHvacRecirculation(area, false, { _, _ -> ok(Unit) }, err) },
                    read = { ok, err -> v.getHVAC().getHvacRecirculationState(area, ok, err) },
                    expected = false,
                )
            }
        }
        var winValue: Int? = s.window?.value as? Int
        val windowResult = run {
            val area = windowArea
            val w = s.window
            val cur = w?.value as? Int
            val target = if (w?.min != null && w.max != null && cur != null) VentRules.slightlyOpenTarget(w.min, w.max, cur) else null
            when {
                area == null || w?.writable != true || cur == null -> StepResult.UNSUPPORTED
                target == null -> StepResult.SKIPPED_ALREADY
                else -> writeVerify(
                    write = { ok, err -> v.getWindow().setWindowPosition(area, target, { _, _ -> ok(Unit) }, err) },
                    read = { ok, err -> v.getWindow().getCurrentWindowPosition(area, { x -> winValue = x; ok(x) }, err) },
                    expected = target,
                )
            }
        }
        refreshControls()
        return VentReport(recircResult, windowResult, winValue)
    }

    /** Put recirculation and window back to what they were before [ventilate]. */
    suspend fun restore(): Boolean {
        val v = vehicle ?: return false
        val (rc, win) = original ?: return true
        var ok = true
        val ra = recircArea
        if (rc != null && ra != null) ok = writeVerify(
            { o, e -> v.getHVAC().setHvacRecirculation(ra, rc, { _, _ -> o(Unit) }, e) },
            { o, e -> v.getHVAC().getHvacRecirculationState(ra, o, e) }, rc,
        ) == StepResult.CONFIRMED && ok
        val wa = windowArea
        if (win != null && wa != null) ok = writeVerify(
            { o, e -> v.getWindow().setWindowPosition(wa, win, { _, _ -> o(Unit) }, e) },
            { o, e -> v.getWindow().getCurrentWindowPosition(wa, o, e) }, win,
        ) == StepResult.CONFIRMED && ok
        if (ok) original = null
        refreshControls()
        return ok
    }

    val hasPendingRestore get() = original != null

    private suspend fun <T> writeVerify(
        write: ((Unit) -> Unit, (Exception) -> Unit) -> Unit,
        read: ((T?) -> Unit, (Exception) -> Unit) -> Unit,
        expected: T,
    ): StepResult {
        if (call<Unit>(write) !is Res.Ok) return StepResult.FAILED
        // The car may take a moment to move the window / flap: read back a few times.
        repeat(4) {
            delay(1_500)
            val r = call(read)
            if (r is Res.Ok && r.v == expected) return StepResult.CONFIRMED
        }
        return StepResult.NOT_CONFIRMED
    }

    fun stop() {
        runCatching {
            vehicle?.getGear()?.unregisterGearSelection(gearL)
            vehicle?.getSteeringWheel()?.unregisterSteeringWheelAngle(steerL)
            vehicle?.getBrake()?.unregisterBrakePedalStatus(brakeL)
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
    }
}
