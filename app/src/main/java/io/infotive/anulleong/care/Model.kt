package io.infotive.anulleong.care

/** Where the data on screen came from. Shown to the user at all times. */
enum class DataSource { NAVI, DEMO, NONE }

enum class Severity { GENTLE, NOTICE, STRONG }

enum class EventKind { TURN, SHARP_TURN, RAMP, MERGE, SLOWDOWN, BUMP, DOWNHILL, ROUGH_ROAD, NARROW, REST, ARRIVE,
    /** A spot where this family recorded 🤢 before (from their own records, not navigation). */
    PAST_SICK }

/**
 * Something ahead on the route that may jolt or sway the car.
 * [distanceM] is null when the navigation SDK does not tell us the distance from the car
 * (for TBT only the first item is treated as "next"; later items keep their order only).
 */
data class AheadEvent(
    val key: String,
    val kind: EventKind,
    val severity: Severity,
    val title: String,
    val kidLine: String,
    val distanceM: Int?,
    val fromCautionPoint: Boolean,
)

data class Loc(val lat: Double, val lon: Double, val heading: Double?, val receivedAtMs: Long)

data class DriveSnapshot(
    val source: DataSource,
    val routing: Boolean,
    val events: List<AheadEvent>,
    val remainDistanceM: Int?,
    val remainTimeSec: Int?,
    val destinationName: String?,
    val location: Loc?,
    val routeId: String?,
) {
    companion object {
        val EMPTY = DriveSnapshot(DataSource.NONE, false, emptyList(), null, null, null, null, null)
    }
}

enum class Feeling(val label: String, val emoji: String) {
    OK("괜찮아요", "😊"),
    UNEASY("조금 불편해요", "😐"),
    SICK("멀미 나요", "🤢"),
}

data class CareRecord(
    val id: String,
    val timeMs: Long,
    val feeling: Feeling,
    val source: DataSource,
    val lat: Double?,
    val lon: Double?,
    val heading: Double?,
    val routeId: String?,
    val steeringDeg: Float?,
    val brakeOn: Boolean?,
    val gear: String?,
    val nextEventTitle: String?,
)
