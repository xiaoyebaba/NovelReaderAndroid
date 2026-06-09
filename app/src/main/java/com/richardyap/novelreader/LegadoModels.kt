package com.richardyap.novelreader

data class LegadoSourceRecord(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val url: String,
    val group: String,
    val logo: String,
    val type: String,
    val enabled: Boolean,
    val minDelay: Int,
    val tags: List<String>,
    val description: String,
    val updateUrl: String,
    val requireUrls: List<String>,
    val scriptFileName: String,
    val installedAt: Long,
)

data class LegadoBookItem(
    val name: String,
    val bookUrl: String,
    val author: String = "",
    val coverUrl: String = "",
    val tocUrl: String = "",
    val intro: String = "",
    val latestChapter: String = "",
    val latestChapterUrl: String = "",
    val wordCount: String = "",
    val chapterCount: Int = 0,
    val updateTime: String = "",
    val status: String = "",
    val kind: String = "",
)

data class LegadoChapterInfo(
    val name: String,
    val url: String,
    val vip: Boolean = false,
)

