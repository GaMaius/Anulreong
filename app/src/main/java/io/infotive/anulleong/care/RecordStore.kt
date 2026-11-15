package io.infotive.anulleong.care

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local-only, anonymous record log (no account, no voice audio, no upload).
 * Written atomically so a crash mid-write cannot corrupt the history.
 */
class RecordStore(context: Context) {
    private val file = File(context.filesDir, "care_records.json")

    @Synchronized
    fun load(): List<CareRecord> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { runCatching { fromJson(arr.getJSONObject(it)) }.getOrNull() }
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun save(records: List<CareRecord>) {
        val arr = JSONArray()
        records.forEach { arr.put(toJson(it)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun toJson(r: CareRecord) = JSONObject().apply {
        put("id", r.id); put("timeMs", r.timeMs); put("feeling", r.feeling.name); put("source", r.source.name)
        r.lat?.let { put("lat", it) }; r.lon?.let { put("lon", it) }; r.heading?.let { put("heading", it) }
        r.routeId?.let { put("routeId", it) }; r.steeringDeg?.let { put("steering", it.toDouble()) }
        r.brakeOn?.let { put("brake", it) }; r.gear?.let { put("gear", it) }
        r.nextEventTitle?.let { put("nextEvent", it) }
    }

    private fun fromJson(o: JSONObject) = CareRecord(
        id = o.getString("id"),
        timeMs = o.getLong("timeMs"),
        feeling = Feeling.valueOf(o.getString("feeling")),
        source = DataSource.valueOf(o.optString("source", "NONE")),
        lat = o.optDoubleOrNull("lat"), lon = o.optDoubleOrNull("lon"), heading = o.optDoubleOrNull("heading"),
        routeId = o.optString("routeId").ifEmpty { null },
        steeringDeg = o.optDoubleOrNull("steering")?.toFloat(),
        brakeOn = if (o.has("brake")) o.getBoolean("brake") else null,
        gear = o.optString("gear").ifEmpty { null },
        nextEventTitle = o.optString("nextEvent").ifEmpty { null },
    )

    private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k)) getDouble(k) else null
}
