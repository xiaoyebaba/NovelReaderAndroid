package com.richardyap.novelreader

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LegadoSourceDialogSheet(
    sourceRepository: LegadoSourceRepository,
    novelRepository: NovelRepository,
    sources: List<LegadoSourceRecord>,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    onImportBook: (String, String) -> Unit,
) {
    var scriptText by remember { mutableStateOf("") }
    var legacyRuleText by remember { mutableStateOf("") }
    var urlText by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf("") }
    var importMessage by remember { mutableStateOf("") }
    var conversionWarnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<LegadoBookItem>>(emptyList()) }
    var selectedSourceId by remember(sources) { mutableStateOf(sources.firstOrNull()?.id.orEmpty()) }
    var isSearching by remember { mutableStateOf(false) }
    val checkingIds = remember { mutableStateListOf<String>() }
    val healthResults = remember { mutableMapOf<String, SourceHealthResult>() }
    val scope = rememberCoroutineScope()

    val filteredSources = remember(sources, sourceFilter) {
        if (sourceFilter.isBlank()) sources
        else {
            val q = sourceFilter.trim().lowercase()
            sources.filter { s ->
                s.name.lowercase().contains(q) ||
                        s.group.lowercase().contains(q) ||
                        s.author.lowercase().contains(q)
            }
        }
    }

    fun refreshSources() {
        onChanged()
        if (selectedSourceId.isBlank()) {
            selectedSourceId = sourceRepository.loadSources().firstOrNull()?.id.orEmpty()
        }
    }

    fun importScript() {
        if (scriptText.isBlank()) return
        runCatching {
            val imported = sourceRepository.batchImportSourceText(scriptText)
            if (imported.size == 1) {
                importMessage = "已导入：${imported.first().name}"
            } else {
                importMessage = "已批量导入 ${imported.size} 个书源"
            }
        }.onSuccess {
            scriptText = ""
            refreshSources()
        }.onFailure {
            importMessage = "导入失败：${it.message ?: "未知错误"}"
        }
    }

    fun importFromUrl() {
        val url = urlText.trim()
        if (url.isBlank()) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    sourceRepository.importSourceFromUrl(url)
                }
            }.onSuccess { imported ->
                urlText = ""
                if (imported.size == 1) {
                    importMessage = "从 URL 导入：${imported.first().name}"
                } else {
                    importMessage = "从 URL 批量导入 ${imported.size} 个书源"
                }
                refreshSources()
            }.onFailure {
                importMessage = "URL 导入失败：${it.message ?: "请检查链接和网络"}"
            }
        }
    }

    fun convertLegacyRule() {
        if (legacyRuleText.isBlank()) return
        runCatching { convertLegacyRuleToJsSource(legacyRuleText) }
            .onSuccess { result ->
                scriptText = result.script
                conversionWarnings = result.warnings
                importMessage = if (result.warnings.isEmpty()) {
                    "已生成 JS 草稿，可直接导入或继续修改。"
                } else {
                    "已生成 JS 草稿，存在 ${result.warnings.size} 条提示。"
                }
            }
            .onFailure {
                conversionWarnings = emptyList()
                importMessage = "转换失败：${it.message ?: "未知错误"}"
            }
    }

    fun checkHealth(sourceId: String) {
        if (sourceId.isBlank() || sourceId in checkingIds) return
        checkingIds.add(sourceId)
        healthResults.remove(sourceId)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                sourceRepository.checkSourceHealth(sourceId)
            }
            healthResults[sourceId] = result
            checkingIds.remove(sourceId)
        }
    }

    fun checkHealthAll() {
        filteredSources.forEach { source ->
            if (source.enabled && source.id !in checkingIds) {
                checkHealth(source.id)
            }
        }
    }

    fun updateSource(sourceId: String) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    sourceRepository.updateSource(sourceId)
                }
            }.onSuccess { updated ->
                importMessage = "已更新：${updated.name}"
                refreshSources()
            }.onFailure {
                importMessage = "更新失败：${it.message ?: "未知错误"}"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("书源管理")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${sources.size} 个书源",
                        color = Color(0xFF526079),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { checkHealthAll() }) {
                        Text("检测全部", fontSize = 12.sp)
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // ── Source filter ──
                OutlinedTextField(
                    value = sourceFilter,
                    onValueChange = { sourceFilter = it },
                    label = { Text("搜索书源") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // ── URL import ──
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("从 URL 导入书源") },
                    placeholder = { Text("粘贴书源仓库链接") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { importFromUrl() }) { Text("拉取") }
                    OutlinedButton(onClick = { urlText = "" }) { Text("清空") }
                }

                Divider()

                // ── Legacy rule → JS ──
                OutlinedTextField(
                    value = legacyRuleText,
                    onValueChange = { legacyRuleText = it },
                    label = { Text("旧规则 JSON / 文本") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { convertLegacyRule() }) { Text("转换为 JS") }
                    OutlinedButton(onClick = {
                        legacyRuleText = ""
                        conversionWarnings = emptyList()
                    }) { Text("清空旧规则") }
                }
                if (conversionWarnings.isNotEmpty()) {
                    SourceCard {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("转换提示", fontWeight = FontWeight.Black, color = Color(0xFF0E1726))
                            conversionWarnings.forEach { warning ->
                                Text(warning, color = Color(0xFF6B7890), fontSize = 12.sp)
                            }
                        }
                    }
                }

                // ── JS script import ──
                OutlinedTextField(
                    value = scriptText,
                    onValueChange = { scriptText = it },
                    label = { Text("JS 书源脚本 / 批量") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { importScript() }) { Text("导入") }
                    OutlinedButton(onClick = {
                        val target = selectedSourceId
                        if (target.isNotBlank()) {
                            sourceRepository.deleteSource(target)
                            refreshSources()
                        }
                    }) { Text("删除当前") }
                    OutlinedButton(onClick = {
                        scriptText = ""
                    }) { Text("清空") }
                }
                if (importMessage.isNotBlank()) {
                    Text(importMessage, color = Color(0xFF1677FF), fontSize = 12.sp)
                }

                Divider()

                // ── Search section ──
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("搜索关键字") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val sourceId = selectedSourceId
                            if (sourceId.isNotBlank() && searchQuery.isNotBlank()) {
                                isSearching = true
                                scope.launch {
                                    val results = try {
                                        withContext(Dispatchers.IO) {
                                            runCatching { sourceRepository.search(sourceId, searchQuery) }
                                                .getOrDefault(emptyList())
                                        }
                                    } finally {
                                        isSearching = false
                                    }
                                    searchResults = results
                                }
                            }
                        }, enabled = !isSearching) {
                            if (isSearching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White,
                                    strokeCap = StrokeCap.Round,
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("搜索")
                        }
                        OutlinedButton(onClick = {
                            selectedSourceId = sources.firstOrNull()?.id.orEmpty()
                        }) { Text("重置") }
                    }
                    TextButton(onClick = {
                        scope.launch {
                            val json = sourceRepository.exportSourcesAsJson()
                            scriptText = json
                            importMessage = "已导出 ${sources.size} 个书源 → 脚本输入框 (可复制分享)"
                        }
                    }) { Text("导出全部", fontSize = 12.sp) }
                }

                // ── Source list + search results ──
                LazyColumn(
                    modifier = Modifier.height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Source list
                    items(filteredSources, key = { it.id }) { source ->
                        val enabled = source.enabled
                        val selected = source.id == selectedSourceId
                        val checking = source.id in checkingIds
                        val health = healthResults[source.id]

                        SourceCard(selected = selected) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            source.name,
                                            fontWeight = FontWeight.Black,
                                            color = if (enabled) Color(0xFF0E1726) else Color(0xFF9CA3AF),
                                            fontSize = 15.sp,
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (source.group.isNotBlank()) {
                                                Text(
                                                    source.group,
                                                    color = Color(0xFF1677FF),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                                Text(" · ", color = Color(0xFFB0B8C8), fontSize = 11.sp)
                                            }
                                            Text(
                                                source.author.ifBlank { source.url.take(40) },
                                                color = Color(0xFF6B7890),
                                                fontSize = 11.sp,
                                            )
                                        }
                                    }
                                    // Health status indicator
                                    if (checking) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            strokeCap = StrokeCap.Round,
                                        )
                                    } else if (health != null) {
                                        val dotColor = if (health.ok) Color(0xFF52C41A) else Color(0xFFFF4D4F)
                                        Text("●", color = dotColor, fontSize = 14.sp)
                                    }
                                }

                                // Health message
                                health?.let { h ->
                                    if (h.message.isNotBlank()) {
                                        Text(
                                            h.message,
                                            color = if (h.ok) Color(0xFF52C41A) else Color(0xFFFF4D4F),
                                            fontSize = 11.sp,
                                        )
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedButton(
                                        onClick = { selectedSourceId = source.id },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    ) { Text("选中", fontSize = 11.sp) }

                                    OutlinedButton(
                                        onClick = {
                                            sourceRepository.setSourceEnabled(source.id, !enabled)
                                            refreshSources()
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            if (enabled) "停用" else "启用",
                                            fontSize = 11.sp,
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = { checkHealth(source.id) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    ) { Text("检测", fontSize = 11.sp) }

                                    if (source.updateUrl.isNotBlank()) {
                                        OutlinedButton(
                                            onClick = { updateSource(source.id) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        ) { Text("更新", fontSize = 11.sp) }
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            sourceRepository.deleteSource(source.id)
                                            healthResults.remove(source.id)
                                            refreshSources()
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = Color(0xFFFF4D4F),
                                        ),
                                    ) { Text("删除", fontSize = 11.sp) }
                                }
                            }
                        }
                    }

                    // Search results header
                    if (searchResults.isNotEmpty()) {
                        item {
                            Divider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = Color(0xFFE8ECF4),
                            )
                            Text(
                                "搜索结果 (${searchResults.size})",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF1677FF),
                            )
                        }
                    }
                    items(searchResults, key = { it.bookUrl.ifBlank { it.name } }) { book ->
                        SourceCard {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(book.name, fontWeight = FontWeight.Black, color = Color(0xFF0E1726))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (book.author.isNotBlank()) {
                                        Text(book.author, color = Color(0xFF6B7890), fontSize = 12.sp)
                                        Text(" · ", color = Color(0xFFB0B8C8), fontSize = 12.sp)
                                    }
                                    if (book.kind.isNotBlank()) {
                                        Text(book.kind, color = Color(0xFF8A96AA), fontSize = 12.sp)
                                        Text(" · ", color = Color(0xFFB0B8C8), fontSize = 12.sp)
                                    }
                                    if (book.wordCount.isNotBlank()) {
                                        Text("${book.wordCount}字", color = Color(0xFF8A96AA), fontSize = 12.sp)
                                    }
                                }
                                if (book.latestChapter.isNotBlank()) {
                                    Text("最新：${book.latestChapter}", color = Color(0xFF526079), fontSize = 12.sp)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        val sourceId = selectedSourceId
                                        if (sourceId.isNotBlank()) onImportBook(sourceId, book.bookUrl)
                                    }) { Text("导入到书架") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun SourceCard(
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val borderModifier = if (selected) {
        Modifier.border(2.dp, Color(0xFF1677FF), RoundedCornerShape(18.dp))
    } else {
        Modifier.border(1.dp, Color.White.copy(alpha = 0.86f), RoundedCornerShape(18.dp))
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .then(borderModifier)
            .background(Color.White.copy(alpha = 0.72f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}
