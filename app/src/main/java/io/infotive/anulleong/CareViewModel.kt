package io.infotive.anulleong

import ai.pleos.playground.navi.constants.RouteOption
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
import io.infotive.anulleong.care.EventKind
import io.infotive.anulleong.care.ExposureStore
import io.infotive.anulleong.care.Feeling
import io.infotive.anulleong.care.josa
import io.infotive.anulleong.care.KindSensitivity
import io.infotive.anulleong.care.PassTracker
import io.infotive.anulleong.care.RecordRules
import io.infotive.anulleong.care.RecordStore
import io.infotive.anulleong.care.RevisitDetector
import io.infotive.anulleong.care.RouteCompare
import io.infotive.anulleong.care.RouteStats
import io.infotive.anulleong.care.SimilarityModel
import io.infotive.anulleong.care.ZoneAnalysis
import io.infotive.anulleong.sdk.ListenState
import io.infotive.anulleong.sdk.NaviSource
import io.infotive.anulleong.sdk.NaviState
import io.infotive.anulleong.sdk.VehicleSource
import io.infotive.anulleong.sdk.VehicleState
import io.infotive.anulleong.sdk.VentReport
import io.infotive.anulleong.sdk.VoiceGuide
import io.infotive.anulleong.sdk.VoiceInput
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

/** Why the care sheet is asking: after a 🤢, or ahead of a known/similar sick section. */
enum class CareReason { SICK, AHEAD }

/** Driving smoothness over the last 5 minutes, from steering-rate and brake-press events. */
data class DriveStyle(val sharpSteers: Int, val brakePresses: Int, val mode: String?, val synthetic: Boolean) {
    val score: Int get() = (100 - sharpSteers * 10 - maxOf(0, brakePresses - 5) * 4).coerceIn(0, 100)
    val rough: Boolean get() = sharpSteers >= 2 || brakePresses >= 9
}

data class UiState(
    val snapshot: DriveSnapshot = DriveSnapshot.EMPTY,
    val navi: NaviState = NaviState(),
    val vehicle: VehicleState = VehicleState(),
    val voice: VoiceState = VoiceState(),
    val listen: ListenState = ListenState(),
    val demoOn: Boolean = false,
    val voiceOn: Boolean = true,
    val records: List<CareRecord> = emptyList(),
    val cue: Cue? = null,
    val revisit: CareRecord? = null,
    val vent: VentPhase = VentPhase.IDLE,
    val ventReason: CareReason = CareReason.SICK,
    val ventAheadTitle: String? = null,
    val ventReport: VentReport? = null,
    val canRestore: Boolean = false,
    val message: String? = null,
    val nowMs: Long = System.currentTimeMillis(),
    /** Largest remaining distance seen on this route = trip length, for the progress bar. */
    val routeTotalM: Int? = null,
    val demoTrip: Int = 1,
    val demoArrived: Boolean = false,
    val demoAlerted: Boolean = false,
    /** A 유사 구간 warning fired on a demo trip (step 4 of the guide). */
    val demoSimilarAlerted: Boolean = false,
    val demoRoad: DemoScenario.Road = DemoScenario.Road.USUAL,
    val routeCompare: RouteCompare? = null,
    val revisitCount: Int = 0,
    /** Per-kind sensitivity for the current data source (유사 구간 예측). */
    val sensitivities: List<KindSensitivity> = emptyList(),
    val driveStyle: DriveStyle = DriveStyle(0, 0, null, false),
    /** Suggest a break when the child got sick twice on this trip and a rest area is ahead. */
    val restHint: AheadEvent? = null,
) {
    val driving get() = vehicle.gear == "GEAR_DRIVE" && snapshot.source == DataSource.NAVI
}

class CareViewModel(app: Application) : AndroidViewModel(app) {
    private val store = RecordStore(app)
    private val exposure = ExposureStore(app)
    private val navi = NaviSource(app)
    private val vehicle = VehicleSource(app, viewModelScope)
    private val voice = VoiceGuide(app)
    private val listen = VoiceInput(app)
    private val cuePolicy = CuePolicy()
    private val revisit = RevisitDetector()
    private val passes = PassTracker()
    private var demoStartMs = 0L
    private var demoTrip = 1
    private var demoAlerted = false
    private var demoSimilarAlerted = false
    private var demoRoad = DemoScenario.Road.USUAL
    private var revisitAt = 0L
    private var totalRouteKey: String? = null
    private var totalM: Int? = null
    private var recentlyPassed: Pair<AheadEvent, Long>? = null
    private var passCounts: Map<DataSource, Map<EventKind, Int>> = emptyMap()
    private val askedAhead = HashSet<String>()
    private var coachedAt = 0L
    private var lastSource: DataSource? = null
    private var naviCompareBefore: RouteStats? = null
    private var naviCompareSig: String? = null
    private var naviCompareAt = 0L
    private val saveVersion = java.util.concurrent.atomic.AtomicLong()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    init {
        navi.start(); vehicle.start(); voice.start(); listen.start()
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { store.load() }
            val counts = withContext(Dispatchers.IO) { DataSource.values().associateWith { exposure.load(it) } }
            passCounts = counts
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
        if (base.source != lastSource) { passes.reset(); lastSource = base.source }

        // Sections just driven through: exposure counts for 유사 구간 예측 (+ demo driving events).
        if (base.source != DataSource.NONE) passes.update(base.events).forEach { e ->
            recentlyPassed = e to now
            if (e.kind in SimilarityModel.LEARNABLE) {
                val src = base.source
                passCounts = passCounts + (src to ((passCounts[src] ?: emptyMap()) + (e.kind to ((passCounts[src]?.get(e.kind) ?: 0) + 1))))
                viewModelScope.launch(Dispatchers.IO) { exposure.add(src, e.kind) }
            }
            if (base.source == DataSource.DEMO && (e.kind == EventKind.SHARP_TURN || e.kind == EventKind.BUMP)) {
                vehicle.injectSyntheticEvent(steer = e.kind == EventKind.SHARP_TURN)
            }
        }
        val sens = SimilarityModel.sensitivities(s.records, passCounts[base.source] ?: emptyMap(), base.source)
        val flagged = sens.filter { it.flagged }.map { it.kind }.toSet()

        // The family's own past 🤢 spots that lie ahead become pins on the road; sections of a
        // kind this child is sensitive to are flagged as 유사 구간.
        val here = RecordRules.usableLocation(base.location, now)
        // Pins and revisit alerts only while a route is actually being driven.
        val pastAhead = if (base.routing) revisit.ahead(here, s.records, now, base.source, base.routeId) else emptyList()
        val withPins = if (pastAhead.isEmpty()) base.events else
            (base.events + pastAhead.take(2).map { AheadEvents.fromPastRecord(it.record.id, it.distanceM) })
                .sortedBy { it.distanceM ?: Int.MAX_VALUE }
        val snap = base.copy(events = SimilarityModel.annotate(withPins, flagged))

        var cue = s.cue?.takeIf { now - it.atMs < 12_000 }
        cuePolicy.pick(snap.events, now)?.let { e ->
            val spoken = s.voiceOn && voice.speak(e.kidLine)
            cue = Cue(e, now, spoken)
            if (e.similarRisk && base.source == DataSource.DEMO) demoSimilarAlerted = true
        }
        var rv = s.revisit?.takeIf { now - revisitAt < 25_000 }
        (if (base.routing) revisit.check(here, s.records, now, base.source, base.routeId) else null)?.let { r ->
            rv = r
            revisitAt = now
            if (base.source == DataSource.DEMO) demoAlerted = true
            val n2 = ZoneAnalysis.countNear(r, s.records)
            if (s.voiceOn) voice.speak(if (n2 > 1) "이 근처에서 ${n2}번 멀미가 났었어요. 미리 창밖 먼 곳을 봐요."
                                       else "지난번 이 근처에서 멀미가 났었어요. 미리 창밖 먼 곳을 봐요.")
        }

        // 선제 케어: 600–900 m before a known sick spot or a sensitive section, offer fresh air once.
        var aheadOffer: String? = null   // only set when 선제 케어 fires, so a 🤢 sheet opened meanwhile is never overwritten
        if (s.vent == VentPhase.IDLE && !vehicle.hasPendingRestore) {
            snap.events.firstOrNull { (it.kind == EventKind.PAST_SICK || it.similarRisk) && (it.distanceM ?: -1) in 600..900 && it.key !in askedAhead }
                ?.let { e ->
                    askedAhead += e.key
                    val title = if (e.kind == EventKind.PAST_SICK) "지난번 멀미 난 곳" else e.title
                    aheadOffer = title
                    if (s.voiceOn) voice.speak("곧 ${josa(title, "이", "가")} 나와요. 미리 시원한 바람을 넣어 둘까요?")
                }
        }

        // 휴게소 제안: two 🤢 on this trip and a rest area within 5 km.
        val sickThisTrip = s.records.count { it.feeling == Feeling.SICK && it.routeId == base.routeId && it.source == base.source && now - it.timeMs < 3 * 3600_000 }
        val restHint = if (sickThisTrip >= 2) snap.events.firstOrNull { it.kind == EventKind.REST && (it.distanceM ?: Int.MAX_VALUE) <= 5_000 } else null

        // Real navi comparison: fill in the "after" side once the TBT list changed (or after 15 s).
        var compare = s.routeCompare
        if (naviCompareBefore != null && compare != null && compare.to == null) {
            val sig = routeSig(n.tbt)
            if ((sig != naviCompareSig && n.tbt.isNotEmpty()) || now - naviCompareAt > 15_000) {
                val after = RouteStats.of(AheadEvents.merge(n.tbt, n.cautions), null, n.remainDistanceM, flagged)
                compare = compare.copy(to = after, applied = true, note = if (sig == naviCompareSig)
                    "내비가 같은 경로를 다시 안내했어요. 지금 길이 이미 가장 편안한 길일 수 있어요." else compare.note)
                naviCompareBefore = null
            }
        }

        val routeKey = "${snap.source}:${snap.routeId ?: snap.destinationName}"
        if (routeKey != totalRouteKey) { totalRouteKey = routeKey; totalM = null }
        snap.remainDistanceM?.let { r -> totalM = maxOf(totalM ?: 0, r) }
        if (snap.source == DataSource.DEMO) totalM = demoRoad.lengthM

        val v = vehicle.state.value
        val style = DriveStyle(v.steerEvents.count { now - it < 5 * 60_000 }, v.brakeEvents.count { now - it < 5 * 60_000 },
            v.drivingMode, synthetic = base.source == DataSource.DEMO)

        _ui.update { cur ->
            val it = if (aheadOffer != null && cur.vent == VentPhase.IDLE)
                cur.copy(vent = VentPhase.ASKING, ventReason = CareReason.AHEAD, ventAheadTitle = aheadOffer) else cur
            it.copy(snapshot = snap, navi = n, routeTotalM = totalM, vehicle = v, voice = voice.state.value, listen = listen.state.value,
                cue = cue, revisit = rv, canRestore = vehicle.hasPendingRestore, nowMs = now,
                revisitCount = rv?.let { r -> ZoneAnalysis.countNear(r, s.records) } ?: 0,
                routeCompare = compare, demoRoad = demoRoad, sensitivities = sens, driveStyle = style, restHint = restHint,
                demoTrip = demoTrip, demoAlerted = demoAlerted, demoSimilarAlerted = demoSimilarAlerted,
                demoArrived = s.demoOn && DemoScenario.arrived((now - demoStartMs) / 1000, demoRoad))
        }
    }

    fun recordFeeling(f: Feeling, byVoice: Boolean = false) {
        val now = System.currentTimeMillis()
        val s = _ui.value
        if (RecordRules.isDuplicate(s.records.lastOrNull(), f, now)) {
            _ui.update { it.copy(message = "방금 같은 기록을 남겼어요 (중복 저장 안 함)") }
            return
        }
        val loc = RecordRules.usableLocation(s.snapshot.location, now)
        val v = vehicle.state.value
        // Attribute the record to the section just passed (≤60 s) or right ahead (≤300 m).
        val cause = SimilarityModel.attribute(recentlyPassed, s.snapshot.events, now)
        val rec = CareRecord(
            id = UUID.randomUUID().toString(), timeMs = now, feeling = f,
            source = s.snapshot.source, lat = loc?.lat, lon = loc?.lon, heading = loc?.heading,
            routeId = s.snapshot.routeId, steeringDeg = v.steeringDeg, brakeOn = v.brakeOn, gear = v.gear,
            nextEventTitle = cause?.title, cueKind = cause?.kind,
        )
        val list = s.records + rec
        persist(list)
        val msg = when (f) {
            Feeling.OK -> "좋아요! 기록했어요."
            Feeling.UNEASY -> "기록했어요. 앞 창문 먼 곳을 보면 도움이 될 수 있어요."
            Feeling.SICK -> "기록했어요" + (cause?.let { " (${it.title} 근처)" } ?: "") + ". 환기를 도와드릴까요?"
        } + (if (byVoice) " · 음성으로 받았어요" else "") + (if (loc == null) " · 위치 정보 없음" else "")
        _ui.update {
            it.copy(records = list, message = msg,
                vent = if (f == Feeling.SICK && it.vent != VentPhase.WORKING) VentPhase.ASKING else it.vent,
                ventReason = if (f == Feeling.SICK) CareReason.SICK else it.ventReason)
        }
        if (s.voiceOn) voice.speak(when (f) {
            Feeling.OK -> "좋아요!"
            Feeling.UNEASY -> "괜찮아요. 고개를 들고 앞 창문 먼 곳을 봐요."
            Feeling.SICK -> "괜찮아요. 시원한 바람을 넣어 줄까요?"
        })
        // 운전 코칭: when the child is sick, ask the driver once (per 10 min) to drive gently.
        if (f == Feeling.SICK && now - coachedAt > 10 * 60_000) {
            val st = s.driveStyle
            val line = when {
                st.mode == "SPORT" -> "운전자님, 지금은 스포츠 모드예요. 일반이나 에코 모드가 승차감이 더 부드러워요."
                st.rough -> "운전자님, 아이가 멀미 중이에요. 핸들과 브레이크를 조금만 부드럽게 다뤄 주세요."
                else -> null
            }
            if (line != null) { coachedAt = now; if (s.voiceOn) voice.speak(line); _ui.update { it.copy(message = line) } }
        }
    }

    /** "말로 알리기": listen once and record the matched feeling, or explain what was heard. */
    fun listenFeeling() {
        val started = listen.listen { feeling, heard ->
            if (feeling != null) recordFeeling(feeling, byVoice = true)
            else _ui.update { it.copy(message = if (heard.isBlank()) "잘 듣지 못했어요. 버튼을 눌러 알려 주세요"
                                                  else "‘$heard’로 들었어요. 기분 버튼을 눌러 알려 주세요") }
        }
        if (!started) _ui.update { it.copy(message = "음성 인식을 쓸 수 없어요. 버튼을 눌러 알려 주세요") }
    }

    fun cancelListen() = listen.cancel()

    fun ventilate() {
        if (_ui.value.vent == VentPhase.WORKING) return
        _ui.update { it.copy(vent = VentPhase.WORKING, ventReport = null) }
        viewModelScope.launch {
            val r = vehicle.ventilate()
            val msg = when {
                r.anyConfirmed -> "환기를 시작했어요 (차량 상태로 확인됨)"
                r.steps.all { it.result == io.infotive.anulleong.sdk.StepResult.SKIPPED_ALREADY || it.result == io.infotive.anulleong.sdk.StepResult.UNSUPPORTED } -> "이미 바람이 잘 통하는 상태예요"
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
        persist(emptyList())
        viewModelScope.launch(Dispatchers.IO) { DataSource.values().forEach { exposure.clear(it) } }
        passCounts = emptyMap()
        _ui.update { it.copy(records = emptyList(), revisit = null, message = "모든 기록을 지웠어요") }
    }

    fun toggleDemo() {
        if (!_ui.value.demoOn) { restartDemo(); return }
        _ui.update { it.copy(demoOn = false, cue = null, revisit = null, routeCompare = null, message = "시연 모드를 껐어요") }
    }

    /** Drive the same synthetic road again; spots recorded on earlier trips now raise alerts. */
    fun nextDemoTrip() {
        startDemoTrip(demoTrip + 1, DemoScenario.Road.USUAL)
        _ui.update { it.copy(cue = null, revisit = null, message = "${demoTrip}번째 여행을 시작해요. 기록한 곳에 가까워지면 알려 줄게요") }
    }

    /** Start the demo over: removes demo-only records and demo exposure, keeps real ones. */
    fun restartDemo() {
        val list = _ui.value.records.filterNot { it.source == DataSource.DEMO }
        persist(list)
        viewModelScope.launch(Dispatchers.IO) { exposure.clear(DataSource.DEMO) }
        passCounts = passCounts - DataSource.DEMO
        startDemoTrip(1)
        _ui.update { it.copy(records = list, routeCompare = null, cue = null, revisit = null, demoOn = true,
            message = "시연을 시작해요. 가상 경로로 동작하고, 이전 시연 기록은 지웠어요") }
    }

    private fun startDemoTrip(trip: Int, road: DemoScenario.Road = DemoScenario.Road.USUAL) {
        demoTrip = trip
        demoRoad = road
        demoAlerted = false
        demoSimilarAlerted = false
        demoStartMs = System.currentTimeMillis()
        revisit.reset()
        revisitAt = 0L
        askedAhead.clear()
        passes.reset()
        recentlyPassed = null
    }

    /**
     * "멀미가 덜 나는 길": compare the current route with a calmer one by 속편한 점수.
     * Demo: both synthetic roads are fully known, so the comparison is complete.
     * Real navi: the navigation is asked to recalculate with its COMFORTABLE option; the family
     * can keep it or go back to the original option with one tap.
     */
    fun compareRoutes() {
        val s = _ui.value
        val zones = ZoneAnalysis.zones(s.records)
        val flagged = s.sensitivities.filter { it.flagged }.map { it.kind }.toSet()
        if (s.snapshot.source == DataSource.DEMO) {
            _ui.update { it.copy(routeCompare = RouteCompare(
                fromLabel = DemoScenario.Road.USUAL.label, toLabel = DemoScenario.Road.COMFORT.label,
                from = DemoScenario.stats(DemoScenario.Road.USUAL, zones, flagged),
                to = DemoScenario.stats(DemoScenario.Road.COMFORT, zones, flagged), synthetic = true)) }
            return
        }
        val n = navi.state.value
        if (n.routeOption == "COMFORTABLE") {
            _ui.update { it.copy(message = "이미 내비의 ‘편안한 길’ 옵션으로 안내 중이에요") }
            return
        }
        val before = RouteStats.of(AheadEvents.merge(n.tbt, n.cautions), null, n.remainDistanceM, flagged)
        naviCompareBefore = before
        naviCompareSig = routeSig(n.tbt)
        naviCompareAt = System.currentTimeMillis()
        navi.changeRouteOption(RouteOption.COMFORTABLE)
        _ui.update { it.copy(routeCompare = RouteCompare(
            fromLabel = "지금 길 (${optionLabel(n.routeOption)})", toLabel = "편안한 길", from = before, to = null,
            synthetic = false, originalOption = n.routeOption,
            note = "내비가 남은 길의 회전 안내만 알려 주므로 앞쪽 구간 기준으로 비교해요.")) }
    }

    /** Real navi: go back to the route option used before the comparison. */
    fun revertRoute() {
        val opt = _ui.value.routeCompare?.originalOption
        val option = RouteOption.values().firstOrNull { it.name == opt } ?: RouteOption.RECOMMENDED
        navi.changeRouteOption(option)
        _ui.update { it.copy(routeCompare = null, message = "원래 길(${optionLabel(option.name)})로 다시 안내해요") }
    }

    /** Demo: drive the calmer road on the next trip. */
    fun takeComfortRoad() {
        startDemoTrip(demoTrip + 1, DemoScenario.Road.COMFORT)
        _ui.update { it.copy(cue = null, revisit = null, routeCompare = null, message = "편안한 길로 ${demoTrip}번째 여행을 시작해요") }
    }

    fun dismissCompare() = _ui.update { it.copy(routeCompare = null) }

    private fun optionLabel(o: String?) = when (o) {
        "RECOMMENDED" -> "추천"; "SHORT_DISTANCE" -> "최단 거리"; "SHORT_TIME" -> "최소 시간"; "FREE" -> "무료 도로"
        "HIGHWAY" -> "고속도로"; "COMFORTABLE" -> "편안한 길"; "SCHOOL_ZONE" -> "어린이보호구역 회피"; else -> o ?: "기본"
    }

    fun toggleVoice() = _ui.update { it.copy(voiceOn = !it.voiceOn) }

    fun requestSampleRoute() {
        navi.requestRoute(37.554722, 126.970833, "서울역")
        _ui.update { it.copy(message = "내비에 서울역 길 안내를 요청했어요") }
    }

    fun cancelRoute() { navi.cancelRoute(); _ui.update { it.copy(message = "길 안내를 취소했어요") } }

    fun retryNavi() { navi.stop(); navi.start(); _ui.update { it.copy(message = "내비에 다시 연결하고 있어요") } }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    private fun persist(list: List<CareRecord>) {
        val v = saveVersion.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            // Skip stale writes if a newer list was queued meanwhile.
            synchronized(store) { if (v == saveVersion.get()) store.save(list) }
        }
    }

    override fun onCleared() {
        navi.stop(); vehicle.stop(); voice.stop(); listen.stop()
    }
}

/** Route identity without the live distances (they change every second while driving). */
private fun routeSig(tbt: List<AheadEvents.RawTbt>): String =
    tbt.joinToString("|") { it.typeName + ":" + (it.description ?: "") }
