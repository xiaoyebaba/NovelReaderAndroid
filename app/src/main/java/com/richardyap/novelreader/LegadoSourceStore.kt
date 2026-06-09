package com.richardyap.novelreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class LegadoSourceStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("legado_sources", Context.MODE_PRIVATE)
    private val indexKey = "source_index"
    private val dir = File(context.filesDir, "legado-sources").also { it.mkdirs() }

    fun list(): List<LegadoSourceRecord> {
        val raw = prefs.getString(indexKey, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.getJSONObject(index).toRecord() }
        }.getOrDefault(emptyList())
    }

    fun save(record: LegadoSourceRecord, script: String) {
        File(dir, record.scriptFileName).writeText(script, Charsets.UTF_8)
        val records = list().filterNot { it.id == record.id } + record
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        prefs.edit().putString(indexKey, array.toString()).apply()
    }

    fun delete(sourceId: String) {
        val records = list()
        val target = records.firstOrNull { it.id == sourceId } ?: return
        File(dir, target.scriptFileName).delete()
        val array = JSONArray()
        records.filterNot { it.id == sourceId }.forEach { array.put(it.toJson()) }
        prefs.edit().putString(indexKey, array.toString()).apply()
    }

    fun setEnabled(sourceId: String, enabled: Boolean) {
        val updated = list().map { record ->
            if (record.id == sourceId) record.copy(enabled = enabled) else record
        }
        val array = JSONArray()
        updated.forEach { array.put(it.toJson()) }
        prefs.edit().putString(indexKey, array.toString()).apply()
    }

    fun loadScript(record: LegadoSourceRecord): String {
        return File(dir, record.scriptFileName)
            .takeIf { it.exists() }
            ?.readText(Charsets.UTF_8)
            .orEmpty()
    }

    fun importScript(script: String): LegadoSourceRecord {
        val meta = parseLegadoSourceMeta(script)
        if (meta.name.isBlank()) error("书源缺少 @name")
        val record = LegadoSourceRecord(
            id = UUID.randomUUID().toString(),
            name = meta.name,
            version = meta.version,
            author = meta.author,
            url = meta.urls.firstOrNull().orEmpty(),
            group = meta.group,
            logo = meta.logo,
            type = meta.type.ifBlank { "novel" },
            enabled = meta.enabled,
            minDelay = meta.minDelay,
            tags = meta.tags,
            description = meta.description,
            updateUrl = meta.updateUrl,
            requireUrls = meta.requireUrls,
            scriptFileName = "${UUID.randomUUID()}.js",
            installedAt = System.currentTimeMillis(),
        )
        save(record, script)
        return record
    }
}

private fun LegadoSourceRecord.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("version", version)
    .put("author", author)
    .put("url", url)
    .put("group", group)
    .put("logo", logo)
    .put("type", type)
    .put("enabled", enabled)
    .put("minDelay", minDelay)
    .put("tags", JSONArray(tags))
    .put("description", description)
    .put("updateUrl", updateUrl)
    .put("requireUrls", JSONArray(requireUrls))
    .put("scriptFileName", scriptFileName)
    .put("installedAt", installedAt)

private fun JSONObject.toRecord(): LegadoSourceRecord = LegadoSourceRecord(
    id = optString("id", UUID.randomUUID().toString()),
    name = optString("name", ""),
    version = optString("version", ""),
    author = optString("author", ""),
    url = optString("url", ""),
    group = optString("group", ""),
    logo = optString("logo", ""),
    type = optString("type", "novel"),
    enabled = optBoolean("enabled", true),
    minDelay = optInt("minDelay", 0),
    tags = optJSONArray("tags")?.asStringList().orEmpty(),
    description = optString("description", ""),
    updateUrl = optString("updateUrl", ""),
    requireUrls = optJSONArray("requireUrls")?.asStringList().orEmpty(),
    scriptFileName = optString("scriptFileName", "${UUID.randomUUID()}.js"),
    installedAt = optLong("installedAt", System.currentTimeMillis()),
)
