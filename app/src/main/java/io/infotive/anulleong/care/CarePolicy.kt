package io.infotive.anulleong.care

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Decides when to speak a cue, without nagging the driver. Pure, time is injected. */
class CuePolicy(
    private val announceWithinM: Int = 300,
    private val minGapMs: Long = 15_000,
    private val sameKeyCooldownMs: Long = 90_000,
) {
    private var lastAnyMs = Long.MIN_VALUE / 2
    private val lastByKey = HashMap<String, Long>()

    /** Returns the event to announce now, or null. */
    fun pick(events: List<AheadEvent>, nowMs: Long): AheadEvent? {
        if (nowMs - lastAnyMs < minGapMs) return null
        val candidate = events.firstOrNull { e ->
            val d = e.distanceM ?: return@firstOrNull false
            d in 0..(if (e.similarRisk) announceWithinM + 200 else announceWithinM) && e.kind != EventKind.ARRIVE && e.kind != EventKind.PAST_SICK &&
                nowMs - (lastByKey[e.key] ?: Long.MIN_VALUE / 2) >= sameKeyCooldownMs
        } ?: return null
        lastAnyMs = nowMs
        lastByKey[candidate.key] = nowMs
        return candidate
    }
}

object RecordRules {
    /** Same feeling tapped again within this window is treated as a double tap. */
    const val DUPLICATE_WINDOW_MS = 10_000L
    /** Locations older than this are not attached to a record. */
    const val MAX_LOCATION_AGE_MS = 60_000L

    fun isDuplicate(last: CareRecord?, feeling: Feeling, nowMs: Long): Boolean =
        last != null && last.feeling == feeling && nowMs - last.timeMs in 0 until DUPLICATE_WINDOW_MS

    fun usableLocation(loc: Loc?, nowMs: Long): Loc? =
        loc?.takeIf { nowMs - it.receivedAtMs in 0..MAX_LOCATION_AGE_MS }
}

/**
 * "You recorded sickness near here before" — based on the family's own past records,
 * not a prediction. Requires matching direction so the opposite lane does not trigger.
 *
 * Records only count for the same kind of data: synthetic demo records never fire on a
 * real drive and vice versa. A record made on the current trip is ignored for 30 minutes
 * so the alert is about *coming back*, not the spot the child just reported.
 */
class RevisitDetector(
    private val radiusM: Double = 400.0,
    private val aheadRangeM: Double = 1500.0,
    private val maxHeadingDiff: Double = 60.0,
    private val cooldownMs: Long = 10 * 60_000L,
) {
    private val lastAlert = HashMap<String, Long>()

    data class Ahead(val record: CareRecord, val distanceM: Int)

    /** Past sick spots that lie ahead of the car (same direction, roughly in front). */
    fun ahead(here: Loc?, records: List<CareRecord>, nowMs: Long, source: DataSource, tripId: String?): List<Ahead> {
        if (here == null) return emptyList()
        return candidates(records, nowMs, source, tripId)
            .map { Ahead(it, distanceM(here.lat, here.lon, it.lat!!, it.lon!!).toInt()) }
            .filter { it.distanceM <= aheadRangeM }
            .filter { headingClose(here.heading, it.record.heading) }
            .filter { a ->
                // Within 30 m we are "at" the spot; beyond that it must be in front of the car
                // and close to the line the car is driving (a parallel road must not count).
                if (a.distanceM < 30 || here.heading == null) return@filter true
                val b = bearing(here.lat, here.lon, a.record.lat!!, a.record.lon!!)
                val off = Math.toRadians(((b - here.heading) % 360 + 540) % 360 - 180)
                headingClose(here.heading, b) && abs(a.distanceM * sin(off)) <= ZoneAnalysis.ZONE_RADIUS_M
            }
            .sortedBy { it.distanceM }
            .toList()
    }

    /** The record to announce now (within [radiusM]), honouring a per-record cooldown. */
    fun check(here: Loc?, records: List<CareRecord>, nowMs: Long, source: DataSource, tripId: String?): CareRecord? =
        ahead(here, records, nowMs, source, tripId)
            .firstOrNull { it.distanceM <= radiusM && nowMs - (lastAlert[it.record.id] ?: Long.MIN_VALUE / 2) >= cooldownMs }
            ?.record?.also { lastAlert[it.id] = nowMs }

    fun reset() = lastAlert.clear()

    private fun candidates(records: List<CareRecord>, nowMs: Long, source: DataSource, tripId: String?) =
        records.asSequence()
            .filter { it.feeling == Feeling.SICK && it.lat != null && it.lon != null }
            .filter { it.source == source }
            .filter { !(tripId != null && it.routeId == tripId && nowMs - it.timeMs < 30 * 60_000L) }

    private fun headingClose(a: Double?, b: Double?): Boolean {
        if (a == null || b == null) return true
        val d = abs(((a - b) % 360 + 540) % 360 - 180)
        return d <= maxHeadingDiff
    }

    companion object {
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }

        fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }
    }
}

/** Window "open a little" target from the capability bounds the car reports. */
object VentRules {
    /**
     * Assumes the lower bound is "closed" (Android Automotive WINDOW_POS convention).
     * The result is always re-read from the car afterwards, so a wrong assumption shows
     * up as "not confirmed" instead of a false success message.
     */
    fun slightlyOpenTarget(min: Int, max: Int, current: Int): Int? {
        if (max <= min) return null
        val target = (min + ((max - min) * 0.2)).toInt().coerceAtLeast(min + 1).coerceAtMost(max)
        return if (current >= target) null else target
    }
}
