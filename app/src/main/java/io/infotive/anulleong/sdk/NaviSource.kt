package io.infotive.anulleong.sdk

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.infotive.anulleong.care.AheadEvents
import io.infotive.anulleong.care.DataSource
import io.infotive.anulleong.care.DriveSnapshot
import io.infotive.anulleong.care.Loc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Link { CONNECTING, CONNECTED, FAILED, UNAVAILABLE }

data class NaviState(
    val link: Link = Link.CONNECTING,
    val mode: String? = null,
    val routeOption: String? = null,
    val routing: Boolean = false,
    val tbt: List<AheadEvents.RawTbt> = emptyList(),
    val cautions: List<AheadEvents.RawCaution> = emptyList(),
    val remainDistanceM: Int? = null,
    val remainTimeSec: Int? = null,
    val destinationName: String? = null,
    val location: Loc? = null,
    val routeId: String? = null,
    val lastUpdateMs: Long? = null,
    val lastError: String? = null,
) {
    /** Data older than this is not shown as "live". */
    fun isFresh(nowMs: Long) = lastUpdateMs != null && nowMs - lastUpdateMs < 20_000

    fun toSnapshot(nowMs: Long): DriveSnapshot =
        if (link != Link.CONNECTED || !routing || !isFresh(nowMs)) DriveSnapshot.EMPTY.copy(location = location)
        else DriveSnapshot(
            source = DataSource.NAVI, routing = true,
            events = AheadEvents.merge(tbt, cautions),
            remainDistanceM = remainDistanceM, remainTimeSec = remainTimeSec,
            destinationName = destinationName, location = location, routeId = routeId,
        )
}

/** Wraps Pleos NaviHelper: connection lifecycle, listener callbacks and periodic refresh. */
class NaviSource(context: Context) : NaviHelperEventListener {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null
    private val _state = MutableStateFlow(NaviState())
    val state: StateFlow<NaviState> = _state

    private val poll = object : Runnable {
        override fun run() {
            helper?.let { h ->
                runCatching {
                    h.getRouteStateInfo(); h.getTBTInfo(); h.getDestinationInfo(); h.getCurrentLocationInfo()
                }.onFailure { e -> _state.update { it.copy(lastError = e.message) } }
            }
            main.postDelayed(this, 3_000)
        }
    }

    fun start() {
        try {
            helper = NaviHelper(app).also { h ->
                h.initialize({
                    h.addListener(this)
                    _state.update { it.copy(link = Link.CONNECTED, lastError = null) }
                    main.removeCallbacks(poll); main.post(poll)
                }, { err ->
                    Log.w(TAG, "navi connect failed", err)
                    _state.update { it.copy(link = Link.FAILED, lastError = err.message ?: err.javaClass.simpleName) }
                })
            }
        } catch (e: Throwable) {
            // Not a Pleos device / navi service missing: the app keeps working in demo mode.
            Log.w(TAG, "navi unavailable", e)
            _state.update { it.copy(link = Link.UNAVAILABLE, lastError = e.message ?: e.javaClass.simpleName) }
        }
    }

    fun stop() {
        main.removeCallbacks(poll)
        runCatching { helper?.removeListener(this); helper?.release() }
        helper = null
    }

    /** Coordinate-only request (verified on the emulator: request and response coordinates matched). */
    fun requestRoute(lat: Double, lon: Double, name: String) {
        runCatching {
            helper?.requestRoute(RouteInfo(latitude = lat, longitude = lon, poiId = "", poiName = name, poiSubId = "", address = ""))
        }.onFailure { e -> _state.update { it.copy(lastError = e.message) } }
    }

    /** Ask the navigation to recalculate with its "comfortable" route option. */
    fun changeRouteOption(option: RouteOption) {
        runCatching { helper?.changeRouteOption(option) }
            .onFailure { e -> _state.update { it.copy(lastError = e.message) } }
    }

    fun cancelRoute() {
        runCatching { helper?.cancelRoute() }
        _state.update { it.copy(routing = false, tbt = emptyList(), cautions = emptyList()) }
    }

    private fun touch(block: (NaviState) -> NaviState) =
        _state.update { block(it).copy(lastUpdateMs = System.currentTimeMillis()) }

    override fun onRouteStateInfo(info: RouteStateInfo) = touch {
        // Verified on the emulator: while the navi showed an active Seoul Station route,
        // naviMode was ROUTE_GUIDING but isRouting was false. isRouting is therefore not
        // "guidance active"; the guidance mode is.
        val mode = info.naviMode?.name
        val routing = mode == "ROUTE_GUIDING" || mode == "SIMULATION_GUIDING"
        it.copy(mode = info.naviMode?.name, routeOption = info.routeOption?.name, routing = routing,
            tbt = if (routing) it.tbt else emptyList(), cautions = if (routing) it.cautions else emptyList())
    }

    override fun onTBTInfo(info: List<TBTInfo>) = touch {
        it.copy(tbt = info.map { t -> AheadEvents.RawTbt(t.type?.name ?: "TURN_UNKNOWN", t.distance, t.description) })
    }

    override fun onCautionPointInfo(info: List<CautionPoint>) = touch {
        it.copy(cautions = info.map { c -> AheadEvents.RawCaution(c.id ?: "", c.type?.name ?: "UNKNOWN", c.remainDistance) })
    }

    override fun onDestinationInfo(info: DestinationInfo) = touch {
        it.copy(remainDistanceM = info.remainDistance, remainTimeSec = info.remainTime, destinationName = info.name)
    }

    override fun onCurrentLocationInfo(info: CurrentLocationInfo) = touch {
        it.copy(location = Loc(info.latitude, info.longitude, info.heading, System.currentTimeMillis()), routeId = info.routeId)
    }

    override fun onError(code: NaviErrorCode) {
        _state.update { it.copy(lastError = "내비 오류: ${code.name}") }
    }

    companion object { private const val TAG = "AnulNavi" }
}
