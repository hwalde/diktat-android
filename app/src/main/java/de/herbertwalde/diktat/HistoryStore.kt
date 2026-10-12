package de.herbertwalde.diktat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Verlauf aller Aufnahmen (wie Handys History): Die Audiodatei bleibt erhalten, damit ein fehlgeschlagenes
 * oder unvollständiges Transkript jederzeit erneut berechnet werden kann.
 */
class HistoryStore(context: Context) {

    enum class Status { PENDING, OK, SUSPICIOUS, EMPTY, FAILED }

    data class Entry(
        val id: Long,
        val createdAt: Long,
        val durationMs: Long,
        val text: String,
        val status: Status,
        val error: String? = null,
        val targetApp: String? = null,
    )

    companion object {
        private const val MAX_ENTRIES = 100
    }

    private val dir = File(context.filesDir, "recordings").apply { mkdirs() }
    private val index = File(dir, "history.json")
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun notifyChanged() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    fun audioFile(id: Long) = File(dir, "$id.wav")

    @Synchronized
    fun all(): List<Entry> {
        if (!index.exists()) return emptyList()
        return try {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).map { arr.getJSONObject(it).toEntry() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(id: Long) = all().firstOrNull { it.id == id }

    @Synchronized
    fun put(entry: Entry) {
        val list = all().filterNot { it.id == entry.id }.toMutableList()
        list.add(0, entry)
        list.sortByDescending { it.createdAt }
        while (list.size > MAX_ENTRIES) audioFile(list.removeAt(list.size - 1).id).delete()
        save(list)
        notifyChanged()
    }

    @Synchronized
    fun delete(id: Long) {
        audioFile(id).delete()
        save(all().filterNot { it.id == id })
        notifyChanged()
    }

    private fun save(list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(dir, "history.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(index)
    }

    private fun Entry.toJson() = JSONObject()
        .put("id", id).put("createdAt", createdAt).put("durationMs", durationMs)
        .put("text", text).put("status", status.name).put("error", error).put("targetApp", targetApp)

    private fun JSONObject.toEntry() = Entry(
        id = getLong("id"),
        createdAt = getLong("createdAt"),
        durationMs = optLong("durationMs"),
        text = optString("text"),
        status = runCatching { Status.valueOf(getString("status")) }.getOrDefault(Status.FAILED),
        error = optString("error").takeIf { has("error") && !isNull("error") },
        targetApp = optString("targetApp").takeIf { has("targetApp") && !isNull("targetApp") },
    )
}
