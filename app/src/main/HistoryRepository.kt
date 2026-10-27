package com.juliano.limpacel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HistoryEntry(
    val id: String,
    val timestamp: Long,
    val action: String,
    val outcomes: List<FileOutcome>,
    val beforeFree: Long,
    val afterFree: Long,
    val beforeBattery: Float?,
    val afterBattery: Float?,
    val beforeRam: Long,
    val afterRam: Long
) {
    val successCount: Int get() = outcomes.count { it.success }
}

/** Informações apenas no armazenamento interno do aplicativo; não há rede ou analytics. */
class HistoryRepository(context: Context) {
    private val prefs = context.getSharedPreferences("smart_clean_history", Context.MODE_PRIVATE)

    fun all(): List<HistoryEntry> = runCatching {
        val array = JSONArray(prefs.getString("events", "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val files = o.getJSONArray("files")
            HistoryEntry(
                o.getString("id"), o.getLong("at"), o.getString("action"),
                (0 until files.length()).map { j ->
                    val f = files.getJSONObject(j)
                    FileOutcome(f.getString("path"), f.getLong("bytes"), f.getBoolean("ok"), f.getString("detail"))
                }, o.getLong("beforeFree"), o.getLong("afterFree"),
                if (o.isNull("beforeBattery")) null else o.getDouble("beforeBattery").toFloat(),
                if (o.isNull("afterBattery")) null else o.getDouble("afterBattery").toFloat(),
                o.getLong("beforeRam"), o.getLong("afterRam")
            )
        }
    }.getOrDefault(emptyList())

    @Synchronized fun add(
        action: String,
        outcomes: List<FileOutcome>,
        beforeFree: Long,
        afterFree: Long,
        beforeBattery: Float?,
        afterBattery: Float?,
        beforeRam: Long,
        afterRam: Long
    ) {
        val entry = HistoryEntry(UUID.randomUUID().toString(), System.currentTimeMillis(),
            action, outcomes, beforeFree, afterFree, beforeBattery, afterBattery, beforeRam, afterRam)
        val json = JSONArray()
        (listOf(entry) + all()).take(100).forEach { event ->
            val details = JSONArray()
            event.outcomes.forEach { f ->
                details.put(JSONObject().put("path", f.path).put("bytes", f.bytes)
                    .put("ok", f.success).put("detail", f.detail))
            }
            json.put(JSONObject().put("id", event.id).put("at", event.timestamp)
                .put("action", event.action).put("files", details)
                .put("beforeFree", event.beforeFree).put("afterFree", event.afterFree)
                .put("beforeBattery", event.beforeBattery).put("afterBattery", event.afterBattery)
                .put("beforeRam", event.beforeRam).put("afterRam", event.afterRam))
        }
        check(prefs.edit().putString("events", json.toString()).commit()) { "Falha ao salvar histórico" }
    }

    fun clear() { prefs.edit().remove("events").apply() }
}
