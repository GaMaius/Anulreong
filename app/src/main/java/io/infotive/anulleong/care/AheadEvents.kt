package io.infotive.anulleong.care

/**
 * Turns raw navigation data into "sway/jolt ahead" cues for a back-seat child.
 *
 * Honest scope: these are manoeuvres and caution points the navigation already knows
 * about (TBT turn types and CautionPoint types). They are NOT road curvature or a
 * motion-sickness probability. Mapping works on enum *names* so it is testable on the JVM.
 */
object AheadEvents {

    data class RawTbt(val typeName: String, val distance: Double, val description: String?)
    data class RawCaution(val id: String, val typeName: String, val remainDistance: Int)

    fun fromTbt(index: Int, t: RawTbt): AheadEvent? {
        val n = t.typeName
        val (kind, sev) = when {
            n == "TURN_LEFT" || n == "TURN_RIGHT" -> EventKind.TURN to Severity.NOTICE
            n.startsWith("TURN_SHARP") || n == "TURN_UTURN" || n.startsWith("TURN_ENTER_ROUNDABOUT") ||
                n == "TURN_EXIT_ROUNDABOUT" -> EventKind.SHARP_TURN to Severity.STRONG
            n.startsWith("TURN_ENTER_IC") || n.startsWith("TURN_EXIT_IC") ||
                n.contains("_UNDER_") || n.contains("_OVER_") -> EventKind.RAMP to Severity.NOTICE
            n.startsWith("TURN_KEEP") || n == "TURN_DEVIDE_MERGE" -> EventKind.MERGE to Severity.GENTLE
            n == "TURN_TOLL" -> EventKind.SLOWDOWN to Severity.GENTLE
            n == "TURN_ENTER_SERVICEAREA" || n == "TURN_RESTAREA" -> EventKind.REST to Severity.GENTLE
            n == "TURN_ARRIVE" -> EventKind.ARRIVE to Severity.GENTLE
            else -> return null // straight, waypoint, tunnel, unknown: no sway cue
        }
        val left = n.endsWith("_L") || n == "TURN_LEFT"
        val right = n.endsWith("_R") || n == "TURN_RIGHT"
        val side = when { left -> "왼쪽"; right -> "오른쪽"; else -> null }
        val title = when (kind) {
            EventKind.TURN -> if (side != null) "${side}으로 회전" else "회전"
            EventKind.SHARP_TURN -> when {
                n == "TURN_UTURN" -> "유턴"
                n.contains("ROUNDABOUT") -> "회전교차로"
                else -> "${side ?: ""} 급회전".trim()
            }
            EventKind.RAMP -> if (n.contains("EXIT")) "나들목 나가기" else "진출입로"
            EventKind.MERGE -> "${side ?: ""} 방향 갈림길".trim()
            EventKind.SLOWDOWN -> "톨게이트 감속"
            EventKind.REST -> "휴게소"
            EventKind.ARRIVE -> "도착"
            else -> "안내"
        }
        return AheadEvent(
            key = "tbt:$n:${t.description ?: ""}",
            kind = kind, severity = sev, title = title,
            kidLine = kidLine(kind, side),
            // Only the first TBT item is treated as "next manoeuvre from the car".
            // Verified run: later items' distances were not monotonic (700, 50, 110…),
            // so they are shown in order without a distance.
            distanceM = if (index == 0) t.distance.toInt() else null,
            fromCautionPoint = false,
        )
    }

    fun fromCaution(c: RawCaution): AheadEvent? {
        val (kind, sev, title) = when (c.typeName) {
            "SPEED_BUMP" -> Triple(EventKind.BUMP, Severity.STRONG, "과속방지턱")
            "DOWNHILL_CAUTION_AREA" -> Triple(EventKind.DOWNHILL, Severity.NOTICE, "내리막길")
            "SLIPPERY_ROAD_ALERT", "ROAD_DAMAGE_ALERT" -> Triple(EventKind.ROUGH_ROAD, Severity.NOTICE, "노면 주의")
            "NARROW_ROAD" -> Triple(EventKind.NARROW, Severity.GENTLE, "좁은 길")
            "SCHOOL_ZONE" -> Triple(EventKind.SLOWDOWN, Severity.GENTLE, "어린이보호구역 감속")
            else -> return null // enforcement cameras etc. do not sway the car
        }
        return AheadEvent("caution:${c.id}", kind, sev, title, kidLine(kind, null), c.remainDistance, true)
    }

    fun merge(tbt: List<RawTbt>, cautions: List<RawCaution>): List<AheadEvent> {
        val withDistance = mutableListOf<AheadEvent>()
        val orderedOnly = mutableListOf<AheadEvent>()
        tbt.forEachIndexed { i, t -> fromTbt(i, t)?.let { if (it.distanceM != null) withDistance += it else orderedOnly += it } }
        cautions.mapNotNull(::fromCaution).filter { it.distanceM!! >= 0 }.forEach { withDistance += it }
        return withDistance.sortedBy { it.distanceM } + orderedOnly
    }

    fun kidLine(kind: EventKind, side: String?): String = when (kind) {
        EventKind.TURN -> (if (side != null) "곧 ${side}으로 돌아요." else "곧 방향을 바꿔요.") + " 고개를 들고 앞 창문 먼 곳을 봐요!"
        EventKind.SHARP_TURN -> "크게 돌아요! 등을 의자에 붙이고 앞을 봐요."
        EventKind.RAMP -> "빙글 도는 길이 나와요. 멀리 앞을 봐요."
        EventKind.MERGE -> "살짝 옆으로 가요."
        EventKind.SLOWDOWN -> "천천히 속도를 줄여요."
        EventKind.BUMP -> "덜컹! 방지턱이 있어요. 몸에 힘을 살짝 빼요."
        EventKind.DOWNHILL -> "내리막이에요. 배가 간질간질할 수 있어요."
        EventKind.ROUGH_ROAD -> "길이 조금 울퉁불퉁해요."
        EventKind.NARROW -> "좁은 길이라 천천히 가요."
        EventKind.REST -> "휴게소가 있어요. 쉬어 갈 수 있어요."
        EventKind.ARRIVE -> "거의 다 왔어요!"
        EventKind.PAST_SICK -> "지난번에 여기서 속이 안 좋았어요. 미리 창밖 먼 곳을 봐요."
    }

    fun fromPastRecord(id: String, distanceM: Int) = AheadEvent(
        key = "past:$id", kind = EventKind.PAST_SICK, severity = Severity.STRONG,
        title = "지난번 멀미 난 곳", kidLine = kidLine(EventKind.PAST_SICK, null),
        distanceM = distanceM, fromCautionPoint = false,
    )
}
