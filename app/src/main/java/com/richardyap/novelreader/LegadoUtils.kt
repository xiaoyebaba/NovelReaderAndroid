package com.richardyap.novelreader

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.UUID

data class LegadoSourceMeta(
    val name: String = "",
    val version: String = "",
    val author: String = "",
    val urls: List<String> = emptyList(),
    val group: String = "",
    val logo: String = "",
    val type: String = "novel",
    val enabled: Boolean = true,
    val minDelay: Int = 0,
    val tags: List<String> = emptyList(),
    val description: String = "",
    val updateUrl: String = "",
    val requireUrls: List<String> = emptyList(),
)

fun parseLegadoSourceMeta(script: String): LegadoSourceMeta {
    val lines = script.replace("\r\n", "\n").replace('\r', '\n').lineSequence().take(80).toList()
    val urls = mutableListOf<String>()
    val requires = mutableListOf<String>()
    val tags = mutableListOf<String>()
    val description = mutableListOf<String>()
    var name = ""
    var version = ""
    var author = ""
    var group = ""
    var logo = ""
    var type = "novel"
    var enabled = true
    var minDelay = 0
    var updateUrl = ""

    lines.forEach { line ->
        val match = Regex("^//\\s*@([A-Za-z]+)\\s*(.*)$").find(line.trim()) ?: return@forEach
        val key = match.groupValues[1].lowercase(Locale.getDefault())
        val value = match.groupValues[2].trim()
        when (key) {
            "name" -> if (name.isBlank()) name = value
            "version" -> if (version.isBlank()) version = value
            "author" -> if (author.isBlank()) author = value
            "url" -> if (value.isNotBlank()) urls += value
            "group" -> if (group.isBlank()) group = value
            "logo" -> if (logo.isBlank()) logo = value
            "type" -> if (type.isBlank()) type = value else if (type == "novel") type = value
            "enabled" -> enabled = value.equals("true", ignoreCase = true)
            "mindelay" -> minDelay = value.toIntOrNull() ?: 0
            "tags" -> if (value.isNotBlank()) tags += value.split(',').map { it.trim() }.filter { it.isNotBlank() }
            "description" -> if (value.isNotBlank()) description += value
            "updateurl" -> if (updateUrl.isBlank()) updateUrl = value
            "require" -> if (value.isNotBlank()) requires += value
        }
    }

    return LegadoSourceMeta(
        name = name,
        version = version,
        author = author,
        urls = urls,
        group = group,
        logo = logo,
        type = type.ifBlank { "novel" },
        enabled = enabled,
        minDelay = minDelay,
        tags = tags,
        description = description.joinToString("\n").trim(),
        updateUrl = updateUrl,
        requireUrls = requires,
    )
}

fun resolveLegadoUrl(base: String, path: String): String {
    val trimmed = path.trim()
    if (trimmed.isBlank()) return trimmed
    return runCatching { URI(base).resolve(trimmed).toString() }.getOrElse { trimmed }
}

fun jsonQuote(value: String): String = JSONObject.quote(value)

fun JSONArray.asStringList(): List<String> = List(length()) { index -> optString(index) }.filter { it.isNotBlank() }

fun JSONObject.toStringMap(): Map<String, String> {
    val out = linkedMapOf<String, String>()
    keys().forEach { key ->
        val value = opt(key)
        if (value != null && value != JSONObject.NULL) {
            out[key] = value.toString()
        }
    }
    return out
}

fun UUID.fileNameJs(): String = "$this.js"
