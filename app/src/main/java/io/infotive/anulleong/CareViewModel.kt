package io.infotive.anulleong

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.infotive.anulleong.care.AheadEvent
import io.infotive.anulleong.care.AheadEvents
import io.infotive.anulleong.care.CareRecord
import io.infotive.anulleong.care.CuePolicy
import io.infotive.anulleong.care.DataSource
import io.infotive.anulleong.care.DemoScenario
import io.infotive.anulleong.care.DriveSnapshot
import io.infotive.anulleong.care.Feeling
import io.infotive.anulleong.care.RecordRules
import io.infotive.anulleong.care.RecordStore
import io.infotive.anulleong.care.RevisitDetector
import io.infotive.anulleong.care.RouteCompare
import io.infotive.anulleong.care.RouteStats
import io.infotive.anulleong.care.ZoneAnalysis
import io.infotive.anulleong.sdk.NaviSource
import io.infotive.anulleong.sdk.NaviState
import io.infotive.anulleong.sdk.StepResult
import io.infotive.anulleong.sdk.VehicleSource
import io.infotive.anulleong.sdk.VehicleState
import io.infotive.anulleong.sdk.VentReport
import io.infotive.anulleong.sdk.VoiceGuide
import io.infotive.anulleong.sdk.VoiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class Cue(val event: AheadEvent, val atMs: Long, val spoken: Boolean)

enum class VentPhase { IDLE, ASKING, WORKING, DONE }

data class UiState(
    val snapshot: DriveSnapshot = DriveSnapshot.EMPTY,
    val navi: NaviState = NaviState(),
    val vehicle: VehicleState = VehicleState(),
    val voice: VoiceState = VoiceState(),
    val demoOn: Boolean = false,
    val voiceOn: Boolean = true,
    val records: List<CareRecord> = emptyList(),
    val cue: Cue? = null,
    val revisit: CareRecord? = null,
    val vent: VentPhase = VentPhase.IDLE,
    val ventReport: VentReport? = null,
    val canRestore: Boolean = false,
    val message: String? = null,
    val nowMs: Long = System.currentTimeMillis(),
    /** Largest remaining distance seen on this route = trip length, for the progress bar. */
    val routeTotalM: Int? = null,
    /** Demo trip number (1 = record a spot, 2+ = drive past it again). */
    val demoTrip: Int = 1,
    val demoArrived: Boolean = false,
    /** True once a record-based alert fired on a later demo trip (the demo's goal). */
    val demoAlerted: Boolean = false,
    /** Which synthetic road the demo is on. */
    val demoRoad: DemoScenario.Road = DemoScenario.Road.USUAL,
    /** Side-by-side comparison of the current route and a calmer alternative. */
    val routeCompare: RouteCompare? = null,
    /** How many 🤢 records sit around the spot behind the current revisit alert. */
    val revisitCount: Int = 0,
)

class CareViewModel(app: Application) : AndroidViewModel(app) {
    private val store = RecordStore(app)
    private val navi = NaviSource(app)
    private val vehicle = VehicleSource(app, viewModelScope)
    private val voice = VoiceGuide(app)
    private val cuePolicy = CuePolicy()
    private val revisit = RevisitDetector()
    private var demoStartMs = 0L
    private var demoTrip = 1
    private var demoAlerted = false
    private var demoRoad = DemoScenario.Road.USUAL
    /** Real navi: stats captured before asking for the comfortable option, and when. */
    private var naviCompareBefore: RouteStats? = null
    private var naviCompareSig: String? = null
    private var naviCompareAt = 0L
    private var revisitAt = 0L
    private var totalRouteKey: String? = null
    private var totalM: Int? = null
    private val saveVersion = java.util.concurrent.atomic.AtomicLong()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    init {
        navi.start(); vehicle.start(); voice.start()
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { store.load() }
            _ui.update { it.copy(records = loaded) }
        }
        viewModelScope.launch { while (isActive) { tick(); delay(1_000) } }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val n = navi.state.value
        val live = n.toSnapshot(now)
        val s = _ui.value
        // Real navigation always wins; the synthetic demo is used only when no live route exists.
        val base = when {
            live.source == DataSource.NAVI -> live
            s.demoOn -> DemoScenario.snapshot((now - demoStartMs) / 1000, now, demoTrip, demoRoad)
            else -> live
        }
        // The family's own past 🤢 spots that lie ahead become pins on the road.
        val here = RecordRules.usableLocation(base.location, now)
        val pastAhead = revisit.ahead(here, s.records, now, base.source, base.routeId)
        val snap = if (pastAhead.isEmpty()) base else base.copy(
            events = (base.events + pastAhead.take(2).map { AheadEvents.fromPastRecord(it.record.id, it.distanceM) })
                .sortedBy { it.distanceM ?: Int.MAX_VALUE })
        var cue = s.cue?.takeIf { now - it.atMs < 12_000 }
        cuePolicy.pick(snap.events, now)?.let { e ->
            val spoken = s.voiceOn && voice.speak(e.kidLine)
            cue = Cue(e, now, spoken)
        }
        var rv = s.revisit?.takeIf { now - revisitAt < 25_000 }
        revisit.check(here, s.records, now, base.source, base.routeId)?.let { r ->
            rv = r
            revisitAt = now
            if (base.source == DataSource.DEMO) demoAlerted = true
            val n2 = ZoneAnalysis.countNear(r, s.records)
            if (s.voiceOn) voice.speak(if (n2 > 1) "이 근처에서 ${n2}번 멀미가 났었어요. 미리 창밖 먼 곳을 봐요."
                                       else "지난번 이 근처에서 멀미가 났었어요. 미리 창밖 먼 곳을 봐요.")
        }
        // Real navi comparison: fill in the "after" side once the TBT list changed (or after 15 s).
        var compare = s.routeCompare
        val before = naviCompareBefore
        if (before != null && compare != null && compare.to == null) {
            val sig = n.tbt.joinToString { it.typeName + it.distance }
            if ((sig != naviCompareSig && n.tbt.isNotEmpty()) || now - naviCompareAt > 15_000) {
                val after = RouteStats.of(AheadEvents.merge(n.tbt, n.cautions), null, n.remainDistanceM)
                compare = compare.copy(to = after, note = if (sig == naviCompareSig)
                    "내비가 같은 경로를 다시 안내했어요. 이 길이 이미 가장 편안한 길일 수 있어요." else compare.note)
                naviCompareBefore = null
            }
        }
        val routeKey = "${snap.source}:${snap.routeId ?: snap.destinationName}"
        if (routeKey != totalRouteKey) { totalRouteKey = routeKey; totalM = null }
        snap.remainDistanceM?.let { r -> totalM = maxOf(totalM ?: 0, r) }
        if (snap.source == DataSource.DEMO) totalM = demoRoad.lengthM
        _ui.update {
            it.copy(snapshot = snap, navi = n, routeTotalM = totalM, vehicle = vehicle.state.value, voice = voice.state.value,
                cue = cue, revisit = rv, canRestore = vehicle.hasPendingRestore, nowMs = now,
                revisitCount = rv?.let { r -> ZoneAnalysis.countNear(r, s.records) } ?: 0,
                routeCompare = compare, demoRoad = demoRoad,
                demoTrip = demoTrip, demoAlerted = demoAlerted,
                demoArrived = s.demoOn && DemoScenario.arrived((now - demoStartMs) / 1000, demoRoad))
        }
    }


    fun recordFeeling(f: Feeling) {
        val now = System.currentTimeMillis()
        val s = _ui.value
        if (RecordRules.isDuplicate(s.records.lastOrNull(), f, now)) {
            _ui.update { it.copy(message = "방금 같은 기록을 남겼어요 (중복 저장 안 함)") }
            return
        }
        val loc = RecordRules.usableLocation(s.snapshot.location, now)
        val v = vehicle.state.value
        val rec = CareRecord(
            id = UUID.randomUUID().toString(), timeMs = now, feeling = f,
            source = s.snapshot.source, lat = loc?.lat, lon = loc?.lon, heading = loc?.heading,
            routeId = s.snapshot.routeId, steeringDeg = v.steeringDeg, brakeOn = v.brakeOn, gear = v.gear,
            nextEventTitle = s.snapshot.events.firstOrNull()?.title,
        )
        val list = s.records + rec
        persist(list)
        val msg = when (f) {
            Feeling.OK -> "좋아요! 기록했어요."
            Feeling.UNEASY -> "기록했어요. 앞 창문 먼 곳을 보면 도움이 될 수 있어요."
            Feeling.SICK -> "기록했어요. 환기를 도와드릴까요?"
        } + if (loc == null) " (위치 정보 없음)" else ""
        _ui.update {
            it.copy(records = list, message = msg,
                vent = if (f == Feeling.SICK && it.vent != VentPhase.WORKING) VentPhase.ASKING else it.vent)
        }
        if (s.voiceOn) voice.speak(when (f) {
            Feeling.OK -> "좋아요!"
            Feeling.UNEASY -> "괜찮아요. 고개를 들고 앞 창문 먼 곳을 봐요."
            Feeling.SICK -> "괜찮아요. 시원한 바람을 넣어 줄까요?"
        })
    }

    fun ventilate() {
        if (_ui.value.vent == VentPhase.WORKING) return
        _ui.update { it.copy(vent = VentPhase.WORKING, ventReport = null) }
        viewModelScope.launch {
            val r = vehicle.ventilate()
            val msg = when {
                r.anyConfirmed -> "환기를 시작했어요 (차량 상태로 확인됨)"
                r.recirc == StepResult.SKIPPED_ALREADY && r.window == StepResult.SKIPPED_ALREADY -> "이미 바깥 공기가 들어오고 창문도 열려 있어요"
                else -> "차량에서 확인되지 않았어요. 직접 창문을 조금 열어 주세요"
            }
            if (_ui.value.voiceOn && r.anyConfirmed) voice.speak("시원한 바람이 들어와요. 천천히 숨 쉬어요.")
            _ui.update { it.copy(vent = VentPhase.DONE, ventReport = r, message = msg, canRestore = vehicle.hasPendingRestore) }
        }
    }

    fun dismissVent() = _ui.update { it.copy(vent = VentPhase.IDLE, ventReport = null) }

    fun restoreVent() {
        viewModelScope.launch {
            val ok = vehicle.restore()
            _ui.update {
                it.copy(vent = VentPhase.IDLE, ventReport = null, canRestore = vehicle.hasPendingRestore,
                    message = if (ok) "공조와 창문을 원래대로 돌려놨어요 (확인됨)" else "원래대로 돌리지 못했어요. 직접 확인해 주세요")
            }
        }
    }

    fun deleteRecord(id: String) {
        val list = _ui.value.records.filterNot { it.id == id }
        persist(list); _ui.update { it.copy(records = list, message = "기록을 지웠어요") }
    }

    fun clearRecords() {
        persist(emptyList()); _ui.update { it.copy(records = emptyList(), revisit = null, message = "모든 기록을 지웠어요") }
    }

    fun toggleDemo() {
        val on = !_ui.value.demoOn
        if (on) startDemoTrip(1)
        _ui.update { it.copy(demoOn = on, cue = null, revisit = null, routeCompare = null,
            message = if (on) "시연 모드: 가상 경로로 동작해요 (실제 길 안내가 오면 실제가 우선)" else "시연 모드를 껐어요") }
    }

    /** Drive the same synthetic road again; spots recorded on earlier trips now raise alerts. */
    fun nextDemoTrip() {
        startDemoTrip(demoTrip + 1, DemoScenario.Road.USUAL)
        _ui.update { it.copy(cue = null, revisit = null, message = "${demoTrip}번째 여행을 시작해요. 기록한 곳에 가까워지면 알려 줄게요") }
    }

    /** Start the demo over: removes demo-only records, keeps real ones. */
    fun restartDemo() {
        val list = _ui.value.records.filterNot { it.source == DataSource.DEMO }
        persist(list)
        startDemoTrip(1)
        _ui.update { it.copy(records = list, routeCompare = null, cue = null, revisit = null, demoOn = true, message = "시연을 처음부터 다시 시작해요 (시연 기록만 지웠어요)") }
    }

    private fun startDemoTrip(trip: Int, road: DemoScenario.Road = DemoScenario.Road.USUAL) {
        demoTrip = trip
        demoRoad = road
        demoAlerted = false
        demoStartMs = System.currentTimeMillis()
        revisit.reset()
        revisitAt = 0L
    }

    /**
     * "멀미가 덜 나는 길": compare the current route with a calmer one.
     * Demo: both synthetic roads are fully known, so the comparison is complete.
     * Real navi: the current TBT list is scored, then the navigation is asked to recalculate
     * with its COMFORTABLE option and the new TBT list is scored when it arrives.
     */
    fun compareRoutes() {
        val s = _ui.value
        val zones = ZoneAnalysis.zones(s.records)
        if (s.snapshot.source == DataSource.DEMO) {
            _ui.update { it.copy(routeCompare = RouteCompare(
                fromLabel = DemoScenario.Road.USUAL.label, toLabel = DemoScenario.Road.COMFORT.label,
                from = DemoScenario.stats(DemoScenario.Road.USUAL, zones),
                to = DemoScenario.stats(DemoScenario.Road.COMFORT, zones), synthetic = true)) }
            return
        }
        val n = navi.state.value
        if (n.routeOption == "COMFORTABLE") {
            _ui.update { it.copy(message = "이미 내비의 ‘편안한 길’ 옵션으로 안내 중이에요") }
            return
        }
        val before = RouteStats.of(AheadEvents.merge(n.tbt, n.cautions), null, n.remainDistanceM)
        naviCompareBefore = before
        naviCompareSig = n.tbt.joinToString { it.typeName + it.distance }
        naviCompareAt = System.currentTimeMillis()
        navi.changeRouteOption(ai.pleos.playground.navi.constants.RouteOption.COMFORTABLE)
        _ui.update { it.copy(routeCompare = RouteCompare(
            fromLabel = "지금 길 (${optionLabel(n.routeOption)})", toLabel = "편안한 길", from = before, to = null,
            synthetic = false, note = "실제 경로의 모양은 SDK가 주지 않아서 지난 멀미 구간 통과 여부는 1.5km 앞까지만 확인돼요.")) }
    }

    /** Demo: drive the calmer road on the next trip. */
    fun takeComfortRoad() {
        startDemoTrip(demoTrip + 1, DemoScenario.Road.COMFORT)
        _ui.update { it.copy(cue = null, revisit = null, routeCompare = null, message = "편안한 길로 ${demoTrip}번째 여행을 시작해요") }
    }

    fun dismissCompare() = _ui.update { it.copy(routeCompare = null) }

    private fun optionLabel(o: String?) = when (o) {
        "RECOMMENDED" -> "추천"; "SHORT_DISTANCE" -> "최단 거리"; "SHORT_TIME" -> "최소 시간"; "FREE" -> "무료 도로"
        "HIGHWAY" -> "고속도로"; "COMFORTABLE" -> "편안한 길"; "SCHOOL_ZONE" -> "어린이보호구역 회피"; else -> o ?: "-"
    }

    fun toggleVoice() = _ui.update { it.copy(voiceOn = !it.voiceOn) }

    fun requestSampleRoute() {
        navi.requestRoute(37.554722, 126.970833, "서울역")
        _ui.update { it.copy(message = "내비에 서울역 경로를 요청했어요") }
    }

    fun cancelRoute() { navi.cancelRoute(); _ui.update { it.copy(message = "경로 안내를 취소했어요") } }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    private fun persist(list: List<CareRecord>) {
        val v = saveVersion.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            // Skip stale writes if a newer list was queued meanwhile.
            synchronized(store) { if (v == saveVersion.get()) store.save(list) }
        }
    }

    override fun onCleared() {
        navi.stop(); vehicle.stop(); voice.stop()
    }
}
