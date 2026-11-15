package io.infotive.anulleong.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.infotive.anulleong.CareReason
import io.infotive.anulleong.CareViewModel
import io.infotive.anulleong.UiState
import io.infotive.anulleong.VentPhase
import io.infotive.anulleong.care.josa
import io.infotive.anulleong.care.ieyo
import io.infotive.anulleong.care.AheadEvent
import io.infotive.anulleong.care.CareRecord
import io.infotive.anulleong.care.DataSource
import io.infotive.anulleong.care.DemoScenario
import io.infotive.anulleong.care.EventKind
import io.infotive.anulleong.care.Feeling
import io.infotive.anulleong.care.ZoneAnalysis
import io.infotive.anulleong.sdk.Link
import io.infotive.anulleong.sdk.ListenStatus
import io.infotive.anulleong.sdk.StepResult
import io.infotive.anulleong.sdk.VoiceStatus
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(ui: UiState, vm: CareViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(ui.message) { if (ui.message != null) { delay(4_000); vm.clearMessage() } }

    BoxWithConstraints(Modifier.fillMaxSize().background(C.sky)) {
        val wide = maxWidth > 840.dp
        if (wide) {
            // Car display: navigation rail on the left (thumb-reachable from the driver seat).
            Row(Modifier.fillMaxSize()) {
                SideRail(tab, ui.records.count { it.feeling == Feeling.SICK }) { tab = it }
                Box(Modifier.weight(1f).fillMaxHeight().statusBarsPadding()) { Tab(tab, ui, vm, wide = true) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) { Tab(tab, ui, vm, wide = false) }
                BottomBar(tab, ui.records.count { it.feeling == Feeling.SICK }) { tab = it }
            }
        }
        AnimatedVisibility(ui.message != null, Modifier.align(Alignment.BottomCenter).padding(bottom = if (wide) 28.dp else 96.dp),
            enter = fadeIn(), exit = fadeOut()) {
            Text(ui.message ?: "", color = Color.White, fontSize = 17.sp, lineHeight = 23.sp,
                modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 640.dp).clip(RoundedCornerShape(16.dp)).background(C.ink)
                    .padding(horizontal = 22.dp, vertical = 14.dp))
        }
    }
    CareSheet(ui, vm)
}

@Composable
private fun Tab(tab: Int, ui: UiState, vm: CareViewModel, wide: Boolean) = when (tab) {
    0 -> JourneyTab(ui, vm, wide)
    1 -> RecordsTab(ui, vm)
    else -> ConnectTab(ui, vm)
}

// ───────────────────────────────────────── 여정

@Composable
private fun JourneyTab(ui: UiState, vm: CareViewModel, wide: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Keep the road visible together with the feeling buttons on short screens.
        val heroH = minOf(if (wide) 460.dp else maxWidth - 32.dp, maxHeight * if (wide) 0.62f else 0.5f, 460.dp)
        if (wide) {
            Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Header(ui)
                    Hero(ui, vm, Modifier.fillMaxWidth().height(heroH))
                    UpcomingAndEta(ui, vm)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    FeelingPicker(ui, vm)
                    // What the family just asked for comes first, then status, then the demo guide.
                    RouteCompareCard(ui, vm); RestHint(ui); VentStatus(ui, vm)
                    DemoGuide(ui, vm); DemoInvite(ui, vm); DriveCoach(ui)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Spacer(Modifier.height(4.dp)); Header(ui)
                Hero(ui, vm, Modifier.fillMaxWidth().height(heroH))
                FeelingPicker(ui, vm)
                RouteCompareCard(ui, vm); RestHint(ui); VentStatus(ui, vm)
                DemoGuide(ui, vm); DemoInvite(ui, vm); UpcomingAndEta(ui, vm); DriveCoach(ui)
                Footnote()
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun Header(ui: UiState) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("안울렁", fontFamily = Jua, fontSize = 28.sp, color = C.periwinkle)
        Spacer(Modifier.width(12.dp))
        ui.vehicle.childSeat?.let { Chip("뒷좌석 $it 탑승", C.inkSoft) }
        Spacer(Modifier.weight(1f))
        val (label, color) = when (ui.snapshot.source) {
            DataSource.NAVI -> "실시간 내비" to C.mint
            DataSource.DEMO -> "시연용 합성 경로" to C.lilac
            DataSource.NONE -> "길 안내 없음" to C.inkSoft
        }
        Row(Modifier.clip(CircleShape).background(C.paper).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 15.sp, color = C.ink, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) =
    Text(text, fontSize = 16.sp, color = color, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(C.paper).padding(horizontal = 12.dp, vertical = 7.dp))

@Composable
private fun Hero(ui: UiState, vm: CareViewModel, modifier: Modifier) {
    val snap = ui.snapshot
    val next = snap.events.firstOrNull()
    val headline = when {
        next != null -> (next.distanceM?.let { "${distText(it)} 뒤\n" } ?: "다음은\n") + heroVerb(next)
        snap.source == DataSource.NONE && ui.navi.link == Link.CONNECTED -> "목적지를 정하면\n앞길을 그려 줄게요"
        snap.source == DataSource.NONE -> "내비에 연결되지\n않았어요"
        snap.source == DataSource.DEMO && !snap.routing -> "도착했어요!"
        else -> "한동안\n쭉 가요"
    }
    val sub = when {
        next != null -> next.kidLine
        snap.source == DataSource.NONE && ui.navi.link != Link.CONNECTED -> "아래 ‘시연 시작’으로 기능을 미리 체험할 수 있어요."
        snap.source == DataSource.NONE -> "내비에서 길 안내를 시작하면 돌아가는 길과 덜컹하는 곳을 미리 알려 줘요."
        snap.source == DataSource.DEMO && !snap.routing -> "시연 가이드에서 다음 여행을 시작해 보세요."
        else -> "창밖 먼 곳을 보며 편하게 쉬어요."
    }
    RoadHero(
        events = snap.events, moving = snap.routing,
        highlightKey = ui.cue?.event?.key, headline = headline, sub = sub,
        modifier = modifier.clip(RoundedCornerShape(28.dp)),
    ) { HeroAlerts(ui, vm) }
}

/** The most important, location-triggered alerts sit on top of the road itself. */
@Composable
private fun BoxScope.HeroAlerts(ui: UiState, vm: CareViewModel) {
    val r = ui.revisit
    val similar = ui.cue?.event?.takeIf { it.similarRisk && r == null }
    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.7f).padding(16.dp)) {
        if (r != null) AlertCard(
            icon = { EventIcon(AheadEvents_past, Color.White, 30.dp) },
            title = if (ui.revisitCount > 1) "이 근처에서 ${ui.revisitCount}번 멀미가 났어요" else "지난번 이 근처에서 멀미가 났어요",
            body = "${dateText(r.timeMs)} 기록 · 미리 창밖 먼 곳을 봐요",
            bg = C.lilacDeep,
            action = if (ui.routeCompare == null) ("덜 나는 길 찾기" to { vm.compareRoutes() }) else null,
        )
        if (similar != null) AlertCard(
            icon = { EventIcon(similar, Color.White, 30.dp) },
            title = "유사 구간 예측: ${similar.title}",
            body = "이 아이는 ${io.infotive.anulleong.care.SimilarityModel.label(similar.kind)}에서 멀미한 적이 있어요",
            bg = C.ink,
            action = null,
        )
    }
}

private val AheadEvents_past = io.infotive.anulleong.care.AheadEvents.fromPastRecord("icon", 0)

@Composable
private fun AlertCard(icon: @Composable () -> Unit, title: String, body: String, bg: Color, action: Pair<String, () -> Unit>?) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(20.dp)).background(bg).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontFamily = Jua, fontSize = 19.sp, color = Color.White, lineHeight = 25.sp)
                Text(body, fontSize = 15.sp, color = Color.White, lineHeight = 21.sp)
            }
        }
        if (action != null) { Spacer(Modifier.height(10.dp)); Pill(action.first, C.paper, C.ink, action.second) }
    }
}

private fun heroVerb(e: AheadEvent) = when (e.kind) {
    EventKind.TURN -> when { e.title.startsWith("왼") -> "왼쪽으로 돌아요"; e.title.startsWith("오른") -> "오른쪽으로 돌아요"; else -> "방향을 바꿔요" }
    EventKind.SHARP_TURN -> if (e.title == "회전교차로") "빙글 돌아요" else "크게 돌아요"
    EventKind.RAMP -> "구불길로 가요"
    EventKind.MERGE -> "살짝 옆으로 가요"
    EventKind.SLOWDOWN -> "천천히 가요"
    EventKind.BUMP -> "방지턱 덜컹!"
    EventKind.DOWNHILL -> "내리막이에요"
    EventKind.ROUGH_ROAD -> "울퉁불퉁해요"
    EventKind.NARROW -> "좁은 길이에요"
    EventKind.REST -> "휴게소가 있어요"
    EventKind.ARRIVE -> "도착해요!"
    EventKind.PAST_SICK -> "지난번 멀미 난 곳"
}

@Composable
private fun FeelingPicker(ui: UiState, vm: CareViewModel) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.paper).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("지금 기분은 어때?", fontFamily = Jua, fontSize = 24.sp, color = C.ink, modifier = Modifier.weight(1f))
            val listening = ui.listen.status == ListenStatus.LISTENING
            val canListen = ui.listen.status == ListenStatus.IDLE || ui.listen.status == ListenStatus.ERROR
            if (listening) Pill("듣는 중… 그만", C.berry, Color.White) { vm.cancelListen() }
            else if (canListen) Pill("🎙 말로 알리기", C.sky, C.ink) { vm.listenFeeling() }
        }
        if (ui.listen.status == ListenStatus.LISTENING) {
            Text("“멀미 나요”, “속이 안 좋아”, “괜찮아”처럼 말해 주세요" + (ui.listen.partial?.let { " · 들린 말: $it" } ?: ""),
                fontSize = 15.sp, color = C.inkSoft, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Feeling.values().forEach { f ->
                val color = feelColor(f)
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clip(RoundedCornerShape(22.dp)).clickable { vm.recordFeeling(f) }.padding(8.dp)) {
                    Box(Modifier.size(if (f == Feeling.SICK) 104.dp else 92.dp).clip(CircleShape).background(color.copy(alpha = .16f))
                        .border(4.dp, color, CircleShape), contentAlignment = Alignment.Center) {
                        Text(f.emoji, fontSize = if (f == Feeling.SICK) 50.sp else 44.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(f.label, fontSize = 18.sp, color = C.ink, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun RestHint(ui: UiState) {
    val r = ui.restHint ?: return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.mint).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        EventIcon(r, Color.White, 34.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text("${r.distanceM?.let(::distText) ?: "곧"} 뒤 휴게소에서 쉬어 가요", fontFamily = Jua, fontSize = 20.sp, color = Color.White)
            Text("이번 여행에서 멀미가 두 번 이상 있었어요. 잠깐 내려서 바깥 공기를 마시면 좋아요.", fontSize = 15.sp, color = Color.White)
        }
    }
}

@Composable
private fun VentStatus(ui: UiState, vm: CareViewModel) {
    if (!ui.canRestore || ui.vent != VentPhase.IDLE) return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.paper).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("🌬️", fontSize = 28.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("환기 케어 중", fontSize = 18.sp, color = C.ink, fontWeight = FontWeight.Bold)
            Text("아이가 괜찮아지면 공조와 창문을 원래대로 돌려요", fontSize = 15.sp, color = C.inkSoft)
        }
        Pill("원래대로", C.periwinkle, Color.White) { vm.restoreVent() }
    }
}

@Composable
private fun DriveCoach(ui: UiState) {
    if (ui.snapshot.source == DataSource.NONE) return
    val st = ui.driveStyle
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.paper).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("운전 부드러움", fontFamily = Jua, fontSize = 20.sp, color = C.ink, modifier = Modifier.weight(1f))
            if (st.synthetic) Badge("시연 값")
            Spacer(Modifier.width(8.dp))
            Text(if (st.rough) "조금 거칠어요" else "부드러워요", fontFamily = Jua, fontSize = 24.sp, color = if (st.rough) C.berry else C.mint)
        }
        Text("최근 5분 · 급한 핸들 ${st.sharpSteers}회 · 브레이크 ${st.brakePresses}회" +
            (st.mode?.let { " · ${modeLabel(it)} 모드" } ?: ""), fontSize = 15.sp, color = C.inkSoft)
        if (st.rough || st.mode == "SPORT") Text(
            if (st.mode == "SPORT") "일반·에코 모드가 승차감이 더 부드러워요" else "핸들과 브레이크를 조금 더 부드럽게 다뤄 보세요",
            fontSize = 15.sp, color = C.berry, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun UpcomingAndEta(ui: UiState, vm: CareViewModel) {
    val snap = ui.snapshot
    val rest = snap.events.drop(1).take(4)
    if (rest.isEmpty() && snap.remainDistanceM == null) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.paper).padding(20.dp)) {
        if (snap.remainDistanceM != null || snap.remainTimeSec != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("도착까지", fontSize = 16.sp, color = C.inkSoft)
                Spacer(Modifier.width(8.dp))
                val min = (snap.remainTimeSec ?: 0) / 60
                Text(if (min >= 1) "${min}분" else "곧 도착", fontFamily = Jua, fontSize = 30.sp, color = C.ink)
                Spacer(Modifier.width(8.dp))
                Text(snap.remainDistanceM?.let(::distText) ?: "", fontSize = 16.sp, color = C.inkSoft, modifier = Modifier.padding(bottom = 4.dp))
                Spacer(Modifier.weight(1f))
                if (snap.source != DataSource.NONE && ui.routeCompare == null) Pill("멀미가 덜 나는 길 찾기", C.mint, Color.White) { vm.compareRoutes() }
            }
            val total = ui.routeTotalM
            if (total != null && total > 0 && snap.remainDistanceM != null) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { (1f - snap.remainDistanceM.toFloat() / total).coerceIn(0f, 1f) },
                    color = C.periwinkle, trackColor = C.sky,
                    modifier = Modifier.fillMaxWidth().height(12.dp).clip(CircleShape))
            }
        }
        if (rest.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("그다음에는", fontSize = 15.sp, color = C.inkSoft, fontWeight = FontWeight.SemiBold)
            rest.forEach { e ->
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(eventColor(e).copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                        EventIcon(e, eventColor(e), 24.dp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(e.title, fontSize = 18.sp, color = C.ink, modifier = Modifier.weight(1f))
                    if (e.similarRisk) { Badge("아이가 약한 유형"); Spacer(Modifier.width(8.dp)) }
                    Text(e.distanceM?.let(::distText) ?: "", fontSize = 16.sp, color = C.inkSoft)
                }
            }
        }
    }
}

@Composable
private fun Footnote() {
    Text("앞길 그림은 내비게이션의 회전 안내와 주의 지점(과속방지턱·내리막 등)으로 그려요. 멀미 확률을 계산하지는 않아요.",
        fontSize = 16.sp, lineHeight = 22.sp, color = C.inkSoft, modifier = Modifier.padding(horizontal = 4.dp))
}

// ───────────────────────────────────────── 시연 가이드

/** Shown when nothing is driving: one tap starts the guided demo. */
@Composable
private fun DemoInvite(ui: UiState, vm: CareViewModel) {
    if (ui.demoOn || ui.snapshot.source != DataSource.NONE) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.paper).padding(20.dp)) {
        Text("길 안내 없이 체험해 보기", fontFamily = Jua, fontSize = 22.sp, color = C.ink)
        Spacer(Modifier.height(6.dp))
        Text("가상 길을 세 번 달려요. 🤢을 남기면 다음 여행에서 그곳을 미리 알려 주고, 멀미가 덜 나는 길과 처음 가는 길의 비슷한 구간까지 알려 줘요.",
            fontSize = 16.sp, lineHeight = 23.sp, color = C.inkSoft)
        Spacer(Modifier.height(14.dp))
        Pill("시연 시작", C.lilacDeep, Color.White) { vm.toggleDemo() }
    }
}

/** Step-by-step guide so a judge can see record → alert → calmer route → prediction. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DemoGuide(ui: UiState, vm: CareViewModel) {
    if (!ui.demoOn || ui.snapshot.source != DataSource.DEMO) return
    val recorded = ui.records.any { it.source == DataSource.DEMO && it.feeling == Feeling.SICK }
    val calm = ui.demoRoad == DemoScenario.Road.COMFORT
    val firstTrip = ui.demoTrip == 1
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.lilac.copy(alpha = .14f))
        .border(2.dp, C.lilac.copy(alpha = .5f), RoundedCornerShape(24.dp)).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("시연 · ${ui.demoTrip}번째 여행" + if (calm) " (편안한 길)" else "", fontFamily = Jua, fontSize = 22.sp, color = C.ink, modifier = Modifier.weight(1f))
            Text(if (ui.demoArrived) "도착" else "달리는 중", fontSize = 15.sp, color = C.lilacDeep, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        GuideStep(1, "🤢 멀미 나요를 눌러 그 자리와 구간을 기록해요", recorded, active = !recorded)
        GuideStep(2, "다음 여행에서 그 자리에 가까워지면 미리 알려 줘요", ui.demoAlerted || calm, active = recorded && !ui.demoAlerted && !calm)
        GuideStep(3, "속편한 점수로 길을 비교하고 편안한 길로 가요", calm, active = ui.demoAlerted && !calm)
        GuideStep(4, "처음 가는 길에서도 비슷한 구간을 미리 알려 줘요", ui.demoSimilarAlerted, active = calm && !ui.demoSimilarAlerted)
        Spacer(Modifier.height(10.dp))
        val hint = when {
            !recorded && !ui.demoArrived -> "회전교차로를 지나면 🤢을 눌러 보세요. 그 자리와 구간 유형이 기록돼요."
            !recorded -> "기록 없이 도착했어요. ‘처음부터’를 눌러 다시 달려 보세요."
            firstTrip -> "기록했어요! ‘다음 여행 시작’을 누르면 같은 길을 다시 달려요."
            !calm && !ui.demoAlerted -> "📍 지난번 멀미 난 곳이 길 위로 다가와요. 900m 앞에서 미리 환기를 제안하고 400m 앞에서 알려요."
            !calm -> "‘덜 나는 길 찾기’를 눌러 두 길의 속편한 점수를 비교해 보세요."
            !ui.demoSimilarAlerted -> "처음 가는 편안한 길에도 회전교차로가 있어요. 아이가 약한 유형이라 미리 알려 줄 거예요."
            else -> "같은 장소가 아니어도, 아이가 약한 유형의 구간을 미리 알려 줬어요."
        }
        Text(hint, fontSize = 16.sp, lineHeight = 23.sp, color = C.ink)
        Spacer(Modifier.height(14.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (recorded && !calm && (firstTrip || ui.demoArrived)) Pill("다음 여행 시작", C.lilacDeep, Color.White) { vm.nextDemoTrip() }
            if (ui.demoAlerted && !calm && ui.routeCompare == null) Pill("덜 나는 길 찾기", C.mint, Color.White) { vm.compareRoutes() }
            Pill("처음부터", C.paper, C.ink) { vm.restartDemo() }
            Pill("시연 끄기", C.paper, C.inkSoft) { vm.toggleDemo() }
        }
    }
}

@Composable
private fun GuideStep(n: Int, text: String, done: Boolean, active: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(if (done) C.mint else if (active) C.lilacDeep else C.line),
            contentAlignment = Alignment.Center) {
            Text(if (done) "✓" else "$n", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 17.sp, color = if (done) C.inkSoft else C.ink,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
    }
}

// ───────────────────────────────────────── 속편한 길 비교

@Composable
private fun RouteCompareCard(ui: UiState, vm: CareViewModel) {
    val c = ui.routeCompare ?: return
    val t = c.to
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.paper)
        .border(2.dp, C.mint.copy(alpha = .6f), RoundedCornerShape(24.dp)).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("멀미가 덜 나는 길", fontFamily = Jua, fontSize = 23.sp, color = C.ink, modifier = Modifier.weight(1f))
            if (c.synthetic) Badge("시연용 합성 경로")
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ScoreTile(c.fromLabel, c.from.comfortScore, highlight = false, Modifier.weight(1f))
            ScoreTile(c.toLabel, t?.comfortScore, highlight = t != null && t.comfortScore > c.from.comfortScore, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        val verdict = when {
            t == null -> "내비가 ‘편안한 길’ 옵션으로 다시 찾는 중이에요…"
            t.comfortScore > c.from.comfortScore -> "${josa("‘${c.toLabel}’", "이", "가")} ${t.comfortScore - c.from.comfortScore}점 더 속편해요" +
                (if ((c.from.sickZones ?: 0) > (t.sickZones ?: 0)) ". 지난 멀미 구간도 피해 가요." else ".")
            t.comfortScore == c.from.comfortScore -> "두 길의 속편한 점수가 같아요."
            else -> "지금 길이 더 속편해요."
        }
        Text(verdict, fontSize = 18.sp, lineHeight = 24.sp, color = C.ink, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        // Decision first, details below: the buttons must be reachable without scrolling.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                c.synthetic && t != null -> { Pill("편안한 길로 가기", C.mint, Color.White) { vm.takeComfortRoad() }; Pill("닫기", C.sky, C.ink) { vm.dismissCompare() } }
                c.applied -> { Pill("이 길로 계속", C.mint, Color.White) { vm.dismissCompare() }; Pill("원래 길로", C.sky, C.ink) { vm.revertRoute() } }
                else -> Pill("원래 길로", C.sky, C.ink) { vm.revertRoute() }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row { Spacer(Modifier.weight(1.4f)); ColHead(c.fromLabel, C.inkSoft); ColHead(c.toLabel, C.mint) }
        CompareRow("급회전·회전교차로", c.from.sharpTurns, t?.sharpTurns)
        CompareRow("진출입로", c.from.ramps, t?.ramps)
        CompareRow("방지턱·내리막", c.from.bumps, t?.bumps)
        CompareRow("아이가 약한 유형", c.from.similar, t?.similar)
        CompareRow("지난 멀미 구간", c.from.sickZones, t?.sickZones, unknown = "앞쪽만 확인")
        CompareRow("거리", c.from.distanceM, t?.distanceM, lowerIsBetter = null, format = { distText(it) })
        Text("속편한 점수(0~100)는 1km당 급회전·진출입로·방지턱 수, 지난 멀미 구간, 아이가 약한 유형으로 계산한 길 비교 값이에요. 멀미 확률이 아니에요." +
            (c.note?.let { " $it" } ?: ""), fontSize = 16.sp, lineHeight = 22.sp, color = C.inkSoft, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ScoreTile(label: String, score: Int?, highlight: Boolean, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(if (highlight) C.mint.copy(alpha = .14f) else C.sky).padding(16.dp)) {
        Text(label, fontSize = 15.sp, color = if (highlight) C.mint else C.inkSoft, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(score?.toString() ?: "…", fontFamily = Jua, fontSize = 44.sp, color = if (highlight) C.mint else C.ink)
            Text(" 점", fontSize = 16.sp, color = C.inkSoft, modifier = Modifier.padding(bottom = 8.dp))
        }
        Text("속편한 점수", fontSize = 16.sp, color = C.inkSoft)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ColHead(t: String, color: Color) =
    Text(t, fontSize = 16.sp, color = color, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))

@Composable
private fun CompareRow(label: String, a: Int?, b: Int?, unknown: String = "-", lowerIsBetter: Boolean? = true, format: (Int) -> String = { "$it" }) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp, color = C.ink, modifier = Modifier.weight(1.4f))
        Text(a?.let(format) ?: unknown, fontSize = 16.sp, color = C.inkSoft, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        val better = lowerIsBetter == true && a != null && b != null && b < a
        Text(b?.let(format) ?: unknown, fontSize = 16.sp, color = if (better) C.mint else C.ink,
            fontWeight = if (better) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

// ───────────────────────────────────────── 케어 바텀시트

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CareSheet(ui: UiState, vm: CareViewModel) {
    if (ui.vent == VentPhase.IDLE) return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { if (ui.vent != VentPhase.WORKING) vm.dismissVent() }, sheetState = state,
        containerColor = C.paper) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(bottom = 28.dp).navigationBarsPadding()
            .verticalScroll(rememberScrollState())) {
            val v = ui.vehicle
            when (ui.vent) {
                VentPhase.ASKING -> {
                    Text("🌬️", fontSize = 44.sp)
                    Text(if (ui.ventReason == CareReason.AHEAD) "곧 ${ieyo(ui.ventAheadTitle ?: "멀미 구간")}. 미리 바람을 넣을까요?"
                         else "시원한 바람을 넣을까요?", fontFamily = Jua, fontSize = 27.sp, color = C.ink)
                    Spacer(Modifier.height(8.dp))
                    Text("바깥 공기 모드, ${v.windowName ?: "뒷좌석 창문"} 조금 열기, 송풍 한 단계 올리기, 온도 조금 낮추기를 차에 요청해요. " +
                        "끝나면 차 상태를 다시 읽어서 정말 바뀌었는지 확인해요." + (v.childSeat?.let { " (아이가 앉은 $it 자리 기준)" } ?: ""),
                        fontSize = 16.sp, lineHeight = 23.sp, color = C.inkSoft)
                    Spacer(Modifier.height(22.dp))
                    BigButton("바람 넣기", C.periwinkle, Color.White) { vm.ventilate() }
                    Spacer(Modifier.height(10.dp))
                    BigButton("괜찮아요", C.sky, C.ink) { vm.dismissVent() }
                }
                VentPhase.WORKING -> {
                    Text("차에 요청하는 중이에요", fontFamily = Jua, fontSize = 25.sp, color = C.ink)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(color = C.periwinkle, trackColor = C.sky, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape))
                    Spacer(Modifier.height(10.dp))
                    Text("명령을 보낸 뒤 차 상태를 다시 읽어 확인하고 있어요.", fontSize = 16.sp, color = C.inkSoft)
                }
                VentPhase.DONE -> {
                    val r = ui.ventReport
                    Text(if (r?.anyConfirmed == true) "바람이 들어와요" else "차에서 확인되지 않았어요", fontFamily = Jua, fontSize = 27.sp, color = C.ink)
                    Spacer(Modifier.height(12.dp))
                    r?.steps?.forEach { StepLine(it.label, it.result, it.detail) }
                    Spacer(Modifier.height(20.dp))
                    BigButton("확인", C.periwinkle, Color.White) { vm.dismissVent() }
                    if (ui.canRestore) { Spacer(Modifier.height(10.dp)); BigButton("원래대로 돌리기", C.sky, C.ink) { vm.restoreVent() } }
                }
                VentPhase.IDLE -> {}
            }
        }
    }
}

@Composable
private fun StepLine(name: String, r: StepResult, extra: String?) {
    val (txt, color) = when (r) {
        StepResult.CONFIRMED -> "바뀐 것 확인" to C.mint
        StepResult.NOT_CONFIRMED -> "요청했지만 그대로예요" to C.sun
        StepResult.SKIPPED_ALREADY -> "이미 그렇게 되어 있어요" to C.inkSoft
        StepResult.SKIPPED_HVAC_OFF -> "공조가 꺼져 있어 건너뛰었어요" to C.inkSoft
        StepResult.UNSUPPORTED -> "이 차는 지원하지 않아요" to C.inkSoft
        StepResult.FAILED -> "실패했어요. 직접 조작해 주세요" to C.berry
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Text(name, fontSize = 18.sp, color = C.ink, modifier = Modifier.weight(1f))
        Text(txt + (if (r == StepResult.CONFIRMED && extra != null) " · $extra" else ""), fontSize = 17.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

// ───────────────────────────────────────── 기록

@Composable
private fun RecordsTab(ui: UiState, vm: CareViewModel) {
    var confirmClear by remember { mutableStateOf(false) }
    val recs = ui.records
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 880.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("멀미 기록과 분석", fontFamily = Jua, fontSize = 30.sp, color = C.ink)
            Text("이 기기 안에만 저장돼요. 음성은 녹음하지 않아요.", fontSize = 15.sp, color = C.inkSoft)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Feeling.values().reversed().forEach { f ->
                    val n = recs.count { it.feeling == f }
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(feelColor(f).copy(alpha = .14f)).padding(16.dp)) {
                        Text(f.emoji, fontSize = 24.sp)
                        Text("$n", fontFamily = Jua, fontSize = 34.sp, color = C.ink)
                        Text(f.label, fontSize = 15.sp, color = C.inkSoft)
                    }
                }
            }

            // 유사 구간 예측: kinds this child is sensitive to.
            if (ui.sensitivities.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("아이가 힘들어하는 구간 유형", fontFamily = Jua, fontSize = 22.sp, color = C.ink)
                Text("멀미 기록 수 ÷ 그 유형을 지나간 수예요. 30% 이상이면 처음 가는 길에서도 미리 알려 줘요.", fontSize = 15.sp, color = C.inkSoft)
                Spacer(Modifier.height(8.dp))
                ui.sensitivities.take(6).forEach { k ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(18.dp)).background(C.paper).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(k.label, fontSize = 18.sp, color = C.ink, fontWeight = FontWeight.Bold)
                                if (k.flagged) { Spacer(Modifier.width(8.dp)); Badge("미리 알림") }
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { k.rate }, color = if (k.flagged) C.berry else C.sun, trackColor = C.sky,
                                modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape))
                        }
                        Spacer(Modifier.width(16.dp))
                        Text("${k.sick} / ${k.passed}회", fontSize = 17.sp, color = C.inkSoft, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            val zones = ZoneAnalysis.zones(recs)
            if (zones.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("자주 멀미 나는 장소", fontFamily = Jua, fontSize = 22.sp, color = C.ink)
                Text("300m 안에서 🤢을 누른 기록을 한 장소로 묶었어요. 다시 지날 때 미리 알려 줘요.", fontSize = 15.sp, color = C.inkSoft)
                Spacer(Modifier.height(8.dp))
                zones.take(5).forEachIndexed { i, z ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(18.dp)).background(C.paper).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(52.dp).clip(CircleShape).background(C.berry.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                            Text("${z.count}번", fontSize = 16.sp, color = C.berry, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("장소 ${i + 1}" + (z.commonCue?.let { " · ‘$it’ 근처" } ?: ""), fontSize = 18.sp, color = C.ink, fontWeight = FontWeight.Bold)
                            Text("최근 ${dateText(z.lastMs)}" + if (z.source == DataSource.DEMO) " · 시연 기록" else "", fontSize = 15.sp, color = C.inkSoft)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            if (recs.isEmpty()) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.paper).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🚗", fontSize = 44.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("아직 기록이 없어요", fontFamily = Jua, fontSize = 22.sp, color = C.ink)
                    Text("아이가 불편하다고 하면 ‘여정’에서 얼굴 버튼을 누르거나 ‘말로 알리기’를 써 주세요. 기록이 쌓이면 장소와 구간 유형을 분석해요.",
                        fontSize = 16.sp, lineHeight = 23.sp, color = C.inkSoft, textAlign = TextAlign.Center)
                }
            } else {
                Text("모든 기록", fontFamily = Jua, fontSize = 22.sp, color = C.ink)
                var lastDay = ""
                recs.asReversed().take(100).forEach { r ->
                    val day = SimpleDateFormat("M월 d일 EEEE", Locale.KOREA).format(Date(r.timeMs))
                    if (day != lastDay) {
                        Text(day, fontSize = 15.sp, color = C.inkSoft, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                        lastDay = day
                    }
                    RecordItem(r, enabled = !ui.driving) { vm.deleteRecord(r.id) }
                }
                Spacer(Modifier.height(12.dp))
                if (!ui.driving) Pill("기록 모두 지우기", C.paper, C.berry) { confirmClear = true }
                else Text("주행 중에는 기록을 지울 수 없어요", fontSize = 15.sp, color = C.inkSoft)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("기록을 모두 지울까요?") },
        text = { Text("저장된 기록 ${recs.size}개와 구간 분석이 지워지고 되돌릴 수 없어요.") },
        confirmButton = { TextButton({ vm.clearRecords(); confirmClear = false }) { Text("모두 지우기", color = C.berry) } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("취소") } },
        containerColor = C.paper,
    )
}

@Composable
private fun RecordItem(r: CareRecord, enabled: Boolean, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(18.dp)).background(C.paper).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(feelColor(r.feeling).copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            Text(r.feeling.emoji, fontSize = 26.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(r.timeMs)), fontSize = 18.sp, color = C.ink, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(r.feeling.label, fontSize = 17.sp, color = C.ink)
                if (r.source == DataSource.DEMO) { Spacer(Modifier.width(10.dp)); Badge("시연") }
            }
            Text(listOfNotNull(r.nextEventTitle?.let { "$it 근처" }, if (r.lat == null) "위치 없음" else null)
                .ifEmpty { listOf("구간 정보 없음") }.joinToString(", "), fontSize = 15.sp, color = C.inkSoft)
        }
        if (enabled) Pill("지우기", C.sky, C.inkSoft, onDelete)
    }
}

// ───────────────────────────────────────── 연결

@Composable
private fun ConnectTab(ui: UiState, vm: CareViewModel) {
    val n = ui.navi; val v = ui.vehicle; val vo = ui.voice
    var dev by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 880.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("설정과 차량", fontFamily = Jua, fontSize = 30.sp, color = C.ink)
            Group {
                ToggleRow("음성으로 알려 주기", "안내를 화면과 함께 소리로도 들려줘요", ui.voiceOn, !ui.driving) { vm.toggleVoice() }
                ToggleRow("시연 모드", "실제 길 안내가 없을 때 가상 경로로 기능을 보여줘요. 화면에 ‘시연용 합성 경로’가 표시돼요.", ui.demoOn, !ui.driving) { vm.toggleDemo() }
                if (n.link == Link.CONNECTED) Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("테스트 길 안내", fontSize = 18.sp, color = C.ink, fontWeight = FontWeight.SemiBold)
                        Text(if (n.routing) "길 안내 중이에요" else "내비에 서울역까지 길 안내를 요청해요", fontSize = 15.sp, color = C.inkSoft)
                    }
                    if (n.routing) Pill("안내 취소", C.berry, Color.White) { vm.cancelRoute() } else Pill("서울역 안내", C.periwinkle, Color.White) { vm.requestSampleRoute() }
                }
                if (ui.driving) Text("주행 중에는 설정을 바꿀 수 없어요", fontSize = 15.sp, color = C.inkSoft, modifier = Modifier.padding(bottom = 8.dp))
            }
            Group("이 차에서 쓰는 기능") {
                Feature("내비 앞길 안내", linkText(n.link), n.link == Link.CONNECTED) {
                    if (n.link == Link.FAILED || n.link == Link.UNAVAILABLE) Pill("다시 연결", C.periwinkle, Color.White) { vm.retryNavi() }
                }
                Feature("뒷좌석 탑승 감지", v.rearSeats.takeIf { it.isNotEmpty() }?.let { m -> m.entries.joinToString { "${it.key} ${if (it.value) "탑승" else "빈자리"}" } } ?: "지원 안 함", v.rearSeats.isNotEmpty())
                Feature("바깥 공기 전환", supportText(v.recirc?.writable), v.recirc?.writable == true)
                Feature(v.windowName ?: "창문 조절", supportText(v.window?.writable), v.window?.writable == true)
                Feature("송풍·온도 조절", supportText(v.fan?.writable == true || v.temp?.writable == true), v.fan?.writable == true || v.temp?.writable == true)
                Feature("운전 부드러움(핸들·브레이크)", if (v.link == Link.CONNECTED) "측정 중" else linkText(v.link), v.link == Link.CONNECTED)
                Feature("음성 안내", when (vo.status) {
                    VoiceStatus.READY, VoiceStatus.SPEAKING -> "사용 가능"; VoiceStatus.STARTING -> "준비 중"; else -> "화면 안내로 대체 중"
                }, vo.status == VoiceStatus.READY || vo.status == VoiceStatus.SPEAKING)
                Feature("말로 알리기", when (ui.listen.status) {
                    ListenStatus.IDLE, ListenStatus.LISTENING -> "사용 가능"; ListenStatus.ERROR -> "인식 오류, 버튼 사용"; ListenStatus.UNAVAILABLE -> "사용 불가, 버튼 사용"
                }, ui.listen.status == ListenStatus.IDLE || ui.listen.status == ListenStatus.LISTENING)
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { dev = !dev }.padding(vertical = 10.dp, horizontal = 6.dp)) {
                Text(if (dev) "개발자 정보 접기 ▲" else "개발자 정보 보기 ▼", fontSize = 16.sp, color = C.inkSoft, fontWeight = FontWeight.SemiBold)
            }
            if (dev) Group {
                Status("내비 모드 / 경로 옵션", "${n.mode ?: "-"} / ${n.routeOption ?: "-"}")
                Status("회전 안내 / 주의 지점", "${n.tbt.size}개 / ${n.cautions.size}개")
                Status("마지막 수신", n.lastUpdateMs?.let { "${(ui.nowMs - it) / 1000}초 전" } ?: "-")
                n.location?.let { Status("현재 위치", "%.5f, %.5f".format(it.lat, it.lon)) }
                Status("기어 / 주행 모드", "${v.gear?.removePrefix("GEAR_") ?: "-"} / ${v.drivingMode ?: "-"}")
                Status("핸들 / 브레이크", "${v.steeringDeg?.let { "%.1f°".format(it) } ?: "-"} / ${v.brakeOn ?: "-"}")
                Status("공기 순환 / 창문", "${v.recirc?.value ?: "-"} / ${v.window?.value ?: "-"} (${v.window?.min ?: "?"}~${v.window?.max ?: "?"})")
                Status("송풍 / 온도", "${v.fan?.value ?: "-"} / ${v.temp?.value ?: "-"}")
                listOfNotNull(n.lastError, v.lastError, vo.lastError, ui.listen.lastError).forEach { Status("오류", it, C.berry) }
            }
            Text("안울렁 1.1 · Pleos Connect SDK (NaviHelper, Vehicle, TextToSpeech, SpeechToText)", fontSize = 14.sp, color = C.inkSoft)
        }
    }
}

@Composable
private fun Feature(name: String, state: String, ok: Boolean, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(if (ok) C.mint else C.line))
        Spacer(Modifier.width(14.dp))
        Text(name, fontSize = 18.sp, color = C.ink, modifier = Modifier.weight(1f))
        Text(state, fontSize = 16.sp, color = if (ok) C.mint else C.inkSoft, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp)); trailing()
    }
}

@Composable
private fun Group(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        if (title != null) Text(title, fontSize = 16.sp, color = C.inkSoft, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.paper).padding(horizontal = 20.dp, vertical = 10.dp), content = content)
    }
}

@Composable
private fun ToggleRow(title: String, desc: String, on: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).clickable(enabled = enabled, onClick = onToggle).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, color = C.ink, fontWeight = FontWeight.SemiBold)
            Text(desc, fontSize = 15.sp, color = C.inkSoft, lineHeight = 21.sp)
        }
        Spacer(Modifier.width(16.dp))
        Switch(on, { onToggle() }, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = C.periwinkle))
    }
}

@Composable
private fun Status(k: String, v: String, color: Color = C.ink) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(k, fontSize = 15.sp, color = C.inkSoft, modifier = Modifier.weight(1f))
        Text(v, fontSize = 15.sp, color = color, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

// ───────────────────────────────────────── 공통

@Composable
private fun BottomBar(tab: Int, sickCount: Int, onTab: (Int) -> Unit) {
    NavigationBar(containerColor = C.paper, tonalElevation = 0.dp) {
        val colors = NavigationBarItemDefaults.colors(selectedIconColor = C.periwinkle, selectedTextColor = C.periwinkle,
            indicatorColor = C.periwinkle.copy(alpha = .12f), unselectedIconColor = C.inkSoft, unselectedTextColor = C.inkSoft)
        NavigationBarItem(tab == 0, { onTab(0) }, { Icon(Icons.Rounded.Home, null) }, label = { Text("여정", fontSize = 16.sp) }, colors = colors)
        NavigationBarItem(tab == 1, { onTab(1) }, { Icon(Icons.Rounded.Favorite, null) },
            label = { Text(if (sickCount > 0) "기록 $sickCount" else "기록", fontSize = 16.sp) }, colors = colors)
        NavigationBarItem(tab == 2, { onTab(2) }, { Icon(Icons.Rounded.Settings, null) }, label = { Text("설정", fontSize = 16.sp) }, colors = colors)
    }
}

@Composable
private fun SideRail(tab: Int, sickCount: Int, onTab: (Int) -> Unit) {
    NavigationRail(containerColor = C.paper, modifier = Modifier.fillMaxHeight().statusBarsPadding()) {
        Spacer(Modifier.height(12.dp))
        val colors = NavigationRailItemDefaults.colors(selectedIconColor = C.periwinkle, selectedTextColor = C.periwinkle,
            indicatorColor = C.periwinkle.copy(alpha = .12f), unselectedIconColor = C.inkSoft, unselectedTextColor = C.inkSoft)
        listOf(Triple("여정", Icons.Rounded.Home, 0), Triple(if (sickCount > 0) "기록 $sickCount" else "기록", Icons.Rounded.Favorite, 1),
            Triple("설정", Icons.Rounded.Settings, 2)).forEach { (label, icon, i) ->
            NavigationRailItem(tab == i, { onTab(i) }, { Icon(icon, null, Modifier.size(30.dp)) },
                label = { Text(label, fontSize = 15.sp) }, colors = colors, modifier = Modifier.padding(vertical = 10.dp))
        }
    }
}

@Composable
private fun BigButton(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).clip(RoundedCornerShape(20.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, color = fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** Primary/secondary action button: at least 56 dp tall so it can be hit at arm's length. */
@Composable
private fun Pill(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(Modifier.defaultMinSize(minHeight = 56.dp).clip(CircleShape).background(bg).clickable(onClick = onClick).padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Badge(text: String) =
    Text(text, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(C.lilacDeep).padding(horizontal = 10.dp, vertical = 4.dp))

private fun feelColor(f: Feeling) = when (f) { Feeling.OK -> C.mint; Feeling.UNEASY -> C.sun; Feeling.SICK -> C.berry }
private fun distText(m: Int) = if (m >= 1000) "%.1fkm".format(m / 1000f) else "${m}m"
private fun dateText(ms: Long) = SimpleDateFormat("M월 d일 a h:mm", Locale.KOREA).format(Date(ms))
private fun linkText(l: Link) = when (l) { Link.CONNECTING -> "연결 중"; Link.CONNECTED -> "연결됨"; Link.FAILED -> "연결 실패"; Link.UNAVAILABLE -> "사용 불가" }
private fun supportText(w: Boolean?) = if (w == true) "사용 가능" else "지원 안 함"
private fun modeLabel(m: String) = when (m) { "SPORT" -> "스포츠"; "ECO" -> "에코"; "NORMAL" -> "일반"; else -> m }
