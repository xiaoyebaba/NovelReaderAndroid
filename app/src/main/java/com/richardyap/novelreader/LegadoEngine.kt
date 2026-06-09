package com.richardyap.novelreader

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.UUID

class LegadoSourceRepository(private val context: Context) {
    private val store = LegadoSourceStore(context.applicationContext)
    private val runtimes = mutableMapOf<String, LegadoScriptRuntime>()

    fun loadSources(): List<LegadoSourceRecord> = store.list()
        .sortedWith(compareByDescending<LegadoSourceRecord> { it.installedAt }.thenBy { it.name.lowercase(Locale.getDefault()) })

    suspend fun importSource(uri: Uri): LegadoSourceRecord = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取书源文件")
        store.importScript(decodeText(bytes))
    }

    fun importSourceText(script: String): LegadoSourceRecord = store.importScript(script)

    fun deleteSource(sourceId: String) {
        synchronized(runtimes) {
            runtimes.remove(sourceId)?.close()
        }
        store.delete(sourceId)
    }

    fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        store.setEnabled(sourceId, enabled)
        if (!enabled) {
            synchronized(runtimes) {
                runtimes.remove(sourceId)?.close()
            }
        }
    }

    suspend fun search(sourceId: String, keyword: String, page: Int = 1): List<LegadoBookItem> {
        return runtimeFor(sourceId).search(keyword, page)
    }

    suspend fun bookInfo(sourceId: String, bookUrl: String): LegadoBookItem {
        return runtimeFor(sourceId).bookInfo(bookUrl)
    }

    suspend fun chapterList(sourceId: String, tocUrl: String): List<LegadoChapterInfo> {
        return runtimeFor(sourceId).chapterList(tocUrl)
    }

    suspend fun chapterContent(sourceId: String, chapterUrl: String): String {
        return runtimeFor(sourceId).chapterContent(chapterUrl)
    }

    suspend fun importRemoteBook(sourceId: String, bookUrl: String, novelRepository: NovelRepository): Book {
        val source = loadSources().firstOrNull { it.id == sourceId && it.enabled }
            ?: error("书源不可用")
        val book = bookInfo(sourceId, bookUrl)
        val tocUrl = book.tocUrl.ifBlank { book.bookUrl.ifBlank { bookUrl } }
        val chapters = chapterList(sourceId, tocUrl)
        if (chapters.isEmpty()) error("目录为空")

        val baseUrl = source.url.ifBlank { tocUrl.ifBlank { bookUrl } }
        val text = buildString {
            appendLine(book.name.ifBlank { "未命名小说" })
            if (book.author.isNotBlank()) appendLine(book.author)
            if (book.intro.isNotBlank()) {
                appendLine()
                appendLine(book.intro.trim())
            }
            chapters.forEachIndexed { index, chapter ->
                val chapterUrl = resolveLegadoUrl(baseUrl, chapter.url)
                val content = chapterContent(sourceId, chapterUrl).trim()
                appendLine()
                appendLine(chapter.name.ifBlank { "第${index + 1}章" })
                appendLine(content)
            }
        }.trim()

        return novelRepository.importTextBook(
            title = book.name.ifBlank { "未命名小说" },
            text = text,
            sourceName = source.name,
            bookUrl = bookUrl,
            tocUrl = tocUrl,
        )
    }

    fun close() {
        synchronized(runtimes) {
            runtimes.values.forEach { it.close() }
            runtimes.clear()
        }
    }

    private suspend fun runtimeFor(sourceId: String): LegadoScriptRuntime {
        val source = loadSources().firstOrNull { it.id == sourceId && it.enabled }
            ?: error("书源不可用")

        synchronized(runtimes) {
            runtimes[sourceId]?.let { return it }
        }

        val script = store.loadScript(source)
        val requires = source.requireUrls.map { fetchRemoteScript(it) }
        val runtime = LegadoScriptRuntime(source, script, requires)
        synchronized(runtimes) {
            runtimes[sourceId] = runtime
        }
        return runtime
    }

    private suspend fun fetchRemoteScript(url: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "NovelReaderAndroid/${BuildConfig.VERSION_NAME}")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            error("依赖脚本下载失败：$code")
        }
        val text = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        connection.disconnect()
        text
    }
}

private class LegadoScriptRuntime(
    private val source: LegadoSourceRecord,
    private val script: String,
    private val requires: List<String>,
) : AutoCloseable {
    private val lock = Any()
    @Volatile
    private var scope: ScriptableObject? = null
    @Volatile
    private var lastFetchedUrl: String = source.url

    fun search(keyword: String, page: Int): List<LegadoBookItem> {
        return parseBookItems(call("search", keyword, page))
    }

    fun bookInfo(bookUrl: String): LegadoBookItem {
        return parseBookItem(call("bookInfo", bookUrl)) ?: error("bookInfo() 没有返回有效对象")
    }

    fun chapterList(tocUrl: String): List<LegadoChapterInfo> {
        return parseChapterItems(call("chapterList", tocUrl))
    }

    fun chapterContent(chapterUrl: String): String {
        val result = call("chapterContent", chapterUrl)
        return when (result) {
            is String -> result
            is JSONObject -> result.optString("content", result.optString("text", result.toString()))
            is Map<*, *> -> result.stringValue("content", "text", "body", "html")
            else -> result?.toString().orEmpty()
        }
    }

    override fun close() {
        synchronized(lock) {
            scope = null
        }
    }

    private fun call(functionName: String, vararg args: Any?): Any? {
        val scopeRef = ensureScope()
        val cx = RhinoContext.enter()
        try {
            cx.optimizationLevel = -1
            val fn = ScriptableObject.getProperty(scopeRef, functionName)
            if (fn !is Function) {
                error("书源缺少函数：$functionName")
            }
            val jsArgs = args.map { arg -> RhinoContext.javaToJS(arg, scopeRef) }.toTypedArray()
            val raw = fn.call(cx, scopeRef, scopeRef, jsArgs)
            return parseResult(raw)
        } finally {
            RhinoContext.exit()
        }
    }

    private fun ensureScope(): ScriptableObject {
        synchronized(lock) {
            scope?.let { return it }
            val cx = RhinoContext.enter()
            try {
                cx.optimizationLevel = -1
                val s = cx.initStandardObjects() as ScriptableObject
                ScriptableObject.putProperty(s, "window", s)
                ScriptableObject.putProperty(s, "globalThis", s)
                ScriptableObject.putProperty(s, "self", s)
                ScriptableObject.putProperty(s, "legado", RhinoContext.javaToJS(LegadoHost(this), s))
                ScriptableObject.putProperty(s, "console", RhinoContext.javaToJS(LegadoConsole(), s))
                ScriptableObject.putProperty(s, "location", RhinoContext.javaToJS(LegadoLocation(source.url.ifBlank { lastFetchedUrl }), s))
                ScriptableObject.putProperty(s, "source", RhinoContext.javaToJS(source, s))

                requires.forEachIndexed { index, requireScript ->
                    cx.evaluateString(s, requireScript, "${source.name}-require-$index", 1, null)
                }
                cx.evaluateString(s, script, "${source.name}.js", 1, null)
                scope = s
                return s
            } finally {
                RhinoContext.exit()
            }
        }
    }

    private fun parseResult(value: Any?): Any? {
        val raw = value ?: return null
        val cx = RhinoContext.getCurrentContext() ?: RhinoContext.enter()
        return try {
            val scopeRef = ensureScope()
            ScriptableObject.putProperty(scopeRef, "__legado_result", raw)
            val json = cx.evaluateString(
                scopeRef,
                "(function(){ var v = __legado_result; return JSON.stringify(typeof v === 'undefined' ? null : v); })();",
                "${source.name}-result",
                1,
                null,
            )
            val text = RhinoContext.toString(json)
            if (text.isBlank() || text == "undefined") {
                null
            } else {
                JSONTokener(text).nextValue().takeUnless { it == JSONObject.NULL }
            }
        } finally {
            if (RhinoContext.getCurrentContext() == cx) {
                RhinoContext.exit()
            }
        }
    }

    private fun parseBookItems(value: Any?): List<LegadoBookItem> = when (value) {
        null -> emptyList()
        is String -> if (value.isBlank()) emptyList() else listOf(LegadoBookItem(name = value, bookUrl = value))
        is JSONArray -> (0 until value.length()).mapNotNull { index -> parseBookItem(value.opt(index)) }
        is List<*> -> value.mapNotNull { parseBookItem(it) }
        is JSONObject -> listOfNotNull(parseBookItem(value))
        is Map<*, *> -> listOfNotNull(parseBookItem(value))
        else -> emptyList()
    }

    private fun parseBookItem(value: Any?): LegadoBookItem? {
        val map = value.asObjectMap() ?: return null
        val name = map.stringValue("name", "title", "bookName", "label")
        val bookUrl = map.stringValue("bookUrl", "url", "link", "bookurl")
        if (name.isBlank() && bookUrl.isBlank()) return null
        return LegadoBookItem(
            name = name.ifBlank { bookUrl },
            bookUrl = bookUrl.ifBlank { name },
            author = map.stringValue("author", "authors"),
            coverUrl = map.stringValue("coverUrl", "cover"),
            tocUrl = map.stringValue("tocUrl", "toc", "catalogUrl"),
            intro = map.stringValue("intro", "desc", "description"),
            latestChapter = map.stringValue("latestChapter", "lastChapter", "latest"),
            latestChapterUrl = map.stringValue("latestChapterUrl", "lastChapterUrl"),
            wordCount = map.stringValue("wordCount", "words"),
            chapterCount = map.intValue("chapterCount", "chapters"),
            updateTime = map.stringValue("updateTime", "date"),
            status = map.stringValue("status"),
            kind = map.stringValue("kind", "type"),
        )
    }

    private fun parseChapterItems(value: Any?): List<LegadoChapterInfo> = when (value) {
        null -> emptyList()
        is String -> if (value.isBlank()) emptyList() else listOf(LegadoChapterInfo(name = value, url = value))
        is JSONArray -> (0 until value.length()).mapNotNull { index -> parseChapterItem(value.opt(index)) }
        is List<*> -> value.mapNotNull { parseChapterItem(it) }
        is JSONObject -> listOfNotNull(parseChapterItem(value))
        is Map<*, *> -> listOfNotNull(parseChapterItem(value))
        else -> emptyList()
    }

    private fun parseChapterItem(value: Any?): LegadoChapterInfo? {
        val map = value.asObjectMap() ?: return null
        val name = map.stringValue("name", "title", "chapterName")
        val url = map.stringValue("url", "chapterUrl", "link")
        if (name.isBlank() && url.isBlank()) return null
        return LegadoChapterInfo(
            name = name.ifBlank { url },
            url = url.ifBlank { name },
            vip = map.booleanValue("vip", "isVip"),
        )
    }

    private fun httpRequest(method: String, url: String, headers: Any? = null, body: Any? = null): String {
        val requestMethod = method.uppercase(Locale.getDefault())
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            this.requestMethod = requestMethod
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NovelReaderAndroid/${BuildConfig.VERSION_NAME}")
            normalizeHeaders(headers).forEach { (key, value) -> setRequestProperty(key, value) }
        }

        normalizeBody(body)?.let { payload ->
            if (requestMethod !in setOf("GET", "HEAD")) {
                connection.doOutput = true
                if (connection.getRequestProperty("Content-Type").isNullOrBlank()) {
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
                connection.outputStream.use { output -> output.write(payload.toByteArray(Charsets.UTF_8)) }
            }
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        lastFetchedUrl = connection.url.toString()
        connection.disconnect()
        return text
    }

    private fun normalizeHeaders(value: Any?): Map<String, String> = when (val raw = value.normalizeJsValue()) {
        null -> emptyMap()
        is String -> runCatching { JSONObject(raw).toStringMap() }.getOrDefault(emptyMap())
        is Map<*, *> -> raw.entries.associate { (key, item) -> key.toString() to item.toString() }.filterValues { it.isNotBlank() }
        else -> emptyMap()
    }

    private fun normalizeBody(value: Any?): String? = when (val raw = value.normalizeJsValue()) {
        null -> null
        is String -> raw
        is Number, is Boolean -> raw.toString()
        is JSONObject -> raw.toString()
        is JSONArray -> raw.toString()
        is Map<*, *> -> JSONObject(raw).toString()
        is List<*> -> JSONArray(raw).toString()
        else -> raw.toString()
    }

    private fun element(handle: Any?): Element? = when (val value = handle.normalizeJsValue()) {
        is Element -> value
        is Document -> value
        is String -> Jsoup.parse(value, lastFetchedUrl)
        else -> null
    }

    private fun nodeText(handle: Any?): String = when (val value = handle.normalizeJsValue()) {
        is Element -> value.text()
        is Document -> value.text()
        is String -> value.trim()
        else -> value?.toString().orEmpty()
    }

    private fun nodeHtml(handle: Any?): String = when (val value = handle.normalizeJsValue()) {
        is Element -> value.outerHtml()
        is Document -> value.outerHtml()
        is String -> value
        else -> value?.toString().orEmpty()
    }

    private fun nodeAttr(handle: Any?, name: String): String = element(handle)?.let { element ->
        if (name.startsWith("abs:")) element.absUrl(name.removePrefix("abs:")) else element.attr(name)
    }.orEmpty()

    private fun unwrap(value: Any?): Any? = when (value) {
        null -> null
        is org.mozilla.javascript.Wrapper -> value.unwrap()
        else -> value
    }

    private inner class LegadoHost(private val runtime: LegadoScriptRuntime) {
        fun getHttp(): LegadoHttpApi = LegadoHttpApi(runtime)
        fun getDom(): LegadoDomApi = LegadoDomApi(runtime)
        fun getBrowser(): LegadoBrowserApi = LegadoBrowserApi()
        fun getRuntime(): LegadoRuntimeInfo = LegadoRuntimeInfo()
        fun log(vararg args: Any?) { Log.d("Legado", args.joinToString(" ")) }
        fun toast(vararg args: Any?) { Log.d("Legado", args.joinToString(" ")) }
    }

    private inner class LegadoHttpApi(private val runtime: LegadoScriptRuntime) {
        fun get(url: Any?, headers: Any? = null): String = runtime.httpRequest("GET", url.toString(), headers = headers)
        fun post(url: Any?, body: Any? = null, headers: Any? = null): String = runtime.httpRequest("POST", url.toString(), headers = headers, body = body)
        fun request(options: Any?): String {
            val map = options.asObjectMap() ?: error("http.request 需要对象参数")
            val url = map.stringValue("url")
            val method = map.stringValue("method").ifBlank { "GET" }
            val headers = map["headers"]
            val body = map["body"]
            return runtime.httpRequest(method, url, headers = headers, body = body)
        }
        fun batchGet(urls: Any?, headers: Any? = null): Array<String> {
            val list = urls.asStringList()
            return list.map { runtime.httpRequest("GET", it, headers = headers) }.toTypedArray()
        }
    }

    private inner class LegadoDomApi(private val runtime: LegadoScriptRuntime) {
        fun parse(html: Any?, baseUrl: String? = null): Element = Jsoup.parse(html.toString(), baseUrl ?: runtime.lastFetchedUrl)
        fun select(handle: Any?, selector: String): Element? = runtime.element(handle)?.selectFirst(selector)
        fun selectAll(handle: Any?, selector: String): Array<Element> = runtime.element(handle)?.select(selector)?.toList()?.toTypedArray() ?: emptyArray()
        fun text(handle: Any?): String = runtime.nodeText(handle)
        fun html(handle: Any?): String = runtime.nodeHtml(handle)
        fun attr(handle: Any?, name: String): String = runtime.nodeAttr(handle, name)
        fun selectText(handle: Any?, selector: String): String = select(handle, selector)?.text().orEmpty()
        fun selectAllTexts(handle: Any?, selector: String): Array<String> = selectAll(handle, selector).map { it.text() }.toTypedArray()
        fun selectAttr(handle: Any?, selector: String, attrName: String): String = select(handle, selector)?.attr(attrName).orEmpty()
        fun selectAllAttrs(handle: Any?, selector: String, attrName: String): Array<String> = selectAll(handle, selector).map { it.attr(attrName) }.toTypedArray()
        fun ownText(handle: Any?): String = runtime.element(handle)?.ownText().orEmpty()
        fun remove(handle: Any?, selector: String): String {
            val clone = runtime.element(handle)?.clone() ?: return ""
            clone.select(selector).remove()
            return clone.outerHtml()
        }
        fun selectByText(handle: Any?, text: String): Element? {
            val root = runtime.element(handle) ?: return null
            return root.select("*").firstOrNull { it.text().contains(text) } ?: if (root.text().contains(text)) root else null
        }
    }

    private inner class LegadoBrowserApi {
        fun run(vararg args: Any?): Any {
            error("browser API 暂未在竹简阅读中实现")
        }
    }

    private data class LegadoRuntimeInfo(
        val platform: String = "android",
        val engine: String = "rhino",
        val os: String = "android",
    )
}

private fun decodeText(bytes: ByteArray): String {
    val charsets = listOf(
        Charsets.UTF_8,
        Charset.forName("GB18030"),
        Charset.forName("GBK"),
        Charsets.UTF_16LE,
        Charsets.UTF_16BE,
    )
    for (charset in charsets) {
        try {
            return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .stripBom()
        } catch (_: CharacterCodingException) {
            // try next encoding
        }
    }
    return String(bytes, Charsets.UTF_8).stripBom()
}

private fun String.stripBom(): String = removePrefix("\uFEFF")

private fun Any?.normalizeJsValue(): Any? = when (this) {
    null -> null
    is org.mozilla.javascript.Wrapper -> this.unwrap().normalizeJsValue()
    is JSONObject -> (this as JSONObject).toMapRecursively()
    is JSONArray -> (0 until length()).map { index -> opt(index).normalizeJsValue() }
    is org.mozilla.javascript.Scriptable -> (this as org.mozilla.javascript.Scriptable).toMapRecursively()
    else -> this
}

private fun Any?.normalizeJsonValue(): Any? = when (this) {
    null, JSONObject.NULL -> null
    is JSONObject -> (this as JSONObject).toMapRecursively()
    is JSONArray -> (0 until length()).map { index -> opt(index).normalizeJsonValue() }
    else -> this
}

private fun Any?.asObjectMap(): Map<String, Any?>? = when (val raw = normalizeJsValue()) {
    null -> null
    is Map<*, *> -> raw.entries.associate { (key, value) -> key.toString() to value }
    else -> null
}

private fun Any?.asStringList(): List<String> = when (val raw = normalizeJsValue()) {
    null -> emptyList()
    is String -> listOf(raw).filter { it.isNotBlank() }
    is List<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf { text -> text.isNotBlank() } }
    is JSONArray -> (0 until raw.length()).mapNotNull { index -> raw.opt(index)?.toString()?.trim()?.takeIf { text -> text.isNotBlank() } }
    else -> listOf(raw.toString()).filter { it.isNotBlank() }
}

private fun JSONObject.toMapRecursively(): Map<String, Any?> {
    val out = linkedMapOf<String, Any?>()
    keys().forEach { key ->
        val value = opt(key)
        if (value != null && value != JSONObject.NULL) {
            out[key] = when (value) {
                is JSONObject -> value.toMapRecursively()
                is JSONArray -> (0 until value.length()).map { index -> value.opt(index).normalizeJsonValue() }
                else -> value.normalizeJsValue()
            }
        }
    }
    return out
}

private fun org.mozilla.javascript.Scriptable.toMapRecursively(): Map<String, Any?> {
    val out = linkedMapOf<String, Any?>()
    getIds().forEach { id ->
        val key = id.toString()
        out[key] = ScriptableObject.getProperty(this, key).normalizeJsValue()
    }
    return out
}

private fun Map<*, *>.stringValue(vararg keys: String): String {
    for (key in keys) {
        val value = this[key] ?: continue
        val text = when (value) {
            null, JSONObject.NULL -> ""
            is String -> value.trim()
            is Number, is Boolean -> value.toString().trim()
            is JSONObject -> value.toString()
            is JSONArray -> value.toString()
            is Map<*, *> -> JSONObject(value).toString()
            is List<*> -> JSONArray().apply { value.forEach { put(it) } }.toString()
            else -> value.toString().trim()
        }
        if (text.isNotBlank()) return text
    }
    return ""
}

private fun Map<*, *>.intValue(vararg keys: String): Int = stringValue(*keys).toIntOrNull() ?: 0

private fun Map<*, *>.booleanValue(vararg keys: String): Boolean {
    for (key in keys) {
        val value = this[key] ?: continue
        when (value) {
            is Boolean -> return value
            is Number -> return value.toInt() != 0
            is String -> {
                if (value.equals("true", ignoreCase = true) || value == "1") return true
                if (value.equals("false", ignoreCase = true) || value == "0") return false
            }
        }
    }
    return false
}

private class LegadoConsole {
    fun log(vararg args: Any?) {
        Log.d("Legado", args.joinToString(" "))
    }

    fun debug(vararg args: Any?) = log(*args)
    fun info(vararg args: Any?) = log(*args)
    fun warn(vararg args: Any?) = Log.w("Legado", args.joinToString(" "))
    fun error(vararg args: Any?) = Log.e("Legado", args.joinToString(" "))
}

private data class LegadoLocation(
    val href: String = "",
    val origin: String = "",
    val protocol: String = "",
    val host: String = "",
    val hostname: String = "",
    val port: String = "",
    val pathname: String = "",
    val search: String = "",
    val hash: String = "",
)
