package pl.necek.wywoz

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

data class Schedule(val fetchedAt: Long, val pickups: List<Pickup>)

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("wywoz", Context.MODE_PRIVATE)
    private val scheduleFile = File(context.filesDir, "schedule.json")
    private val rawFile = File(context.filesDir, "pdf.txt")

    val street: String? get() = prefs.getString("street", null)
    val number: String? get() = prefs.getString("number", null)
    val numberId: String? get() = prefs.getString("numberId", null)
    val address: String get() = listOfNotNull(street, number).joinToString(" ")

    fun setAddress(street: String, number: String, numberId: String) {
        prefs.edit()
            .putString("street", street)
            .putString("number", number)
            .putString("numberId", numberId)
            .apply()
        scheduleFile.delete()
        rawFile.delete()
    }

    fun saveSchedule(schedule: Schedule) {
        val pickups = JSONArray()
        schedule.pickups.forEach {
            pickups.put(JSONObject().put("date", it.date.toString()).put("types", JSONArray(it.types)))
        }
        val tmp = File(scheduleFile.parentFile, scheduleFile.name + ".tmp")
        tmp.writeText(JSONObject().put("fetchedAt", schedule.fetchedAt).put("pickups", pickups).toString())
        tmp.renameTo(scheduleFile)
    }

    fun loadSchedule(): Schedule? = runCatching {
        val o = JSONObject(scheduleFile.readText())
        val pickups = o.getJSONArray("pickups")
        Schedule(o.getLong("fetchedAt"), (0 until pickups.length()).map { i ->
            val p = pickups.getJSONObject(i)
            val types = p.getJSONArray("types")
            Pickup(LocalDate.parse(p.getString("date")), (0 until types.length()).map { types.getString(it) })
        })
    }.getOrNull()

    var rawText: String?
        get() = if (rawFile.exists()) rawFile.readText() else null
        set(value) {
            if (value == null) rawFile.delete() else rawFile.writeText(value)
        }
}
