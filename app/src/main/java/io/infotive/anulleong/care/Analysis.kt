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
 * Sway profile of a route from the manoeuvres the navigation reports.
 * It compares routes with each other; it is not a motion-sickness probability.
 */
data class RouteStats(
    val sharpTurns: Int,
    val ramps: Int,
    val turns: Int,
    val bumps: Int,
    /** Known sick zones on the route; null when the SDK cannot tell (see README). */
    val sickZones: Int?,
    val distanceM: Int?,
) {
    val swayScore get() = sharpTurns * 3 + ramps * 2 + bumps * 2 + turns

    companion object {
        fun of(events: List<AheadEvent>, sickZones: Int?, distanceM: Int?) = RouteStats(
            sharpTurns = events.count { it.kind == EventKind.SHARP_TURN },
            ramps = events.count { it.kind == EventKind.RAMP },
            turns = events.count { it.kind == EventKind.TURN || it.kind == EventKind.MERGE },
            bumps = events.count { it.kind == EventKind.BUMP || it.kind == EventKind.DOWNHILL || it.kind == EventKind.ROUGH_ROAD },
            sickZones = sickZones,
            distanceM = distanceM,
        )
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
)
