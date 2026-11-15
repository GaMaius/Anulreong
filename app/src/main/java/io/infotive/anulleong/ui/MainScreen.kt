package io.infotive.anulleong.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import io.infotive.anulleong.CareViewModel
import io.infotive.anulleong.UiState
import io.infotive.anulleong.VentPhase
import io.infotive.anulleong.care.AheadEvent
import io.infotive.anulleong.care.CareRecord
import io.infotive.anulleong.care.DataSource
import io.infotive.anulleong.care.EventKind
import io.infotive.anulleong.care.Feeling
import io.infotive.anulleong.sdk.Link
import io.infotive.anulleong.sdk.StepResult
import io.infotive.anulleong.sdk.VoiceStatus
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(ui: UiState, vm: CareViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(ui.message) { if (ui.message != null) { delay(3_500); vm.clearMessage() } }

    Box(Modifier.fillMaxSize().background(C.sky)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
                when (tab) {
                    0 -> JourneyTab(ui, vm)
                    1 -> RecordsTab(ui, vm)
                    else -> ConnectTab(ui, vm)
                }
            }
            BottomBar(tab, ui.records.count { it.feeling == Feeling.SICK }) { tab = it }
        }
        AnimatedVisibility(ui.message != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)) {
            Text(ui.message ?: "", color = Color.White, fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 20.dp).clip(RoundedCornerShape(14.dp)).background(C.ink)
                    .padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }
    CareSheet(ui, vm)
}

// ───────────────────────────────────────── 여정

@Composable
private fun JourneyTab(ui: UiState, vm: CareViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 840.dp
        // Keep the road visible together with the feeling buttons on short screens.
        val heroH = minOf(if (wide) 440.dp else maxWidth - 32.dp, maxHeight * 0.6f, 440.dp)
        if (wide) {
            // Car display / tablet: road on the left, care on the right.
            Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1.25f).verticalScroll(rememberScrollState())) {
                    Header(ui); Spacer(Modifier.height(14.dp)); Hero(ui, Modifier.fillMaxWidth().height(heroH))
                    Spacer(Modifier.height(14.dp)); UpcomingAndEta(ui, vm)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Spacer(Modifier.height(50.dp)); DemoGuide(ui, vm); DemoInvite(ui, vm); Revisit(ui, vm); RouteCompareCard(ui, vm); FeelingPicker(vm); VentStatus(ui, vm)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Spacer(Modifier.height(4.dp)); Header(ui)
                DemoGuide(ui, vm)
                Hero(ui, Modifier.fillMaxWidth().height(heroH))
                DemoInvite(ui, vm)
                Revisit(ui, vm); RouteCompareCard(ui, vm); FeelingPicker(vm); VentStatus(ui, vm); UpcomingAndEta(ui, vm)
                Footnote()
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun Header(ui: UiState) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("안울렁", fontFamily = Jua, fontSize = 26.sp, color = C.periwinkle)
        Spacer(Modifier.weight(1f))
        val (label, color) = when (ui.snapshot.source) {
            DataSource.NAVI -> "실시간 내비" to C.mint
            DataSource.DEMO -> "시연용 합성 경로" to C.lilac
            DataSource.NONE -> "경로 없음" to C.inkSoft
        }
        Row(Modifier.clip(CircleShape).background(C.paper).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 13.sp, color = C.ink, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Hero(ui: UiState, modifier: Modifier) {
    val snap = ui.snapshot
    val next = snap.events.firstOrNull()
    val headline = when {
        next != null -> (next.distanceM?.let { "${distText(it)} 뒤\n" } ?: "다음은\n") + heroVerb(next)
        snap.source == DataSource.NONE && ui.navi.link == Link.CONNECTED -> "목적지를 정하면\n앞길을 그려 줄게요"
        snap.source == DataSource.NONE -> "내비에 연결되지\n않았어요"
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
    )
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
private fun FeelingPicker(vm: CareViewModel) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.paper).padding(18.dp)) {
        Text("지금 기분은 어때?", fontFamily = Jua, fontSize = 22.sp, color = C.ink)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Feeling.values().forEach { f ->
                val color = feelColor(f)
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { vm.recordFeeling(f) }.padding(6.dp)) {
                    Box(Modifier.size(82.dp).clip(CircleShape).background(color.copy(alpha = .16f))
                        .border(3.dp, color, CircleShape), contentAlignment = Alignment.Center) {
                        Text(f.emoji, fontSize = 40.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(f.label, fontSize = 15.sp, color = C.ink, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun Revisit(ui: UiState, vm: CareViewModel) {
    val r = ui.revisit ?: return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.lilac).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📍", fontSize = 28.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(if (ui.revisitCount > 1) "이 근처에서 ${ui.revisitCount}번 멀미가 났어요" else "지난번 이 근처에서 멀미가 났어요",
                    fontFamily = Jua, fontSize = 19.sp, color = Color.White)
                Text("${dateText(r.timeMs)}에 남긴 기록이에요. 미리 창밖 먼 곳을 봐요.", fontSize = 13.sp, color = Color.White.copy(alpha = .9f))
            }
        }
        if (ui.routeCompare == null) {
            Spacer(Modifier.height(10.dp))
            Pill("멀미가 덜 나는 길 찾기", C.ink) { vm.compareRoutes() }
        }
    }
}

/** "멀미가 덜 나는 길": current route vs a calmer one, side by side. */
@Composable
private fun RouteCompareCard(ui: UiState, vm: CareViewModel) {
    val c = ui.routeCompare ?: return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.paper)
        .border(2.dp, C.mint.copy(alpha = .6f), RoundedCornerShape(24.dp)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("멀미가 덜 나는 길", fontFamily = Jua, fontSize = 21.sp, color = C.ink, modifier = Modifier.weight(1f))
            if (c.synthetic) Text("시연용 합성 경로", fontSize = 11.sp, color = Color.White,
                modifier = Modifier.clip(CircleShape).background(C.lilac).padding(horizontal = 8.dp, vertical = 3.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Spacer(Modifier.weight(1.3f))
            Text(c.fromLabel, fontSize = 13.sp, color = C.inkSoft, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text(c.toLabel, fontSize = 13.sp, color = C.mint, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        val t = c.to
        CompareRow("흔들림 점수", c.from.swayScore, t?.swayScore)
        CompareRow("급회전·회전교차로", c.from.sharpTurns, t?.sharpTurns)
        CompareRow("진출입로", c.from.ramps, t?.ramps)
        CompareRow("방지턱·내리막", c.from.bumps, t?.bumps)
        CompareRow("지난 멀미 구간", c.from.sickZones, t?.sickZones, unknown = "1.5km 앞만")
        CompareRow("거리", c.from.distanceM, t?.distanceM, lowerIsBetter = null, format = { distText(it) })
        Spacer(Modifier.height(10.dp))
        val verdict = when {
            t == null -> "내비가 편안한 길 옵션으로 다시 찾는 중이에요…"
            t.swayScore < c.from.swayScore && (t.sickZones ?: 0) <= (c.from.sickZones ?: 0) ->
                "‘${c.toLabel}’이 흔들림이 적어요" + (if ((c.from.sickZones ?: 0) > (t.sickZones ?: 0)) ". 지난 멀미 구간도 피해 가요." else ".")
            t.swayScore == c.from.swayScore -> "두 길의 흔들림이 비슷해요."
            else -> "지금 길이 더 나을 수 있어요."
        }
        Text(verdict, fontSize = 15.sp, color = C.ink, fontWeight = FontWeight.SemiBold)
        Text("흔들림 점수 = 급회전×3 + 진출입로×2 + 방지턱·내리막×2 + 일반 회전×1. 길을 비교하기 위한 값이며 멀미 확률이 아니에요." +
            (c.note?.let { " $it" } ?: ""), fontSize = 12.sp, lineHeight = 17.sp, color = C.inkSoft, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (c.synthetic && t != null) Pill("편안한 길로 가기", C.mint) { vm.takeComfortRoad() }
            Text("닫기", fontSize = 14.sp, color = C.inkSoft, modifier = Modifier.clip(CircleShape).clickable { vm.dismissCompare() }.padding(horizontal = 12.dp, vertical = 9.dp))
        }
    }
}

@Composable
private fun CompareRow(label: String, a: Int?, b: Int?, unknown: String = "-", lowerIsBetter: Boolean? = true, format: (Int) -> String = { "$it" }) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = C.ink, modifier = Modifier.weight(1.3f))
        Text(a?.let(format) ?: unknown, fontSize = 15.sp, color = C.inkSoft, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        val better = lowerIsBetter == true && a != null && b != null && b < a
        Text(b?.let(format) ?: unknown, fontSize = 15.sp, color = if (better) C.mint else C.ink,
            fontWeight = if (better) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun VentStatus(ui: UiState, vm: CareViewModel) {
    if (!ui.canRestore || ui.vent != VentPhase.IDLE) return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.paper).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("🌬️", fontSize = 26.sp)
        Spacer(Modifier.width(10.dp))
        Text("바깥 바람이 들어오는 중", fontSize = 16.sp, color = C.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Pill("원래대로", C.periwinkle) { vm.restoreVent() }
    }
}

@Composable
private fun UpcomingAndEta(ui: UiState, vm: CareViewModel) {
    val snap = ui.snapshot
    val rest = snap.events.drop(1).take(4)
    if (rest.isEmpty() && snap.remainDistanceM == null) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.paper).padding(18.dp)) {
        if (snap.remainDistanceM != null || snap.remainTimeSec != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("도착까지", fontSize = 15.sp, color = C.inkSoft)
                Spacer(Modifier.width(8.dp))
                val min = (snap.remainTimeSec ?: 0) / 60
                Text(if (min >= 1) "${min}분" else "곧 도착", fontFamily = Jua, fontSize = 26.sp, color = C.ink)
                Spacer(Modifier.width(8.dp))
                Text(snap.remainDistanceM?.let(::distText) ?: "", fontSize = 15.sp, color = C.inkSoft, modifier = Modifier.padding(bottom = 3.dp))
            }
            val total = ui.routeTotalM
            if (total != null && total > 0 && snap.remainDistanceM != null) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { (1f - snap.remainDistanceM.toFloat() / total).coerceIn(0f, 1f) },
                    color = C.periwinkle, trackColor = C.sky,
                    modifier = Modifier.fillMaxWidth().height(12.dp).clip(CircleShape))
            }
        }
        if (snap.source != DataSource.NONE && ui.routeCompare == null) {
            Spacer(Modifier.height(14.dp))
            Pill("멀미가 덜 나는 길 찾기", C.mint) { vm.compareRoutes() }
        }
        if (rest.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("그다음에는", fontSize = 14.sp, color = C.inkSoft, fontWeight = FontWeight.SemiBold)
            rest.forEach { e ->
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(eventColor(e).copy(alpha = .15f)), contentAlignment = Alignment.Center) {
                        Text(glyph(e), fontSize = 17.sp, color = eventColor(e), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(e.title, fontSize = 16.sp, color = C.ink, modifier = Modifier.weight(1f))
                    Text(e.distanceM?.let(::distText) ?: "", fontSize = 14.sp, color = C.inkSoft)
                }
            }
        }
    }
}

@Composable
private fun Footnote() {
    Text("앞길 그림은 내비게이션의 회전 안내와 주의 지점(과속방지턱, 내리막 등)으로 그려요. 도로의 굽은 정도나 멀미 확률을 계산하지는 않아요.",
        fontSize = 12.sp, lineHeight = 17.sp, color = C.inkSoft, modifier = Modifier.padding(horizontal = 4.dp))
}


// ───────────────────────────────────────── 시연 가이드

/** Shown when nothing is driving: one tap starts the guided demo. */
@Composable
private fun DemoInvite(ui: UiState, vm: CareViewModel) {
    if (ui.demoOn || ui.snapshot.source != DataSource.NONE) return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.paper).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("길 안내 없이 체험해 보기", fontFamily = Jua, fontSize = 20.sp, color = C.ink)
            Spacer(Modifier.height(4.dp))
            Text("가상 길을 두 번 달려요. 처음에 🤢을 남기면, 다음 여행에서 그곳에 가까워질 때 미리 알려 줘요.",
                fontSize = 14.sp, lineHeight = 20.sp, color = C.inkSoft)
        }
        Spacer(Modifier.width(12.dp))
        Pill("시연 시작", C.lilac) { vm.toggleDemo() }
    }
}

/** Step-by-step guide so a judge can see record → alert end to end. */
@Composable
private fun DemoGuide(ui: UiState, vm: CareViewModel) {
    if (!ui.demoOn || ui.snapshot.source != DataSource.DEMO) return
    val recorded = ui.records.any { it.source == DataSource.DEMO && it.feeling == Feeling.SICK }
    val firstTrip = ui.demoTrip == 1
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.lilac.copy(alpha = .14f))
        .border(2.dp, C.lilac.copy(alpha = .5f), RoundedCornerShape(24.dp)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("시연 · ${ui.demoTrip}번째 여행", fontFamily = Jua, fontSize = 20.sp, color = C.ink, modifier = Modifier.weight(1f))
            Text(if (ui.demoArrived) "도착" else "달리는 중", fontSize = 13.sp, color = C.lilac, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        GuideStep(1, "🤢 멀미 나요를 눌러 지금 자리를 기록해요", recorded, active = !recorded)
        val calm = ui.demoRoad == io.infotive.anulleong.care.DemoScenario.Road.COMFORT
        GuideStep(2, "다음 여행에서 그 자리에 가까워지면 미리 알려 줘요", ui.demoAlerted || calm, active = recorded && !ui.demoAlerted && !calm)
        GuideStep(3, "멀미가 덜 나는 길을 비교하고 그 길로 가요", calm, active = ui.demoAlerted && !calm)
        Spacer(Modifier.height(10.dp))
        val hint = when {
            !recorded && !ui.demoArrived -> "달리는 중 아무 때나 🤢을 눌러 보세요. 그 자리가 기록돼요."
            !recorded -> "기록 없이 도착했어요. ‘처음부터’를 눌러 다시 달려 보세요."
            firstTrip -> "기록했어요! ‘다음 여행 시작’을 누르면 같은 길을 다시 달려요."
            calm -> "편안한 길에서는 지난 멀미 구간을 지나지 않아 알림이 뜨지 않아요. 흔들림 안내도 줄어들어요."
            !ui.demoAlerted -> "📍 지난번 멀미 난 곳이 길 위로 다가와요. 400m 안에 들어오면 알림이 떠요."
            ui.routeCompare == null -> "기록을 바탕으로 미리 알려 줬어요. ‘덜 나는 길 찾기’를 눌러 보세요."
            else -> "두 길을 비교했어요. ‘편안한 길로 가기’를 누르면 그 길로 다시 달려요."
        }
        Text(hint, fontSize = 14.sp, lineHeight = 20.sp, color = C.ink)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (recorded && !calm && (firstTrip || ui.demoArrived)) Pill("다음 여행 시작", C.lilac) { vm.nextDemoTrip() }
            if (ui.demoAlerted && !calm && ui.routeCompare == null) Pill("덜 나는 길 찾기", C.mint) { vm.compareRoutes() }
            Text("처음부터", fontSize = 14.sp, color = C.inkSoft, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).clickable { vm.restartDemo() }.padding(horizontal = 12.dp, vertical = 9.dp))
            Spacer(Modifier.weight(1f))
            Text("끄기", fontSize = 14.sp, color = C.inkSoft,
                modifier = Modifier.clip(CircleShape).clickable { vm.toggleDemo() }.padding(horizontal = 12.dp, vertical = 9.dp))
        }
    }
}

@Composable
private fun GuideStep(n: Int, text: String, done: Boolean, active: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (done) C.mint else if (active) C.lilac else C.line),
            contentAlignment = Alignment.Center) {
            Text(if (done) "✓" else "$n", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 15.sp, color = if (done) C.inkSoft else C.ink,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
    }
}

// ───────────────────────────────────────── 환기 바텀시트

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CareSheet(ui: UiState, vm: CareViewModel) {
    if (ui.vent == VentPhase.IDLE) return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { if (ui.vent != VentPhase.WORKING) vm.dismissVent() }, sheetState = state,
        containerColor = C.paper) {
        Column(Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(horizontal = 24.dp).padding(bottom = 28.dp).navigationBarsPadding()) {
            val v = ui.vehicle
            when (ui.vent) {
                VentPhase.ASKING -> {
                    Text("🌬️", fontSize = 44.sp)
                    Text("시원한 바람을 넣을까요?", fontFamily = Jua, fontSize = 26.sp, color = C.ink)
                    Spacer(Modifier.height(8.dp))
                    Text("바깥 공기 모드로 바꾸고 ${v.windowName ?: "뒷좌석 창문"}을 조금만 열어요. 끝나면 차 상태를 다시 읽어서 정말 바뀌었는지 확인해요.",
                        fontSize = 15.sp, lineHeight = 22.sp, color = C.inkSoft)
                    Spacer(Modifier.height(22.dp))
                    BigButton("바람 넣기", C.periwinkle, Color.White) { vm.ventilate() }
                    Spacer(Modifier.height(10.dp))
                    BigButton("괜찮아요", C.sky, C.ink) { vm.dismissVent() }
                }
                VentPhase.WORKING -> {
                    Text("차에 요청하는 중이에요", fontFamily = Jua, fontSize = 24.sp, color = C.ink)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(color = C.periwinkle, trackColor = C.sky, modifier = Modifier.fillMaxWidth().clip(CircleShape))
                    Spacer(Modifier.height(10.dp))
                    Text("명령을 보낸 뒤 차 상태를 다시 읽어 확인하고 있어요.", fontSize = 14.sp, color = C.inkSoft)
                }
                VentPhase.DONE -> {
                    val r = ui.ventReport
                    Text(if (r?.anyConfirmed == true) "바람이 들어와요" else "차에서 확인되지 않았어요", fontFamily = Jua, fontSize = 26.sp, color = C.ink)
                    Spacer(Modifier.height(14.dp))
                    if (r != null) {
                        StepLine("바깥 공기 모드", r.recirc, null)
                        StepLine(v.windowName ?: "창문", r.window, r.windowValue?.let { "열림 정도 $it" })
                    }
                    Spacer(Modifier.height(22.dp))
                    if (ui.canRestore) { BigButton("원래대로 돌리기", C.sky, C.ink) { vm.restoreVent() }; Spacer(Modifier.height(10.dp)) }
                    BigButton("확인", C.periwinkle, Color.White) { vm.dismissVent() }
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
        StepResult.UNSUPPORTED -> "이 차는 지원하지 않아요" to C.inkSoft
        StepResult.FAILED -> "실패했어요. 직접 조작해 주세요" to C.berry
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(name, fontSize = 16.sp, color = C.ink, modifier = Modifier.weight(1f))
        Text(txt + (extra?.let { " · $it" } ?: ""), fontSize = 15.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

// ───────────────────────────────────────── 기록

@Composable
private fun RecordsTab(ui: UiState, vm: CareViewModel) {
    var confirmClear by remember { mutableStateOf(false) }
    val recs = ui.records
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 720.dp)) {
        Text("멀미 기록", fontFamily = Jua, fontSize = 28.sp, color = C.ink)
        Text("이 기기 안에만 저장돼요. 음성은 녹음하지 않아요.", fontSize = 13.sp, color = C.inkSoft)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Feeling.values().reversed().forEach { f ->
                val n = recs.count { it.feeling == f }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(feelColor(f).copy(alpha = .14f)).padding(14.dp)) {
                    Text(f.emoji, fontSize = 22.sp)
                    Text("$n", fontFamily = Jua, fontSize = 30.sp, color = C.ink)
                    Text(f.label, fontSize = 12.sp, color = C.inkSoft)
                }
            }
        }
        val zones = io.infotive.anulleong.care.ZoneAnalysis.zones(recs)
        if (zones.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("자주 멀미 나는 구간", fontFamily = Jua, fontSize = 20.sp, color = C.ink)
            Text("300m 안에서 🤢을 누른 기록을 한 구간으로 묶었어요. 이 가족의 기록만으로 분석해요.", fontSize = 13.sp, color = C.inkSoft)
            Spacer(Modifier.height(8.dp))
            zones.take(5).forEachIndexed { i, z ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp)).background(C.paper).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(C.berry.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                        Text("${z.count}번", fontSize = 14.sp, color = C.berry, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("구간 ${i + 1}" + (z.commonCue?.let { " · 주로 ‘$it’ 앞" } ?: ""), fontSize = 16.sp, color = C.ink, fontWeight = FontWeight.Bold)
                        Text("최근 ${dateText(z.lastMs)}" + if (z.source == DataSource.DEMO) " · 시연 기록" else "", fontSize = 13.sp, color = C.inkSoft)
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (recs.isEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.paper).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🚗", fontSize = 40.sp)
                Spacer(Modifier.height(8.dp))
                Text("아직 기록이 없어요", fontFamily = Jua, fontSize = 20.sp, color = C.ink)
                Text("아이가 불편하다고 하면 ‘여정’ 탭에서 얼굴을 눌러 주세요.", fontSize = 14.sp, color = C.inkSoft, textAlign = TextAlign.Center)
            }
        } else {
            var lastDay = ""
            recs.asReversed().take(100).forEach { r ->
                val day = SimpleDateFormat("M월 d일 EEEE", Locale.KOREA).format(Date(r.timeMs))
                if (day != lastDay) {
                    Text(day, fontSize = 14.sp, color = C.inkSoft, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                    lastDay = day
                }
                RecordItem(r) { vm.deleteRecord(r.id) }
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { confirmClear = true }) { Text("기록 모두 지우기", color = C.berry) }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("기록을 모두 지울까요?") },
        text = { Text("저장된 기록 ${recs.size}개가 지워지고 되돌릴 수 없어요.") },
        confirmButton = { TextButton({ vm.clearRecords(); confirmClear = false }) { Text("모두 지우기", color = C.berry) } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("취소") } },
        containerColor = C.paper,
    )
}

@Composable
private fun RecordItem(r: CareRecord, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp)).background(C.paper).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(feelColor(r.feeling).copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            Text(r.feeling.emoji, fontSize = 22.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(r.timeMs)), fontSize = 16.sp, color = C.ink, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(r.feeling.label, fontSize = 15.sp, color = C.ink)
                if (r.source == DataSource.DEMO) {
                    Spacer(Modifier.width(8.dp))
                    Text("시연", fontSize = 11.sp, color = Color.White, modifier = Modifier.clip(CircleShape).background(C.lilac).padding(horizontal = 7.dp, vertical = 2.dp))
                }
            }
            val detail = buildList {
                r.nextEventTitle?.let { add("앞에 $it") }
                add(if (r.lat != null) "위치 저장됨" else "위치 없음")
                r.steeringDeg?.let { add("핸들 %.0f°".format(it)) }
            }
            Text(detail.joinToString(", "), fontSize = 13.sp, color = C.inkSoft)
        }
        Text("지우기", fontSize = 13.sp, color = C.inkSoft, modifier = Modifier.clip(CircleShape).clickable(onClick = onDelete).padding(8.dp))
    }
}

// ───────────────────────────────────────── 연결

@Composable
private fun ConnectTab(ui: UiState, vm: CareViewModel) {
    val n = ui.navi; val v = ui.vehicle; val vo = ui.voice
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("연결과 설정", fontFamily = Jua, fontSize = 28.sp, color = C.ink)
        Group {
            ToggleRow("음성으로 알려 주기", "안내를 화면과 함께 소리로도 들려줘요", ui.voiceOn) { vm.toggleVoice() }
            ToggleRow("시연 모드", "실제 길 안내가 없을 때 가상 경로로 기능을 보여줘요. 화면에 ‘시연용 합성 경로’가 표시돼요.", ui.demoOn) { vm.toggleDemo() }
            if (n.link == Link.CONNECTED) {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("테스트 경로", fontSize = 16.sp, color = C.ink, fontWeight = FontWeight.SemiBold)
                        Text(if (n.routing) "길 안내 중이에요" else "내비에 서울역까지 길 안내를 요청해요", fontSize = 13.sp, color = C.inkSoft)
                    }
                    if (n.routing) Pill("안내 취소", C.berry) { vm.cancelRoute() } else Pill("서울역 안내", C.periwinkle) { vm.requestSampleRoute() }
                }
            }
        }
        Group("내비게이션") {
            Status("연결", linkText(n.link), linkColor(n.link))
            Status("길 안내", if (n.routing) "안내 중 (${n.mode ?: "-"})" else "없음 (${n.mode ?: "-"})")
            Status("회전 안내 / 주의 지점", "${n.tbt.size}개 / ${n.cautions.size}개")
            Status("마지막 수신", n.lastUpdateMs?.let { "${(ui.nowMs - it) / 1000}초 전" } ?: "-", if (n.isFresh(ui.nowMs)) C.mint else C.inkSoft)
            n.location?.let { Status("현재 위치", "%.5f, %.5f".format(it.lat, it.lon)) }
            n.lastError?.let { Status("오류", it, C.berry) }
        }
        Group("차량") {
            Status("연결", linkText(v.link), linkColor(v.link))
            Status("기어", v.gear?.removePrefix("GEAR_") ?: "-")
            Status("핸들 각도", v.steeringDeg?.let { "%.1f°".format(it) } ?: "-")
            Status("브레이크", when (v.brakeOn) { true -> "밟음"; false -> "뗌"; null -> "-" })
            Status("공기 순환", v.recirc?.let { "${when (it.value) { true -> "실내 순환"; false -> "바깥 공기"; else -> "-" }} (${accessText(it.access)})" } ?: "-")
            Status(v.windowName ?: "창문", v.window?.let { "${it.value ?: "-"} / ${it.max ?: "?"} (${accessText(it.access)})" } ?: "-")
            v.lastError?.let { Status("오류", it, C.berry) }
        }
        Group("음성 안내") {
            Status("상태", when (vo.status) {
                VoiceStatus.STARTING -> "준비 중"; VoiceStatus.READY -> "준비됨"; VoiceStatus.SPEAKING -> "말하는 중"
                VoiceStatus.ERROR -> "오류, 화면으로만 안내"; VoiceStatus.UNAVAILABLE -> "사용 불가, 화면으로만 안내"
            }, if (vo.status == VoiceStatus.READY || vo.status == VoiceStatus.SPEAKING) C.mint else C.sun)
            vo.lastError?.let { Status("오류", it, C.berry) }
        }
        Text("안울렁 1.0 · Pleos Connect SDK (NaviHelper, Vehicle, TextToSpeech)", fontSize = 12.sp, color = C.inkSoft)
    }
}

@Composable
private fun Group(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        if (title != null) Text(title, fontSize = 14.sp, color = C.inkSoft, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.paper).padding(horizontal = 18.dp, vertical = 8.dp), content = content)
    }
}

@Composable
private fun ToggleRow(title: String, desc: String, on: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = C.ink, fontWeight = FontWeight.SemiBold)
            Text(desc, fontSize = 13.sp, color = C.inkSoft, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(on, { onToggle() }, colors = SwitchDefaults.colors(checkedTrackColor = C.periwinkle))
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
        NavigationBarItem(tab == 0, { onTab(0) }, { Icon(Icons.Rounded.Home, null) }, label = { Text("여정") }, colors = colors)
        NavigationBarItem(tab == 1, { onTab(1) }, { Icon(Icons.Rounded.Favorite, null) },
            label = { Text(if (sickCount > 0) "기록 $sickCount" else "기록") }, colors = colors)
        NavigationBarItem(tab == 2, { onTab(2) }, { Icon(Icons.Rounded.Settings, null) }, label = { Text("연결") }, colors = colors)
    }
}

@Composable
private fun BigButton(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Text(label, color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(bg).clickable(onClick = onClick).padding(vertical = 17.dp))
}

@Composable
private fun Pill(label: String, color: Color, onClick: () -> Unit) {
    Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(CircleShape).background(color).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp))
}

fun glyph(e: AheadEvent): String = when (e.kind) {
    EventKind.TURN -> if (e.title.startsWith("왼")) "↰" else if (e.title.startsWith("오른")) "↱" else "↔"
    EventKind.SHARP_TURN -> "↺"
    EventKind.RAMP -> "⤴"
    EventKind.MERGE -> if (e.title.startsWith("왼")) "↖" else "↗"
    EventKind.SLOWDOWN -> "↓"
    EventKind.BUMP -> "〰"
    EventKind.DOWNHILL -> "⤵"
    EventKind.ROUGH_ROAD -> "≋"
    EventKind.NARROW -> "⇥"
    EventKind.REST -> "☕"
    EventKind.ARRIVE -> "🏁"
    EventKind.PAST_SICK -> "📍"
}

private fun feelColor(f: Feeling) = when (f) { Feeling.OK -> C.mint; Feeling.UNEASY -> C.sun; Feeling.SICK -> C.berry }
private fun distText(m: Int) = if (m >= 1000) "%.1fkm".format(m / 1000f) else "${m}m"
private fun dateText(ms: Long) = SimpleDateFormat("M월 d일 a h:mm", Locale.KOREA).format(Date(ms))
private fun linkText(l: Link) = when (l) { Link.CONNECTING -> "연결 중"; Link.CONNECTED -> "연결됨"; Link.FAILED -> "연결 실패"; Link.UNAVAILABLE -> "사용 불가" }
private fun linkColor(l: Link) = when (l) { Link.CONNECTED -> C.mint; Link.CONNECTING -> C.sun; else -> C.berry }
private fun accessText(a: Any?) = when (a?.toString()) { "READ_WRITE" -> "읽기·쓰기"; "READ" -> "읽기만"; "WRITE" -> "쓰기만"; else -> "지원 안 함" }
