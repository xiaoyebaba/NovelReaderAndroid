package com.richardyap.novelreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.compose.BackHandler
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val incomingImportUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = NovelRepository(this)
        incomingImportUri.value = intent.importUri()

        setContent {
            NovelTheme {
                NovelReaderApp(
                    repository = repository,
                    incomingImportUri = incomingImportUri.value,
                    onIncomingImportConsumed = { incomingImportUri.value = null },
                    onOpenUrl = { url ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingImportUri.value = intent.importUri()
    }
}

private fun Intent.importUri(): Uri? = when (action) {
    Intent.ACTION_VIEW -> data
    Intent.ACTION_SEND -> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }
    else -> null
}

data class Book(
    val id: String,
    val title: String,
    val fileName: String,
    val chapterCount: Int,
    val currentChapter: Int,
    val paragraphIndex: Int,
    val currentChapterTitle: String = "",
    val createdAt: Long,
    val lastReadAt: Long,
)

data class ReaderPrefs(
    val fontSize: Int = 20,
    val lineHeight: Float = 1.75f,
    val theme: String = "paper",
    val pageMode: String = "scroll",
)

data class SyncPrefs(
    val webDavUrl: String = "",
    val username: String = "",
    val password: String = "",
    val remoteFile: String = "novelreader-backup.json",
    val updateManifestUrl: String = "",
    val githubOwner: String = DefaultGithubOwner,
    val githubRepo: String = DefaultGithubRepo,
    val githubAssetKeyword: String = ".apk",
)

data class Chapter(
    val title: String,
    val body: String,
)

data class SearchResult(
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val chapterTitle: String,
    val preview: String,
)

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
    val releaseUrl: String = "",
)

private data class HttpResponse(
    val code: Int,
    val text: String,
)

private val ChapterTitlePattern = Regex(
    pattern = """(?im)^[ \t　]*(第[零〇一二三四五六七八九十百千万两\d]+[章节卷回部][^\n]{0,36}|Chapter\s+\d+[^\n]{0,36}|\d{1,4}[、.．]\s*[^\n]{1,36})[ \t　]*$""",
)

private val SpecialChapterTitlePattern = Regex(
    pattern = """(?im)^[ \t　]*(序章|楔子|引子|前言|正文|尾声|后记|番外[^\n]{0,36}|Prologue|Epilogue)[ \t　]*$""",
)

private const val FallbackChapterSize = 18_000
private const val DefaultGithubOwner = "xiaoyebaba"
private const val DefaultGithubRepo = "NovelReaderAndroid"
private const val DefaultGithubAssetKeyword = ".apk"

@Composable
private fun NovelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFFE5484D),
            secondary = Color(0xFF2F3136),
            background = Color(0xFFF6F7F9),
            surface = Color(0xFFFFFFFF),
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = Color(0xFF16171A),
            onSurface = Color(0xFF16171A),
        ),
        content = content,
    )
}

@Composable
private fun NovelReaderApp(
    repository: NovelRepository,
    incomingImportUri: Uri?,
    onIncomingImportConsumed: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var books by remember { mutableStateOf(repository.loadBooks()) }
    var prefs by remember { mutableStateOf(repository.loadReaderPrefs()) }
    var syncPrefs by remember { mutableStateOf(repository.loadSyncPrefs()) }
    var activeBookId by remember { mutableStateOf<String?>(null) }
    var showCloudDialog by remember { mutableStateOf(false) }
    var pendingUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun replaceBook(updated: Book) {
        books = books
            .map { if (it.id == updated.id) updated else it }
            .sortedByDescending { it.lastReadAt }
        repository.saveBooks(books)
    }

    fun importUri(uri: Uri) {
        scope.launch {
            runCatching {
                val book = withContext(Dispatchers.IO) {
                    repository.importBook(uri)
                }
                val nextBooks = (listOf(book) + books).distinctBy { it.id }
                books = nextBooks
                withContext(Dispatchers.IO) {
                    repository.saveBooks(nextBooks)
                }
                activeBookId = book.id
                snackbars.showSnackbar("已导入《${book.title}》，识别到 ${book.chapterCount} 章")
            }.onFailure {
                snackbars.showSnackbar("导入失败：${it.message ?: "无法读取文件"}")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importUri(uri)
        return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val book = repository.importBook(uri)
                books = (listOf(book) + books).distinctBy { it.id }
                repository.saveBooks(books)
                activeBookId = book.id
                snackbars.showSnackbar("已导入《${book.title}》")
            }.onFailure {
                snackbars.showSnackbar("导入失败：${it.message ?: "无法读取文件"}")
            }
        }
    }

    LaunchedEffect(incomingImportUri) {
        val uri = incomingImportUri ?: return@LaunchedEffect
        importUri(uri)
        onIncomingImportConsumed()
    }

    val activeBook = books.firstOrNull { it.id == activeBookId }
    Scaffold(snackbarHost = { SnackbarHost(snackbars) }) { innerPadding ->
        if (activeBook == null) {
            BookshelfScreen(
                books = books,
                onImport = { importLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
                onCloud = { showCloudDialog = true },
                onOpen = { activeBookId = it.id },
                onDelete = { book ->
                    repository.deleteBook(book)
                    books = books.filterNot { it.id == book.id }
                    repository.saveBooks(books)
                },
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            ReaderScreen(
                repository = repository,
                book = activeBook,
                prefs = prefs,
                onBack = { activeBookId = null },
                onUpdateBook = ::replaceBook,
                onUpdatePrefs = {
                    prefs = it
                    repository.saveReaderPrefs(it)
                },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }

    if (showCloudDialog) {
        CloudSyncDialog(
            prefs = syncPrefs,
            onDismiss = { showCloudDialog = false },
            onSave = { next ->
                syncPrefs = next
                repository.saveSyncPrefs(next)
                scope.launch { snackbars.showSnackbar("云同步配置已保存") }
            },
            onUpload = { next ->
                syncPrefs = next
                repository.saveSyncPrefs(next)
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            repository.uploadBackup(next, books)
                        }
                    }.onSuccess {
                        snackbars.showSnackbar("已上传到 WebDAV")
                    }.onFailure {
                        snackbars.showSnackbar("上传失败：${it.message ?: "请检查 WebDAV 配置"}")
                    }
                }
            },
            onRestore = { next ->
                syncPrefs = next
                repository.saveSyncPrefs(next)
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            repository.restoreBackup(next)
                        }
                    }.onSuccess { restored ->
                        books = restored
                        activeBookId = null
                        snackbars.showSnackbar("已从 WebDAV 恢复 ${restored.size} 本书")
                    }.onFailure {
                        snackbars.showSnackbar("恢复失败：${it.message ?: "请检查远程备份"}")
                    }
                }
            },
            onCheckUpdate = { next ->
                syncPrefs = next
                repository.saveSyncPrefs(next)
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            repository.checkUpdate(next)
                        }
                    }.onSuccess { update ->
                        val hasNewerVersion = if (update.versionCode > 0) {
                            update.versionCode > BuildConfig.VERSION_CODE
                        } else {
                            isVersionNameNewer(update.versionName, BuildConfig.VERSION_NAME)
                        }
                        if (hasNewerVersion) {
                            pendingUpdate = update
                        } else {
                            snackbars.showSnackbar("当前已是最新版本")
                        }
                    }.onFailure {
                        snackbars.showSnackbar("检查更新失败：${it.message ?: "请检查更新地址"}")
                    }
                }
            },
        )
    }

    pendingUpdate?.let { update ->
        UpdateAvailableDialog(
            update = update,
            onDismiss = { pendingUpdate = null },
            onDownload = {
                val targetUrl = update.apkUrl.ifBlank { update.releaseUrl }
                pendingUpdate = null
                if (targetUrl.isNotBlank()) onOpenUrl(targetUrl)
            },
        )
    }
}

@Composable
private fun BookshelfScreen(
    books: List<Book>,
    onImport: () -> Unit,
    onCloud: () -> Unit,
    onOpen: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val filteredBooks = remember(books, query) {
        if (query.isBlank()) books else books.filter {
            it.title.contains(query.trim(), ignoreCase = true)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("竹简阅读", fontSize = 32.sp, fontWeight = FontWeight.Black)
                Text("本地 TXT 小说阅读器", color = Color(0xFF777A82), fontSize = 14.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCloud) {
                    Text("云同步")
                }
                Button(onClick = onImport) {
                    Text("导入")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索书名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        if (filteredBooks.isEmpty()) {
            EmptyShelf(onImport = onImport)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 136.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                gridItems(filteredBooks, key = { it.id }) { book ->
                    BookCard(book = book, onOpen = onOpen, onDelete = onDelete)
                }
            }
        }
    }
}

@Composable
private fun EmptyShelf(onImport: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("书架还是空的", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("导入一个 .txt 文件，就能开始阅读。", color = Color(0xFF777A82))
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onImport) {
                Text("选择本地小说")
            }
        }
    }
}

@Composable
private fun CloudSyncDialog(
    prefs: SyncPrefs,
    onDismiss: () -> Unit,
    onSave: (SyncPrefs) -> Unit,
    onUpload: (SyncPrefs) -> Unit,
    onRestore: (SyncPrefs) -> Unit,
    onCheckUpdate: (SyncPrefs) -> Unit,
) {
    var webDavUrl by remember(prefs) { mutableStateOf(prefs.webDavUrl) }
    var username by remember(prefs) { mutableStateOf(prefs.username) }
    var password by remember(prefs) { mutableStateOf(prefs.password) }
    var remoteFile by remember(prefs) { mutableStateOf(prefs.remoteFile) }
    var updateManifestUrl by remember(prefs) { mutableStateOf(prefs.updateManifestUrl) }

    fun nextPrefs(): SyncPrefs = SyncPrefs(
        webDavUrl = webDavUrl.trim(),
        username = username.trim(),
        password = password,
        remoteFile = remoteFile.trim().ifBlank { "novelreader-backup.json" },
        updateManifestUrl = updateManifestUrl.trim(),
        githubOwner = DefaultGithubOwner,
        githubRepo = DefaultGithubRepo,
        githubAssetKeyword = DefaultGithubAssetKeyword,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("云同步 / 更新") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text("WebDAV 同步", fontWeight = FontWeight.Bold)
                }
                item {
                    OutlinedTextField(
                        value = webDavUrl,
                        onValueChange = { webDavUrl = it },
                        label = { Text("WebDAV 目录地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("账号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码 / 应用密码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = remoteFile,
                        onValueChange = { remoteFile = it },
                        label = { Text("远程备份文件名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onUpload(nextPrefs()) }) {
                            Text("上传")
                        }
                        OutlinedButton(onClick = { onRestore(nextPrefs()) }) {
                            Text("恢复")
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("软件更新", fontWeight = FontWeight.Bold)
                }
                item {
                    Text(
                        text = "更新源：GitHub Releases / $DefaultGithubOwner/$DefaultGithubRepo",
                        color = Color(0xFF777A82),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
                item {
                    Text(
                        text = "用户无需填写配置。发布新版本时，只要在该仓库创建新的 Release 并上传 APK 即可。",
                        color = Color(0xFF777A82),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
                item {
                    OutlinedButton(onClick = { onCheckUpdate(nextPrefs()) }) {
                        Text("检查更新")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        confirmButton = {
            Button(onClick = { onSave(nextPrefs()) }) {
                Text("保存")
            }
        },
    )
}

@Composable
private fun UpdateAvailableDialog(
    update: UpdateInfo,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版 ${update.versionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = update.notes.ifBlank { "这个版本没有填写更新日志。" },
                    color = Color(0xFF3F4248),
                    lineHeight = 20.sp,
                )
                if (update.apkUrl.isNotBlank()) {
                    Text(
                        text = "下载链接已就绪，点击下方按钮会跳转浏览器下载 APK。",
                        color = Color(0xFF777A82),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后")
            }
        },
        confirmButton = {
            Button(onClick = onDownload) {
                Text("下载更新")
            }
        },
    )
}

@Composable
private fun BookCard(book: Book, onOpen: (Book) -> Unit, onDelete: (Book) -> Unit) {
    val progress = if (book.chapterCount <= 1) {
        0f
    } else {
        (book.currentChapter.toFloat() / (book.chapterCount - 1)).coerceIn(0f, 1f)
    }
    val readPercent = (((book.currentChapter + 1).toFloat() / book.chapterCount.coerceAtLeast(1)) * 100)
        .roundToInt()
        .coerceIn(0, 100)
    val currentTitle = book.currentChapterTitle.ifBlank { "第 ${book.currentChapter + 1} 章" }
    val coverColors = listOf(
        Color(0xFF202124),
        Color(0xFF28434D),
        Color(0xFF493B69),
        Color(0xFF77504A),
        Color(0xFF365B46),
        Color(0xFF5A4D3F),
    )
    val coverColor = coverColors[book.title.hashCode().ushr(1) % coverColors.size]

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(book) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.68f)
                    .background(coverColor, RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(6.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = book.title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 26.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "TXT",
                    color = Color(0xCCFFFFFF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(Color(0x22FFFFFF), RoundedCornerShape(4.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = book.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 21.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = currentTitle,
                color = Color(0xFFE5484D),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${book.chapterCount} 章 · ${formatTime(book.lastReadAt)}",
                color = Color(0xFF777A82),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(9.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFE5484D),
                trackColor = Color(0xFFE4E5E8),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$readPercent%",
                    color = Color(0xFFE5484D),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = { onDelete(book) }) {
                    Text("删除")
                }
            }
        }
    }
}

@Composable
private fun ReaderScreen(
    repository: NovelRepository,
    book: Book,
    prefs: ReaderPrefs,
    onBack: () -> Unit,
    onUpdateBook: (Book) -> Unit,
    onUpdatePrefs: (ReaderPrefs) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = remember(book.id) { repository.loadBookText(book) }
    val chapters = remember(text) { splitChapters(text) }
    var chapterIndex by remember(book.id) {
        mutableIntStateOf(book.currentChapter.coerceIn(0, chapters.lastIndex))
    }
    var showSettings by remember { mutableStateOf(false) }
    var showCatalog by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showChrome by remember { mutableStateOf(true) }
    var pendingScrollItem by remember { mutableStateOf<Int?>(null) }
    val chapter = chapters[chapterIndex]
    val paragraphs = remember(book.id, chapterIndex) { chapter.toParagraphs() }
    val pages = remember(book.id, chapterIndex, prefs.fontSize, prefs.pageMode) {
        chapter.toPages(charsPerPage = pageSizeFor(prefs.fontSize))
    }
    var pageIndex by remember(book.id, chapterIndex, prefs.pageMode) {
        mutableIntStateOf(0)
    }
    val initialParagraph = if (chapterIndex == book.currentChapter) {
        book.paragraphIndex.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
    } else {
        0
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialParagraph)
    val colors = readerColors(prefs.theme)

    BackHandler(enabled = !showSettings && !showCatalog && !showSearch) {
        onBack()
    }

    LaunchedEffect(showChrome, showSettings, showCatalog, showSearch) {
        if (showChrome && !showSettings && !showCatalog && !showSearch) {
            delay(3500)
            showChrome = false
        }
    }

    LaunchedEffect(chapterIndex, pendingScrollItem) {
        val target = pendingScrollItem
        if (target != null) {
            listState.scrollToItem(target)
            pendingScrollItem = null
        } else if (chapterIndex != book.currentChapter) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(book.id, chapterIndex, listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { paragraph ->
                onUpdateBook(
                    book.copy(
                        chapterCount = chapters.size,
                        currentChapter = chapterIndex,
                        paragraphIndex = paragraph,
                        currentChapterTitle = chapter.title,
                        lastReadAt = System.currentTimeMillis(),
                    ),
                )
            }
    }

    LaunchedEffect(book.id, chapterIndex, pageIndex, prefs.pageMode) {
        if (prefs.pageMode != "scroll") {
            onUpdateBook(
                book.copy(
                    chapterCount = chapters.size,
                    currentChapter = chapterIndex,
                    paragraphIndex = pageIndex,
                    currentChapterTitle = chapter.title,
                    lastReadAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        if (prefs.pageMode == "scroll") {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { showChrome = !showChrome })
                    }
                    .padding(horizontal = 22.dp),
            ) {
                item {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = chapter.title,
                        color = colors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 24.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(16.dp))
                }
                items(paragraphs) { paragraph ->
                    Text(
                        text = paragraph,
                        color = colors.text,
                        fontSize = prefs.fontSize.sp,
                        lineHeight = (prefs.fontSize * prefs.lineHeight).sp,
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                }
                item { Spacer(Modifier.height(126.dp)) }
            }
        } else {
            PagedReaderContent(
                title = chapter.title,
                pages = pages,
                pageIndex = pageIndex,
                prefs = prefs,
                colors = colors,
                modifier = Modifier.fillMaxSize(),
                showChapterTitle = false,
                onToggleChrome = { showChrome = !showChrome },
                onPreviousPage = {
                    if (pageIndex > 0) {
                        pageIndex -= 1
                    } else if (chapterIndex > 0) {
                        val nextChapter = chapterIndex - 1
                        chapterIndex = nextChapter
                        pendingScrollItem = 0
                        pageIndex = 0
                        onUpdateBook(
                            book.copy(
                                currentChapter = nextChapter,
                                paragraphIndex = 0,
                                currentChapterTitle = chapters[nextChapter].title,
                                lastReadAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
                onNextPage = {
                    if (pageIndex < pages.lastIndex) {
                        pageIndex += 1
                    } else if (chapterIndex < chapters.lastIndex) {
                        val nextChapter = chapterIndex + 1
                        chapterIndex = nextChapter
                        pendingScrollItem = 0
                        pageIndex = 0
                        onUpdateBook(
                            book.copy(
                                currentChapter = nextChapter,
                                paragraphIndex = 0,
                                currentChapterTitle = chapters[nextChapter].title,
                                lastReadAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
            )
        }
        AnimatedVisibility(
            visible = showChrome,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 2 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { -it / 2 },
        ) {
            ReaderTopBar(
                title = book.title,
                chapter = chapter.title,
                colors = colors,
                onBack = onBack,
                onCatalog = { showCatalog = true },
                onSearch = { showSearch = true },
                onSettings = { showSettings = true },
            )
        }
        AnimatedVisibility(
            visible = showChrome,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { it / 2 },
        ) {
            ReaderBottomBar(
                current = chapterIndex,
                total = chapters.size,
                onPrevious = {
                val nextChapter = (chapterIndex - 1).coerceAtLeast(0)
                chapterIndex = nextChapter
                pendingScrollItem = 0
                onUpdateBook(
                    book.copy(
                        currentChapter = nextChapter,
                        paragraphIndex = 0,
                        currentChapterTitle = chapters[nextChapter].title,
                        lastReadAt = System.currentTimeMillis(),
                    ),
                )
                },
                onNext = {
                val nextChapter = (chapterIndex + 1).coerceAtMost(chapters.lastIndex)
                chapterIndex = nextChapter
                pendingScrollItem = 0
                onUpdateBook(
                    book.copy(
                        currentChapter = nextChapter,
                        paragraphIndex = 0,
                        currentChapterTitle = chapters[nextChapter].title,
                        lastReadAt = System.currentTimeMillis(),
                    ),
                )
                },
                colors = colors,
                onCatalog = { showCatalog = true },
                onSearch = { showSearch = true },
                onSettings = { showSettings = true },
            )
        }
        AnimatedVisibility(
            visible = !showChrome,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
        ) {
            ReaderMiniStatusBar(
                chapter = chapter.title,
                current = chapterIndex,
                total = chapters.size,
                pageIndex = if (prefs.pageMode == "scroll") listState.firstVisibleItemIndex else pageIndex,
                pageTotal = if (prefs.pageMode == "scroll") paragraphs.size.coerceAtLeast(1) else pages.size.coerceAtLeast(1),
                colors = colors,
            )
        }
    }

    if (showSettings) {
        ReaderSettingsDialog(
            prefs = prefs,
            onDismiss = { showSettings = false },
            onChange = onUpdatePrefs,
        )
    }

    if (showCatalog) {
        CatalogDialog(
            chapters = chapters,
            currentChapter = chapterIndex,
            onDismiss = { showCatalog = false },
            onOpenChapter = { nextChapter ->
                chapterIndex = nextChapter
                pendingScrollItem = 0
                onUpdateBook(
                    book.copy(
                        currentChapter = nextChapter,
                        paragraphIndex = 0,
                        currentChapterTitle = chapters[nextChapter].title,
                        lastReadAt = System.currentTimeMillis(),
                    ),
                )
                showCatalog = false
            },
        )
    }

    if (showSearch) {
        SearchDialog(
            chapters = chapters,
            onDismiss = { showSearch = false },
            onOpenResult = { result ->
                chapterIndex = result.chapterIndex
                pendingScrollItem = result.paragraphIndex + 1
                onUpdateBook(
                    book.copy(
                        currentChapter = result.chapterIndex,
                        paragraphIndex = result.paragraphIndex + 1,
                        currentChapterTitle = result.chapterTitle,
                        lastReadAt = System.currentTimeMillis(),
                    ),
                )
                showSearch = false
            },
        )
    }
}

@Composable
private fun ReaderMiniStatusBar(
    chapter: String,
    current: Int,
    total: Int,
    pageIndex: Int,
    pageTotal: Int,
    colors: ReaderPalette,
) {
    val page = (pageIndex + 1).coerceIn(1, pageTotal.coerceAtLeast(1))
    val progress = ((page.toFloat() / pageTotal.coerceAtLeast(1)) * 100).roundToInt()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.chromeSoft),
    ) {
        Divider(color = colors.divider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = chapter,
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$page/$pageTotal  $progress%",
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun PagedReaderContent(
    title: String,
    pages: List<String>,
    pageIndex: Int,
    prefs: ReaderPrefs,
    colors: ReaderPalette,
    modifier: Modifier = Modifier,
    showChapterTitle: Boolean,
    onToggleChrome: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (!showChapterTitle) Modifier.statusBarsPadding() else Modifier)
            .pointerInput(pageIndex, pages.size) {
                detectTapGestures { offset ->
                    val third = size.width / 3f
                    when {
                        offset.x < third -> onPreviousPage()
                        offset.x > third * 2 -> onNextPage()
                        else -> onToggleChrome()
                    }
                }
            }
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Column(Modifier.padding(bottom = 58.dp)) {
            if (showChapterTitle) {
                Text(
                    text = title,
                    color = colors.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 28.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
            }
            if (prefs.pageMode == "instant") {
                Text(
                    text = pages.getOrElse(pageIndex) { "" },
                    color = colors.text,
                    fontSize = prefs.fontSize.sp,
                    lineHeight = (prefs.fontSize * prefs.lineHeight).sp,
                )
            } else {
                AnimatedContent(
                    targetState = pageIndex,
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(120)) },
                    label = "pageContent",
                ) { targetPage ->
                    Text(
                        text = pages.getOrElse(targetPage) { "" },
                        color = colors.text,
                        fontSize = prefs.fontSize.sp,
                        lineHeight = (prefs.fontSize * prefs.lineHeight).sp,
                    )
                }
            }
        }
        if (showChapterTitle) {
            Text(
                text = "${pageIndex + 1} / ${pages.size.coerceAtLeast(1)}",
                color = colors.secondaryText,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    chapter: String,
    colors: ReaderPalette,
    onBack: () -> Unit,
    onCatalog: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.toolbar)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) {
            Text("书架", color = colors.toolbarText)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold,
                color = colors.toolbarText,
            )
            Text(
                text = chapter,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = colors.toolbarText.copy(alpha = 0.72f),
                fontSize = 12.sp,
            )
        }
        TextButton(onClick = onCatalog) {
            Text("目录", color = colors.toolbarText)
        }
        TextButton(onClick = onSearch) {
            Text("搜索", color = colors.toolbarText)
        }
        TextButton(onClick = onSettings) {
            Text("设置", color = colors.toolbarText)
        }
    }
}

@Composable
private fun CatalogDialog(
    chapters: List<Chapter>,
    currentChapter: Int,
    onDismiss: () -> Unit,
    onOpenChapter: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("目录") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
            ) {
                itemsIndexed(chapters) { index, chapter ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenChapter(index) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            text = chapter.title,
                            fontWeight = if (index == currentChapter) FontWeight.Black else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "第 ${index + 1} 章",
                            color = Color(0xFF6B7280),
                            fontSize = 12.sp,
                        )
                    }
                    if (index != chapters.lastIndex) {
                        Divider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun SearchDialog(
    chapters: List<Chapter>,
    onDismiss: () -> Unit,
    onOpenResult: (SearchResult) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(chapters, query) {
        if (query.isBlank()) emptyList() else searchChapters(chapters, query.trim())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("书内搜索") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("输入关键词") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (query.isBlank()) "搜索章节标题和正文段落" else "找到 ${results.size} 条结果",
                    color = Color(0xFF6B7280),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(results) { result ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenResult(result) }
                                .background(Color(0xFFFFFFFF), RoundedCornerShape(14.dp))
                                .padding(12.dp),
                        ) {
                            Text(
                                text = result.chapterTitle,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = result.preview,
                                color = Color(0xFF4B5563),
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun ReaderBottomBar(
    current: Int,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    colors: ReaderPalette,
    onCatalog: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val progress = ((current + 1).toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.chrome)
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TextButton(onClick = onPrevious, enabled = current > 0) {
                Text("上一章")
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.weight(1f),
                color = colors.accent,
                trackColor = colors.divider,
            )
            TextButton(onClick = onNext, enabled = current < total - 1) {
                Text("下一章")
            }
        }
        Text(
            text = "${current + 1} / $total",
            color = colors.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = onCatalog) {
                Text("目录", color = colors.secondaryText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onSearch) {
                Text("搜索", color = colors.secondaryText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onSettings) {
                Text("界面", color = colors.secondaryText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ReaderSettingsDialog(
    prefs: ReaderPrefs,
    onDismiss: () -> Unit,
    onChange: (ReaderPrefs) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("阅读设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("字号：${prefs.fontSize}")
                Slider(
                    value = prefs.fontSize.toFloat(),
                    onValueChange = { onChange(prefs.copy(fontSize = it.roundToInt())) },
                    valueRange = 16f..32f,
                    steps = 15,
                )
                Text("行距：${String.format(Locale.CHINA, "%.1f", prefs.lineHeight)}")
                Slider(
                    value = prefs.lineHeight,
                    onValueChange = { onChange(prefs.copy(lineHeight = it)) },
                    valueRange = 1.3f..2.2f,
                    steps = 8,
                )
                Text("翻页方式")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeButton("滚动", prefs.pageMode == "scroll") { onChange(prefs.copy(pageMode = "scroll")) }
                    ThemeButton("左右", prefs.pageMode == "page") { onChange(prefs.copy(pageMode = "page")) }
                    ThemeButton("无动画", prefs.pageMode == "instant") { onChange(prefs.copy(pageMode = "instant")) }
                }
                Text("背景")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeButton("纸色", prefs.theme == "paper") { onChange(prefs.copy(theme = "paper")) }
                    ThemeButton("青绿", prefs.theme == "green") { onChange(prefs.copy(theme = "green")) }
                    ThemeButton("夜间", prefs.theme == "dark") { onChange(prefs.copy(theme = "dark")) }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("完成")
            }
        },
    )
}

@Composable
private fun ThemeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

private data class ReaderPalette(
    val background: Color,
    val toolbar: Color,
    val toolbarText: Color,
    val chrome: Color,
    val chromeSoft: Color,
    val text: Color,
    val secondaryText: Color,
    val divider: Color,
    val accent: Color,
)

private fun readerColors(theme: String): ReaderPalette = when (theme) {
    "dark" -> ReaderPalette(
        background = Color(0xFF101114),
        toolbar = Color(0xFF202124),
        toolbarText = Color(0xFFF4F4F5),
        chrome = Color(0xF0222328),
        chromeSoft = Color(0xFF101114),
        text = Color(0xFFEDEEF2),
        secondaryText = Color(0xFFA7AAB2),
        divider = Color(0xFF30323A),
        accent = Color(0xFFFF6B6B),
    )
    "green" -> ReaderPalette(
        background = Color(0xFFF5F7F4),
        toolbar = Color(0xFF496A58),
        toolbarText = Color.White,
        chrome = Color(0xFFF0F2EF),
        chromeSoft = Color(0xFFFFFFFF),
        text = Color(0xFF161A18),
        secondaryText = Color(0xFF686E69),
        divider = Color(0xFFE0E5DF),
        accent = Color(0xFFE5484D),
    )
    else -> ReaderPalette(
        background = Color(0xFFFFFFFF),
        toolbar = Color(0xFF826052),
        toolbarText = Color.White,
        chrome = Color(0xFFF1F1F3),
        chromeSoft = Color(0xFFFFFFFF),
        text = Color(0xFF060607),
        secondaryText = Color(0xFF70737A),
        divider = Color(0xFFE1E2E5),
        accent = Color(0xFFE5484D),
    )
}

class NovelRepository(private val context: Context) {
    private val libraryPrefs = context.getSharedPreferences("novel_library", Context.MODE_PRIVATE)
    private val readerPrefs = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
    private val syncPrefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
    private val booksDir: File
        get() = File(context.filesDir, "books").also { it.mkdirs() }

    fun importBook(uri: Uri): Book {
        val displayName = getDisplayName(uri).ifBlank { "未命名小说.txt" }
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("文件为空")
        val text = decodeText(bytes).trim()
        if (text.isBlank()) error("没有读到正文内容")

        val id = UUID.randomUUID().toString()
        val title = displayName.substringBeforeLast(".").ifBlank { "未命名小说" }
        val chapters = splitChapters(text)
        File(booksDir, "$id.txt").writeText(text, Charsets.UTF_8)

        return Book(
            id = id,
            title = title,
            fileName = "$id.txt",
            chapterCount = chapters.size,
            currentChapter = 0,
            paragraphIndex = 0,
            currentChapterTitle = chapters.firstOrNull()?.title.orEmpty(),
            createdAt = System.currentTimeMillis(),
            lastReadAt = System.currentTimeMillis(),
        )
    }

    fun loadBookText(book: Book): String = File(booksDir, book.fileName)
        .takeIf { it.exists() }
        ?.readText(Charsets.UTF_8)
        .orEmpty()

    fun deleteBook(book: Book) {
        File(booksDir, book.fileName).delete()
    }

    fun loadBooks(): List<Book> {
        val raw = libraryPrefs.getString("books", "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.getJSONObject(index).toBook() }
        }.getOrDefault(emptyList()).sortedByDescending { it.lastReadAt }
    }

    fun saveBooks(books: List<Book>) {
        val array = JSONArray()
        books.forEach { array.put(it.toJson()) }
        libraryPrefs.edit().putString("books", array.toString()).apply()
    }

    fun loadReaderPrefs(): ReaderPrefs = ReaderPrefs(
        fontSize = readerPrefs.getInt("font_size", 20),
        lineHeight = readerPrefs.getFloat("line_height", 1.75f),
        theme = readerPrefs.getString("theme", "paper") ?: "paper",
        pageMode = readerPrefs.getString("page_mode", "scroll") ?: "scroll",
    )

    fun saveReaderPrefs(prefs: ReaderPrefs) {
        readerPrefs.edit()
            .putInt("font_size", prefs.fontSize)
            .putFloat("line_height", prefs.lineHeight)
            .putString("theme", prefs.theme)
            .putString("page_mode", prefs.pageMode)
            .apply()
    }

    fun loadSyncPrefs(): SyncPrefs {
        val owner = syncPrefs.getString("github_owner", DefaultGithubOwner).orEmpty()
        val repo = syncPrefs.getString("github_repo", DefaultGithubRepo).orEmpty()
        val assetKeyword = syncPrefs.getString("github_asset_keyword", DefaultGithubAssetKeyword).orEmpty()
        return SyncPrefs(
            webDavUrl = syncPrefs.getString("webdav_url", "") ?: "",
            username = syncPrefs.getString("username", "") ?: "",
            password = syncPrefs.getString("password", "") ?: "",
            remoteFile = syncPrefs.getString("remote_file", "novelreader-backup.json") ?: "novelreader-backup.json",
            updateManifestUrl = syncPrefs.getString("update_manifest_url", "") ?: "",
            githubOwner = owner.ifBlank { DefaultGithubOwner },
            githubRepo = repo.ifBlank { DefaultGithubRepo },
            githubAssetKeyword = assetKeyword.ifBlank { DefaultGithubAssetKeyword },
        )
    }

    fun saveSyncPrefs(prefs: SyncPrefs) {
        syncPrefs.edit()
            .putString("webdav_url", prefs.webDavUrl)
            .putString("username", prefs.username)
            .putString("password", prefs.password)
            .putString("remote_file", prefs.remoteFile)
            .putString("update_manifest_url", prefs.updateManifestUrl)
            .putString("github_owner", prefs.githubOwner)
            .putString("github_repo", prefs.githubRepo)
            .putString("github_asset_keyword", prefs.githubAssetKeyword)
            .apply()
    }

    fun uploadBackup(prefs: SyncPrefs, books: List<Book>) {
        val url = prefs.backupUrl()
        val payload = exportBackupJson(books).toString().toByteArray(Charsets.UTF_8)
        val response = request(
            url = url,
            method = "PUT",
            prefs = prefs,
            body = payload,
            contentType = "application/json; charset=utf-8",
        )
        if (response.code !in 200..299) {
            error("服务器返回 ${response.code}")
        }
    }

    fun restoreBackup(prefs: SyncPrefs): List<Book> {
        val response = request(url = prefs.backupUrl(), method = "GET", prefs = prefs)
        if (response.code !in 200..299) {
            error("服务器返回 ${response.code}")
        }

        val root = JSONObject(response.text)
        val restoredBooks = mutableListOf<Book>()
        val remoteBooks = root.optJSONArray("books") ?: JSONArray()
        booksDir.mkdirs()
        booksDir.listFiles()?.forEach { it.delete() }

        for (index in 0 until remoteBooks.length()) {
            val item = remoteBooks.getJSONObject(index)
            val book = item.getJSONObject("meta").toBook()
            val encodedText = item.optString("textBase64", "")
            val text = if (encodedText.isBlank()) "" else String(Base64.decode(encodedText, Base64.NO_WRAP), Charsets.UTF_8)
            File(booksDir, book.fileName).writeText(text, Charsets.UTF_8)
            restoredBooks += book
        }

        root.optJSONObject("readerPrefs")?.let { remotePrefs ->
            saveReaderPrefs(
                ReaderPrefs(
                    fontSize = remotePrefs.optInt("fontSize", 20),
                    lineHeight = remotePrefs.optDouble("lineHeight", 1.75).toFloat(),
                    theme = remotePrefs.optString("theme", "paper"),
                    pageMode = remotePrefs.optString("pageMode", "scroll"),
                ),
            )
        }
        saveBooks(restoredBooks)
        return restoredBooks.sortedByDescending { it.lastReadAt }
    }

    fun checkUpdate(prefs: SyncPrefs): UpdateInfo {
        if (prefs.githubOwner.isNotBlank() && prefs.githubRepo.isNotBlank()) {
            return checkGitHubRelease(prefs)
        }
        val url = prefs.updateManifestUrl.ifBlank { error("请先填写更新清单 URL") }
        val response = request(url = url, method = "GET", prefs = prefs.copy(webDavUrl = url))
        if (response.code !in 200..299) {
            error("服务器返回 ${response.code}")
        }
        val json = JSONObject(response.text)
        return UpdateInfo(
            versionCode = json.optInt("versionCode", 0),
            versionName = json.optString("versionName", ""),
            apkUrl = json.optString("apkUrl", ""),
            notes = json.optString("notes", ""),
            releaseUrl = json.optString("releaseUrl", ""),
        )
    }

    private fun checkGitHubRelease(prefs: SyncPrefs): UpdateInfo {
        val owner = prefs.githubOwner.trim()
        val repo = prefs.githubRepo.trim()
        val response = request(
            url = "https://api.github.com/repos/$owner/$repo/releases/latest",
            method = "GET",
            prefs = SyncPrefs(),
        )
        if (response.code !in 200..299) {
            error("GitHub 返回 ${response.code}")
        }

        val json = JSONObject(response.text)
        val tag = json.optString("tag_name", json.optString("name", ""))
        val releaseUrl = json.optString("html_url", "")
        val keyword = prefs.githubAssetKeyword.trim().ifBlank { ".apk" }
        val assets = json.optJSONArray("assets") ?: JSONArray()
        var apkUrl = ""

        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.optString("name", "")
            val downloadUrl = asset.optString("browser_download_url", "")
            if (downloadUrl.isNotBlank() && name.contains(keyword, ignoreCase = true)) {
                apkUrl = downloadUrl
                break
            }
        }
        if (apkUrl.isBlank()) {
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.optString("name", "")
                val downloadUrl = asset.optString("browser_download_url", "")
                if (downloadUrl.isNotBlank() && name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = downloadUrl
                    break
                }
            }
        }

        return UpdateInfo(
            versionCode = 0,
            versionName = tag.removePrefix("v").ifBlank { tag },
            apkUrl = apkUrl,
            notes = json.optString("body", ""),
            releaseUrl = releaseUrl,
        )
    }

    private fun exportBackupJson(books: List<Book>): JSONObject {
        val bookArray = JSONArray()
        books.forEach { book ->
            val text = loadBookText(book)
            bookArray.put(
                JSONObject()
                    .put("meta", book.toJson())
                    .put("textBase64", Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)),
            )
        }
        val prefs = loadReaderPrefs()
        return JSONObject()
            .put("type", "NovelReaderBackup")
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put(
                "readerPrefs",
                JSONObject()
                    .put("fontSize", prefs.fontSize)
                    .put("lineHeight", prefs.lineHeight)
                    .put("theme", prefs.theme)
                    .put("pageMode", prefs.pageMode),
            )
            .put("books", bookArray)
    }

    private fun request(
        url: String,
        method: String,
        prefs: SyncPrefs,
        body: ByteArray? = null,
        contentType: String? = null,
    ): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json, application/json, */*")
            setRequestProperty("User-Agent", "NovelReaderAndroid/${BuildConfig.VERSION_NAME}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (prefs.username.isNotBlank() || prefs.password.isNotBlank()) {
                val token = Base64.encodeToString("${prefs.username}:${prefs.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $token")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
                setRequestProperty("Content-Length", body.size.toString())
            }
        }
        body?.let { bytes ->
            connection.outputStream.use { it.write(bytes) }
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        connection.disconnect()
        return HttpResponse(code, text)
    }

    private fun SyncPrefs.backupUrl(): String {
        val base = webDavUrl.trim().trimEnd('/').ifBlank { error("请先填写 WebDAV 地址") }
        val file = remoteFile.trim().ifBlank { "novelreader-backup.json" }.trimStart('/')
        return "$base/$file"
    }

    private fun getDisplayName(uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return ""
        return cursor.use {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && it.moveToFirst()) it.getString(index).orEmpty() else ""
        }
    }
}

private fun JSONObject.toBook(): Book = Book(
    id = getString("id"),
    title = getString("title"),
    fileName = getString("fileName"),
    chapterCount = optInt("chapterCount", 1),
    currentChapter = optInt("currentChapter", 0),
    paragraphIndex = optInt("paragraphIndex", 0),
    currentChapterTitle = optString("currentChapterTitle", ""),
    createdAt = optLong("createdAt", System.currentTimeMillis()),
    lastReadAt = optLong("lastReadAt", System.currentTimeMillis()),
)

private fun Book.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("fileName", fileName)
    .put("chapterCount", chapterCount)
    .put("currentChapter", currentChapter)
    .put("paragraphIndex", paragraphIndex)
    .put("currentChapterTitle", currentChapterTitle)
    .put("createdAt", createdAt)
    .put("lastReadAt", lastReadAt)

private fun splitChapters(raw: String): List<Chapter> {
    val text = raw.replace("\r\n", "\n").replace('\r', '\n')
    val matches = (ChapterTitlePattern.findAll(text) + SpecialChapterTitlePattern.findAll(text))
        .distinctBy { it.range.first }
        .sortedBy { it.range.first }
        .toList()
    if (matches.isEmpty()) return splitPlainTextIntoChapters(text)

    val chapters = mutableListOf<Chapter>()
    val firstStart = matches.first().range.first
    if (firstStart > 0) {
        val preface = text.substring(0, firstStart).trim()
        if (preface.isNotBlank()) chapters += Chapter("序章", preface)
    }

    matches.forEachIndexed { index, match ->
        val title = match.value.trim()
        val bodyStart = match.range.last + 1
        val bodyEnd = matches.getOrNull(index + 1)?.range?.first ?: text.length
        val body = text.substring(bodyStart, bodyEnd).trim()
        chapters += Chapter(title, body.ifBlank { title })
    }
    return chapters.ifEmpty { splitPlainTextIntoChapters(text) }
}

private fun splitPlainTextIntoChapters(text: String): List<Chapter> {
    val trimmed = text.trim()
    if (trimmed.length <= FallbackChapterSize) return listOf(Chapter("正文", trimmed))

    val chapters = mutableListOf<Chapter>()
    val buffer = StringBuilder()
    var chapterIndex = 1
    trimmed.lines().forEach { line ->
        if (buffer.length + line.length > FallbackChapterSize && buffer.isNotBlank()) {
            chapters += Chapter("第 ${chapterIndex++} 段", buffer.toString().trim())
            buffer.clear()
        }
        buffer.appendLine(line)
    }
    if (buffer.isNotBlank()) {
        chapters += Chapter("第 $chapterIndex 段", buffer.toString().trim())
    }
    return chapters.ifEmpty { listOf(Chapter("正文", trimmed)) }
}

private fun Chapter.toParagraphs(): List<String> {
    val lines = body
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (lines.size > 1) return lines
    return body.chunked(420).map { it.trim() }.filter { it.isNotBlank() }
}

private fun Chapter.toPages(charsPerPage: Int): List<String> {
    val normalized = body
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
        .ifBlank { title }
    return normalized.chunked(charsPerPage).map { it.trim() }.filter { it.isNotBlank() }
        .ifEmpty { listOf(title) }
}

private fun pageSizeFor(fontSize: Int): Int = when {
    fontSize >= 28 -> 260
    fontSize >= 24 -> 340
    fontSize >= 20 -> 460
    else -> 580
}

private fun searchChapters(chapters: List<Chapter>, query: String): List<SearchResult> {
    if (query.isBlank()) return emptyList()
    val results = mutableListOf<SearchResult>()
    chapters.forEachIndexed { chapterIndex, chapter ->
        if (chapter.title.contains(query, ignoreCase = true)) {
            results += SearchResult(
                chapterIndex = chapterIndex,
                paragraphIndex = 0,
                chapterTitle = chapter.title,
                preview = chapter.title,
            )
        }

        chapter.toParagraphs().forEachIndexed { paragraphIndex, paragraph ->
            if (paragraph.contains(query, ignoreCase = true)) {
                results += SearchResult(
                    chapterIndex = chapterIndex,
                    paragraphIndex = paragraphIndex,
                    chapterTitle = chapter.title,
                    preview = paragraph.previewAround(query),
                )
            }
        }
    }
    return results.take(80)
}

private fun String.previewAround(query: String): String {
    val normalized = replace(Regex("\\s+"), " ").trim()
    val index = normalized.lowercase(Locale.CHINA).indexOf(query.lowercase(Locale.CHINA))
    if (index < 0) return normalized.take(120)

    val start = (index - 36).coerceAtLeast(0)
    val end = (index + query.length + 84).coerceAtMost(normalized.length)
    val prefix = if (start > 0) "..." else ""
    val suffix = if (end < normalized.length) "..." else ""
    return prefix + normalized.substring(start, end) + suffix
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
            // Try the next common Chinese TXT encoding.
        }
    }
    return String(bytes, Charsets.UTF_8).stripBom()
}

private fun String.stripBom(): String = removePrefix("\uFEFF")

private fun isVersionNameNewer(remote: String, local: String): Boolean {
    val remoteParts = remote.versionNumbers()
    val localParts = local.versionNumbers()
    val count = maxOf(remoteParts.size, localParts.size)
    for (index in 0 until count) {
        val remoteValue = remoteParts.getOrElse(index) { 0 }
        val localValue = localParts.getOrElse(index) { 0 }
        if (remoteValue != localValue) return remoteValue > localValue
    }
    return false
}

private fun String.versionNumbers(): List<Int> = trim()
    .removePrefix("v")
    .split('.', '-', '_')
    .mapNotNull { it.toIntOrNull() }
    .ifEmpty { listOf(0) }

private fun formatTime(value: Long): String {
    val format = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    return format.format(Date(value))
}
