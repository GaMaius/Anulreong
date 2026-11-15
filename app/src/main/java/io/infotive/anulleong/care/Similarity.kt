package io.infotive.anulleong.care

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * "유사 구간 예측": which *kinds* of road sections (roundabout, ramp, speed bump…) this child
 * tends to get sick around, measured against how often the family actually drove through them.
 *
 * sick  = 🤢 records attributed to that kind
 * passed = times the car passed a section of that kind (counted by the app while driving)
 * A kind is flagged once it has a sick record and sick/passed ≥ 30 %. Flagged kinds are then
 * warned about earlier on *any* road — including roads the family has never driven.
 * It is a per-family pattern from their own data, not a population model.
 */
data class KindSensitivity(val kind: EventKind, val sick: Int, val passed: Int) {
    val rate: Float get() = if (passed <= 0) 1f else (sick.toFloat() / passed).coerceAtMost(1f)
    val flagged: Boolean get() = sick >= 1 && rate >= SimilarityModel.FLAG_RATE
    val label: String get() = SimilarityModel.label(kind)
}

object SimilarityModel {
    const val FLAG_RATE = 0.3f

    /** Kinds that make sense to learn from (sway/jolt sections). */
    val LEARNABLE = setOf(EventKind.TURN, EventKind.SHARP_TURN, EventKind.RAMP, EventKind.MERGE,
        EventKind.BUMP, EventKind.DOWNHILL, EventKind.ROUGH_ROAD, EventKind.NARROW, EventKind.SLOWDOWN)

    fun label(kind: EventKind) = when (kind) {
        EventKind.TURN -> "회전 구간"
        EventKind.SHARP_TURN -> "회전교차로·급회전"
        EventKind.RAMP -> "진출입로"
        EventKind.MERGE -> "갈림길"
        EventKind.SLOWDOWN -> "감속 구간"
        EventKind.BUMP -> "과속방지턱"
        EventKind.DOWNHILL -> "내리막길"
        EventKind.ROUGH_ROAD -> "울퉁불퉁한 길"
        EventKind.NARROW -> "좁은 길"
        else -> "구간"
    }

    fun sensitivities(records: List<CareRecord>, passes: Map<EventKind, Int>, source: DataSource): List<KindSensitivity> {
        val sick = records.filter { it.feeling == Feeling.SICK && it.source == source && it.cueKind in LEARNABLE }
            .groupingBy { it.cueKind!! }.eachCount()
        return (sick.keys + passes.keys.filter { it in LEARNABLE }).distinct()
            .map { KindSensitivity(it, sick[it] ?: 0, maxOf(passes[it] ?: 0, sick[it] ?: 0)) }
            .filter { it.sick > 0 || it.passed > 0 }
            .sortedWith(compareByDescending<KindSensitivity> { it.flagged }.thenByDescending { it.rate }.thenByDescending { it.passed })
    }

    fun flaggedKinds(records: List<CareRecord>, passes: Map<EventKind, Int>, source: DataSource): Set<EventKind> =
        sensitivities(records, passes, source).filter { it.flagged }.map { it.kind }.toSet()

    /**
     * Marks upcoming sections of a flagged kind. A section right next to a known sick *spot* is
     * left to the revisit alert so the family does not hear two warnings for one place.
     */
    fun annotate(events: List<AheadEvent>, flagged: Set<EventKind>): List<AheadEvent> {
        if (flagged.isEmpty()) return events
        val spots = events.filter { it.kind == EventKind.PAST_SICK }.mapNotNull { it.distanceM }
        return events.map { e ->
            val nearSpot = e.distanceM != null && spots.any { kotlin.math.abs(it - e.distanceM) < 400 }
            if (e.kind in flagged && !nearSpot) e.copy(
                similarRisk = true,
                kidLine = "지난번에 비슷한 ${label(e.kind)}에서 속이 안 좋았어요. " + e.kidLine,
            ) else e
        }
    }

    /**
     * Which section a 🤢 belongs to: the section passed in the last 60 s, otherwise the next
     * section if it is within 300 m. Null if neither (then the record still counts as a spot).
     */
    fun attribute(recentlyPassed: Pair<AheadEvent, Long>?, upcoming: List<AheadEvent>, nowMs: Long): AheadEvent? {
        recentlyPassed?.let { (e, at) -> if (nowMs - at <= 60_000 && e.kind in LEARNABLE) return e }
        return upcoming.firstOrNull { it.kind in LEARNABLE && it.distanceM != null && it.distanceM <= 300 }
    }
}

/**
 * Detects sections the car has just driven through: an event that was within 200 m and has
 * now disappeared from the list (navigation drops sections once they are passed).
 */
class PassTracker {
    private var last: Map<String, AheadEvent> = emptyMap()

    fun update(events: List<AheadEvent>): List<AheadEvent> {
        val now = events.associateBy { it.key }
        val passed = last.values.filter { e ->
            e.key !in now && e.distanceM != null && e.distanceM <= 200 &&
                e.kind != EventKind.ARRIVE && e.kind != EventKind.PAST_SICK
        }
        last = now
        return passed
    }

    fun reset() { last = emptyMap() }
}

/** Persists how many times each kind of section was passed, per data source. */
class ExposureStore(context: Context) {
    private val file = File(context.filesDir, "exposure.json")

    @Synchronized
    fun load(source: DataSource): Map<EventKind, Int> = try {
        val o = if (file.exists()) JSONObject(file.readText()) else JSONObject()
        val src = o.optJSONObject(source.name) ?: JSONObject()
        src.keys().asSequence().mapNotNull { k -> runCatching { EventKind.valueOf(k) to src.getInt(k) }.getOrNull() }.toMap()
    } catch (e: Exception) { emptyMap() }

    @Synchronized
    fun add(source: DataSource, kind: EventKind) {
        val o = try { if (file.exists()) JSONObject(file.readText()) else JSONObject() } catch (e: Exception) { JSONObject() }
        val src = o.optJSONObject(source.name) ?: JSONObject().also { o.put(source.name, it) }
        src.put(kind.name, src.optInt(kind.name, 0) + 1)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    @Synchronized
    fun clear(source: DataSource) {
        val o = try { if (file.exists()) JSONObject(file.readText()) else JSONObject() } catch (e: Exception) { JSONObject() }
        o.remove(source.name)
        file.writeText(o.toString())
    }
}
