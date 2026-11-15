package io.infotive.anulleong.care

/**
 * A place where the family recorded 🤢 more than once is a "sick zone".
 * Built only from their own records — nothing here is a population-level prediction.
 */
data class SickZone(
    val id: String,
    val lat: Double,
    val lon: Double,
    val heading: Double?,
    val count: Int,
    val lastMs: Long,
    val source: DataSource,
    /** The navigation cue that was most often next when 🤢 was pressed here. */
    val commonCue: String?,
)

object ZoneAnalysis {
    const val ZONE_RADIUS_M = 300.0

    fun zones(records: List<CareRecord>): List<SickZone> {
        data class Acc(val first: CareRecord, val members: MutableList<CareRecord>)
        val acc = mutableListOf<Acc>()
        records.filter { it.feeling == Feeling.SICK && it.lat != null && it.lon != null }
            .sortedBy { it.timeMs }
            .forEach { r ->
                val hit = acc.firstOrNull { a ->
                    a.first.source == r.source &&
                        RevisitDetector.distanceM(a.first.lat!!, a.first.lon!!, r.lat!!, r.lon!!) <= ZONE_RADIUS_M
                }
                if (hit != null) hit.members += r else acc += Acc(r, mutableListOf(r))
            }
        return acc.map { a ->
            val m = a.members
            SickZone(
                id = a.first.id,
                lat = m.map { it.lat!! }.average(),
                lon = m.map { it.lon!! }.average(),
                heading = a.first.heading,
                count = m.size,
                lastMs = m.maxOf { it.timeMs },
                source = a.first.source,
                commonCue = m.mapNotNull { it.nextEventTitle }.filter { it != "지난번 멀미 난 곳" }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
            )
        }.sortedWith(compareByDescending<SickZone> { it.count }.thenByDescending { it.lastMs })
    }

    /** How many of the family's records sit in the same zone as [r]. */
    fun countNear(r: CareRecord, records: List<CareRecord>): Int =
        records.count {
            it.feeling == Feeling.SICK && it.source == r.source && it.lat != null && r.lat != null &&
                RevisitDetector.distanceM(it.lat, it.lon!!, r.lat, r.lon!!) <= ZONE_RADIUS_M
        }
}

/**
 * 속편한 점수 (0–100, higher = calmer) for comparing routes, built from the manoeuvres the
 * navigation reports. It compares routes with each other; it is not a motion-sickness probability.
 *
 *   흔들림 요소 = 급회전·회전교차로×3 + 진출입로×2 + 방지턱·내리막·노면×2 + 일반 회전·갈림길×1
 *   속편한 점수 = 100 − (흔들림 요소 ÷ 거리 km × 10) − 지난 멀미 구간×15 − 아이가 약한 유형 구간×8
 */
data class RouteStats(
    val sharpTurns: Int,
    val ramps: Int,
    val turns: Int,
    val bumps: Int,
    /** Known sick zones on the route; null when the SDK cannot tell (see README). */
    val sickZones: Int?,
    val distanceM: Int?,
    /** Sections of a kind this child is sensitive to (유사 구간). */
    val similar: Int = 0,
) {
    val swayPoints get() = sharpTurns * 3 + ramps * 2 + bumps * 2 + turns

    val comfortScore: Int get() {
        val km = ((distanceM ?: 10_000) / 1000.0).coerceAtLeast(1.0)
        val s = 100 - swayPoints / km * 10 - 15 * (sickZones ?: 0) - 8 * similar
        return Math.round(s).toInt().coerceIn(0, 100)
    }

    companion object {
        fun of(events: List<AheadEvent>, sickZones: Int?, distanceM: Int?, flagged: Set<EventKind> = emptySet()): RouteStats {
            val ev = events.filter { it.kind != EventKind.PAST_SICK }
            return RouteStats(
                sharpTurns = ev.count { it.kind == EventKind.SHARP_TURN },
                ramps = ev.count { it.kind == EventKind.RAMP },
                turns = ev.count { it.kind == EventKind.TURN || it.kind == EventKind.MERGE },
                bumps = ev.count { it.kind == EventKind.BUMP || it.kind == EventKind.DOWNHILL || it.kind == EventKind.ROUGH_ROAD },
                sickZones = sickZones,
                distanceM = distanceM,
                similar = ev.count { it.kind in flagged },
            )
        }
    }
}

data class RouteCompare(
    val fromLabel: String,
    val toLabel: String,
    val from: RouteStats,
    /** null while the navigation is still recalculating. */
    val to: RouteStats?,
    val synthetic: Boolean,
    val note: String? = null,
    /** Real navi only: option to return to if the family keeps the original route. */
    val originalOption: String? = null,
    /** Real navi only: the new route has already been applied by the navigation. */
    val applied: Boolean = false,
)
