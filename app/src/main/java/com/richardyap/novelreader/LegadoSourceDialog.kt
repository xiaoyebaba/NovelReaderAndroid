package com.richardyap.novelreader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    var importMessage by remember { mutableStateOf("") }
    var conversionWarnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<LegadoBookItem>>(emptyList()) }
    var selectedSourceId by remember(sources) { mutableStateOf(sources.firstOrNull()?.id.orEmpty()) }
    val scope = rememberCoroutineScope()

    fun refreshSources() {
        onChanged()
        if (selectedSourceId.isBlank()) {
            selectedSourceId = sourceRepository.loadSources().firstOrNull()?.id.orEmpty()
        }
    }

    fun importScript() {
        if (scriptText.isBlank()) return
        runCatching {
            sourceRepository.importSourceText(scriptText)
        }.onSuccess {
            scriptText = ""
            importMessage = "已导入：${it.name}"
            refreshSources()
        }.onFailure {
            importMessage = "导入失败：${it.message ?: "未知错误"}"
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Legado 书源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "书源数量：${sources.size}",
                    color = Color(0xFF526079),
                    fontSize = 12.sp,
                )
                OutlinedTextField(
                    value = legacyRuleText,
                    onValueChange = { legacyRuleText = it },
                    label = { Text("旧规则 JSON / 文本") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
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
                OutlinedTextField(
                    value = scriptText,
                    onValueChange = { scriptText = it },
                    label = { Text("JS 书源脚本") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
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
                }
                if (importMessage.isNotBlank()) {
                    Text(importMessage, color = Color(0xFF1677FF), fontSize = 12.sp)
                }
                Divider()
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("搜索关键字") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val sourceId = selectedSourceId
                        if (sourceId.isNotBlank() && searchQuery.isNotBlank()) {
                            scope.launch {
                                val results = withContext(Dispatchers.IO) {
                                    runCatching { sourceRepository.search(sourceId, searchQuery) }
                                        .getOrDefault(emptyList())
                                }
                                searchResults = results
                            }
                        }
                    }) { Text("搜索") }
                    OutlinedButton(onClick = {
                        selectedSourceId = sources.firstOrNull()?.id.orEmpty()
                    }) { Text("重置") }
                }
                LazyColumn(
                    modifier = Modifier.height(260.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sources, key = { it.id }) { source ->
                        val enabled = source.enabled
                        SourceCard {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(source.name, fontWeight = FontWeight.Black, color = Color(0xFF0E1726))
                                Text(source.author.ifBlank { source.url }, color = Color(0xFF6B7890), fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { selectedSourceId = source.id }) { Text("选中") }
                                    OutlinedButton(onClick = {
                                        sourceRepository.setSourceEnabled(source.id, !enabled)
                                        refreshSources()
                                    }) { Text(if (enabled) "停用" else "启用") }
                                    OutlinedButton(onClick = {
                                        sourceRepository.deleteSource(source.id)
                                        refreshSources()
                                    }) { Text("删除") }
                                }
                            }
                        }
                    }
                    items(searchResults, key = { it.bookUrl }) { book ->
                        SourceCard {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(book.name, fontWeight = FontWeight.Black, color = Color(0xFF0E1726))
                                Text(book.author.ifBlank { book.bookUrl }, color = Color(0xFF6B7890), fontSize = 12.sp)
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
private fun SourceCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.72f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}
