package io.infotive.anulleong.care

import kotlin.math.cos

/**
 * Synthetic drives for demos when no real route is active.
 * Everything produced here is tagged [DataSource.DEMO] and the UI labels it as synthetic.
 * It exists to show the care flow; it is not evidence of prediction accuracy.
 */
object DemoScenario {
    const val SPEED_MPS = 60 // fast-forwarded: one 5 km trip takes ~83 seconds
    private const val START_LAT = 37.5100
    private const val START_LON = 127.0600

    private data class Mark(val atM: Int, val tbt: AheadEvents.RawTbt? = null, val caution: AheadEvents.RawCaution? = null)

    enum class Road(val label: String, val lengthM: Int, val latOffset: Double) {
        /** The usual road: bump, left turn, ramp, downhill, roundabout. */
        USUAL("늘 가던 길", 5000, 0.0),
        /** A parallel road ~670 m north: a little longer, fewer sharp manoeuvres. */
        COMFORT("편안한 길", 5600, 0.006),
    }

    private val usual = listOf(
        Mark(600, caution = AheadEvents.RawCaution("demo-bump", "SPEED_BUMP", 0)),
        Mark(1200, tbt = AheadEvents.RawTbt("TURN_LEFT", 0.0, "데모 좌회전")),
        Mark(2000, tbt = AheadEvents.RawTbt("TURN_ENTER_IC_R", 0.0, "데모 진입로")),
        Mark(2700, caution = AheadEvents.RawCaution("demo-downhill", "DOWNHILL_CAUTION_AREA", 0)),
        Mark(3400, tbt = AheadEvents.RawTbt("TURN_ENTER_ROUNDABOUT_03", 0.0, "데모 회전교차로")),
        Mark(4200, tbt = AheadEvents.RawTbt("TURN_RESTAREA", 0.0, "데모 휴게소")),
        Mark(5000, tbt = AheadEvents.RawTbt("TURN_ARRIVE", 0.0, "데모 도착")),
    )
    private val comfort = listOf(
        Mark(1500, tbt = AheadEvents.RawTbt("TURN_KEEP_R", 0.0, "데모 갈림길")),
        Mark(3500, tbt = AheadEvents.RawTbt("TURN_RESTAREA", 0.0, "데모 휴게소")),
        Mark(5600, tbt = AheadEvents.RawTbt("TURN_ARRIVE", 0.0, "데모 도착")),
    )

    private fun marks(road: Road) = if (road == Road.USUAL) usual else comfort

    fun tripId(trip: Int) = "demo-trip-$trip"

    fun arrived(elapsedSec: Long, road: Road = Road.USUAL) = elapsedSec * SPEED_MPS >= road.lengthM

    private fun events(road: Road, pos: Int): List<AheadEvent> {
        val ahead = marks(road).filter { it.atM >= pos }
        val tbt = ahead.mapNotNull { m -> m.tbt?.copy(distance = (m.atM - pos).toDouble()) }
        val cautions = ahead.mapNotNull { m -> m.caution?.copy(remainDistance = m.atM - pos) }
        // In the demo we know true distances for every item, so show all of them.
        return (tbt.mapNotNull { t -> AheadEvents.fromTbt(0, t) } + cautions.mapNotNull(AheadEvents::fromCaution))
            .sortedBy { it.distanceM }
    }

    private fun locAt(road: Road, pos: Int, nowMs: Long): Loc {
        val lonPerM = 1.0 / (111_320.0 * cos(Math.toRadians(START_LAT)))
        return Loc(START_LAT + road.latOffset, START_LON + pos * lonPerM, 90.0, nowMs)
    }

    /** Each demo trip drives one road once and stops at the destination. */
    fun snapshot(elapsedSec: Long, nowMs: Long, trip: Int = 1, road: Road = Road.USUAL): DriveSnapshot {
        val pos = (elapsedSec * SPEED_MPS).coerceAtMost(road.lengthM.toLong()).toInt()
        return DriveSnapshot(
            source = DataSource.DEMO,
            routing = pos < road.lengthM,
            events = events(road, pos),
            remainDistanceM = road.lengthM - pos,
            remainTimeSec = (road.lengthM - pos) / 15,
            destinationName = "시연용 가상 목적지",
            location = locAt(road, pos, nowMs),
            routeId = tripId(trip),
        )
    }

    /** Whole-route profile, including how many of the family's demo sick zones the road passes. */
    fun stats(road: Road, zones: List<SickZone>): RouteStats {
        val onRoute = zones.filter { it.source == DataSource.DEMO }.count { z ->
            (0..road.lengthM step 100).any { p ->
                val l = locAt(road, p, 0)
                RevisitDetector.distanceM(l.lat, l.lon, z.lat, z.lon) <= ZoneAnalysis.ZONE_RADIUS_M
            }
        }
        return RouteStats.of(events(road, 0), onRoute, road.lengthM)
    }
}
