package com.juliano.limpacel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private val Navy = Color(0xFF0B1422)
private val Surface = Color(0xFF182A3F)
private val Muted = Color(0xFFA6BED3)
private val Mint = Color(0xFF37DABA)
private val Amber = Color(0xFFFFCF83)

private enum class MainTab(val title: String, val icon: String) {
    HOME("Início", "⌂"), FILES("Arquivos", "▤"), HEALTH("Saúde", "♥"), TRASH("Lixeira", "▣")
}

internal enum class FileCategory(val title: String, val kind: FileKind? = null) {
    LARGE("Grandes"), DUPLICATES("Duplicados"), TEMPORARY("Temporários"),
    HIDDEN("Ocultos"), THUMBNAILS("Miniaturas"), APP_MEDIA("Mídias de apps"), OUTSIDE_INDEX("Fora da galeria"),
    PHOTOS("Fotos", FileKind.PHOTO), CAPTURES("Capturas", FileKind.SCREENSHOT),
    VIDEOS("Vídeos", FileKind.VIDEO), SCREEN_RECORDS("Gravações", FileKind.SCREEN_RECORDING),
    MUSIC("Músicas", FileKind.MUSIC), AUDIOS("Áudios", FileKind.AUDIO),
    PDF("PDFs", FileKind.PDF), DOCUMENT("Documentos", FileKind.DOCUMENT),
    ARCHIVES("Compactados", FileKind.ARCHIVE), APK("APKs", FileKind.APK),
    OTHER("Outros", FileKind.OTHER), ALL("Todos")
}

class SmartCleanActivity : ComponentActivity() {
    private var broadAccess by mutableStateOf(false)
    private var guidance: Pair<String, DeviceHealth>? = null

    override fun onResume() {
        super.onResume()
        broadAccess = Environment.isExternalStorageManager()
        guidance?.let { (name, before) ->
            guidance = null
            val diagnostics = DeviceDiagnostics(applicationContext)
            val after = diagnostics.read()
            val free = MegaScanner(applicationContext).usage().free
            HistoryRepository(applicationContext).add(name, emptyList(), free, free,
                before.batteryCelsius, after.batteryCelsius, before.availableRamBytes, after.availableRamBytes)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(11, 20, 34)
        window.navigationBarColor = android.graphics.Color.rgb(11, 20, 34)
        broadAccess = Environment.isExternalStorageManager()
        setContent {
            val ctx = applicationContext
            val scanner = remember { MegaScanner(ctx) }
            val trash = remember { TrashManager(ctx) }
            val settings = remember { ScanSettings(ctx) }
            val history = remember { HistoryRepository(ctx) }
            val diagnostics = remember { DeviceDiagnostics(ctx) }
            val scope = rememberCoroutineScope()
            val scan by ScanRuntime.state.collectAsState()
            var tab by remember { mutableStateOf(MainTab.HOME) }
            var category by remember { mutableStateOf(FileCategory.LARGE) }
            var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
            var selectedTrash by remember { mutableStateOf<Set<String>>(emptySet()) }
            var usage by remember { mutableStateOf(scanner.usage()) }
            var health by remember { mutableStateOf<DeviceHealth?>(null) }
            var trashItems by remember { mutableStateOf<List<TrashItem>>(emptyList()) }
            var historyItems by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
            var historyLoading by remember { mutableStateOf(false) }
            var trashLoaded by remember { mutableStateOf(false) }
            var excluded by remember { mutableStateOf(settings.excludedFolders()) }
            var largeMb by remember { mutableIntStateOf(settings.largeFileMb) }
            var deep by remember { mutableStateOf(settings.deepHashing) }
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var confirm by remember { mutableStateOf<String?>(null) }
            var doubleConfirm by remember { mutableStateOf(false) }
            var showHistory by remember { mutableStateOf(false) }
            var showSettings by remember { mutableStateOf(false) }

            fun loadHistory() {
                scope.launch {
                    historyLoading = true
                    try { historyItems = withContext(Dispatchers.IO) { history.all() } }
                    finally { historyLoading = false }
                }
            }

            fun refresh() {
                val includeHistory = showHistory
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val latestUsage = scanner.usage()
                        val latestHealth = diagnostics.read()
                        val latestTrash = trash.items()
                        val latestHistory = if (includeHistory) history.all() else null
                        withContext(Dispatchers.Main) {
                            usage = latestUsage
                            health = latestHealth
                            trashItems = latestTrash
                            trashLoaded = true
                            if (latestHistory != null) historyItems = latestHistory
                            excluded = settings.excludedFolders()
                        }
                    }
                }
            }

            fun scanNow() {
                if (!broadAccess || scan.running || busy) return
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
                }
                selected = emptySet()
                error = null
                try {
                    startForegroundService(Intent(this@SmartCleanActivity, ScanService::class.java)
                        .setAction(ScanService.START))
                } catch (e: Exception) {
                    error = "Não foi possível iniciar o Mega Scan: ${e.message.orEmpty()}"
                }
            }

            fun openGuidance(name: String, action: String) {
                val before = health ?: return
                guidance = name to before
                try { startActivity(Intent(action)) }
                catch (_: Exception) {
                    guidance = null
                    Toast.makeText(this@SmartCleanActivity, "Configuração indisponível neste aparelho", Toast.LENGTH_LONG).show()
                }
            }

            fun execute(action: String) {
                val fileSnapshots = scan.report?.files.orEmpty().filter { it.path in selected }
                val trashIds = selectedTrash.toSet()
                if ((action == "mover" || action == "excluir") && fileSnapshots.isEmpty()) return
                if ((action == "restaurar" || action == "esvaziar") && trashIds.isEmpty()) return
                confirm = null
                doubleConfirm = false
                scope.launch {
                    busy = true
                    error = null
                    try {
                        val outcome = withContext(Dispatchers.IO) {
                            val beforeStorage = scanner.usage().free
                            val beforeHealth = diagnostics.read()
                            val results = when (action) {
                                "mover" -> trash.move(fileSnapshots).outcomes
                                "excluir" -> scanner.delete(fileSnapshots).outcomes
                                "restaurar" -> trash.restore(trashIds).outcomes
                                else -> trash.permanentlyDelete(trashIds).outcomes
                            }
                            val afterStorage = scanner.usage().free
                            val afterHealth = diagnostics.read()
                            val name = when (action) {
                                "mover" -> "Mover para lixeira"
                                "excluir" -> "Exclusão definitiva"
                                "restaurar" -> "Restaurar arquivos"
                                else -> "Excluir da lixeira"
                            }
                            history.add(name, results, beforeStorage, afterStorage,
                                beforeHealth.batteryCelsius, afterHealth.batteryCelsius,
                                beforeHealth.availableRamBytes, afterHealth.availableRamBytes)
                            results
                        }
                        Toast.makeText(this@SmartCleanActivity,
                            "Concluídos: ${outcome.count { it.success }} · Falhas: ${outcome.count { !it.success }}",
                            Toast.LENGTH_LONG).show()
                        selected = emptySet()
                        selectedTrash = emptySet()
                        if (action == "mover" || action == "excluir") {
                            ScanRuntime.update { it.copy(report = null) }
                        }
                        refresh()
                    } catch (e: Exception) {
                        error = e.message ?: "Não foi possível concluir a operação."
                    } finally { busy = false }
                }
            }

            LaunchedEffect(scan.finishedAt) {
                if (scan.finishedAt != null) { selected = emptySet(); refresh() }
            }
            LaunchedEffect(Unit) { refresh() }

            MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Navy,
                background = Navy, surface = Surface, onSurface = Color.White)) {
                Scaffold(
                    containerColor = Navy,
                    bottomBar = {
                        NavigationBar(containerColor = Surface) {
                            MainTab.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = tab == item && !showHistory,
                                    onClick = { tab = item; showHistory = false; showSettings = false },
                                    icon = { Text(item.icon, fontSize = 22.sp) },
                                    label = { Text(item.title) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).background(Navy)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("SMART CLEAN", fontWeight = FontWeight.Bold, color = Mint, fontSize = 12.sp)
                                Text(when {
                                    showHistory -> "Histórico local"
                                    showSettings -> "Preferências da análise"
                                    else -> tab.title
                                }, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = {
                                if (showHistory || showSettings) {
                                    showHistory = false; showSettings = false
                                } else { showSettings = true; showHistory = false }
                            }) { Text(if (showHistory || showSettings) "Voltar" else "Ajustes") }
                        }
                        if (error != null || scan.error != null) {
                            Text(error ?: scan.error.orEmpty(), color = Amber,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp), fontSize = 12.sp)
                        }
                        when {
                            showHistory -> if (historyLoading) {
                                LoadingPanel("Carregando histórico…")
                            } else HistoryScreen(historyItems, onClear = {
                                history.clear(); refresh()
                            })
                            showSettings -> PreferencesScreen(largeMb, deep, excluded,
                                onLimit = { largeMb = it; settings.largeFileMb = it },
                                onDeep = { deep = it; settings.deepHashing = it },
                                onUnprotect = { settings.unprotectFolder(it); refresh() })
                            tab == MainTab.HOME -> DashboardScreen(usage, scan, broadAccess,
                                busy, onGrant = {
                                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:$packageName"))
                                    try { startActivity(intent) } catch (_: Exception) {
                                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                    }
                                }, onStart = { scanNow() }, onStop = {
                                    startService(Intent(this@SmartCleanActivity, ScanService::class.java)
                                        .setAction(ScanService.CANCEL))
                                }, onFiles = { tab = MainTab.FILES }, onHistory = {
                                    showHistory = true; showSettings = false
                                    loadHistory()
                                })
                            tab == MainTab.FILES -> FilesScreen(scan.report, category, selected, busy || scan.running,
                                onFilter = { category = it },
                                onCheck = { path, checked ->
                                    selected = if (checked) selected + path else selected - path
                                },
                                onSelect = { files ->
                                    val previous = selected
                                    scope.launch {
                                        busy = true
                                        try {
                                            selected = withContext(Dispatchers.Default) {
                                                previous + files.map { it.path }
                                            }
                                        } finally { busy = false }
                                    }
                                },
                                onDeselect = { files ->
                                    val previous = selected
                                    scope.launch {
                                        busy = true
                                        try {
                                            selected = withContext(Dispatchers.Default) {
                                                previous - files.map { it.path }.toSet()
                                            }
                                        } finally { busy = false }
                                    }
                                },
                                onDuplicates = {
                                    val groups = scan.report?.duplicateGroups.orEmpty()
                                    val previous = selected
                                    scope.launch {
                                        busy = true
                                        try {
                                            selected = withContext(Dispatchers.Default) {
                                                val extras = groups.flatMap { group ->
                                                    group.sortedBy { it.path }.drop(1)
                                                }.mapTo(hashSetOf()) { it.path }
                                                previous + extras
                                            }
                                        } finally { busy = false }
                                    }
                                },
                                onProtect = { folder ->
                                    settings.protectFolder(folder)
                                    excluded = settings.excludedFolders()
                                    selected = selected.filterNot { ScanPolicy.isInside(it, folder) }.toSet()
                                    Toast.makeText(this@SmartCleanActivity,
                                        "Pasta protegida. Uma nova análise aplicará a exclusão.", Toast.LENGTH_LONG).show()
                                },
                                onMove = { confirm = "mover" }, onDelete = { confirm = "excluir" })
                            tab == MainTab.HEALTH -> if (health == null) {
                                LoadingPanel("Lendo o estado do aparelho…")
                            } else HealthScreen(health!!, onRefresh = { refresh() },
                                onUsage = { openGuidance("Consulta de uso dos aplicativos", DeviceDiagnostics.USAGE_SETTINGS) },
                                onPower = { openGuidance("Revisão de economia de bateria", Settings.ACTION_BATTERY_SAVER_SETTINGS) },
                                onDisplay = { openGuidance("Ajuste de brilho", Settings.ACTION_DISPLAY_SETTINGS) },
                                onApps = { openGuidance("Revisão dos aplicativos", Settings.ACTION_APPLICATION_SETTINGS) })
                            else -> if (!trashLoaded) {
                                LoadingPanel("Carregando lixeira…")
                            } else TrashScreen(trashItems, selectedTrash, busy,
                                onCheck = { id, checked ->
                                    selectedTrash = if (checked) selectedTrash + id else selectedTrash - id
                                }, onRestore = { confirm = "restaurar" },
                                onDelete = { confirm = "esvaziar" })
                        }
                    }
                }

                if (confirm != null) {
                    val action = confirm!!
                    val isPermanent = action == "excluir" || action == "esvaziar"
                    val chosenFiles = if (action == "mover" || action == "excluir")
                        scan.report?.files.orEmpty().filter { it.path in selected }.map { it.bytes }
                    else trashItems.filter { it.id in selectedTrash }.map { it.bytes }
                    AlertDialog(
                        onDismissRequest = { confirm = null; doubleConfirm = false },
                        title = { Text(if (doubleConfirm) "Confirmação definitiva" else "Revisar ação") },
                        text = {
                            Text("${chosenFiles.size} arquivo(s) · ${readableSize(chosenFiles.sum())}. " + when (action) {
                                "mover" -> "Serão movidos para uma lixeira recuperável, sem liberar espaço ainda."
                                "restaurar" -> "Serão devolvidos às pastas de origem quando não houver conflito."
                                else -> "A exclusão é irreversível. Revise os arquivos antes de prosseguir."
                            })
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                if (isPermanent && !doubleConfirm) doubleConfirm = true
                                else execute(action)
                            }) { Text(if (isPermanent && !doubleConfirm) "Continuar" else "Confirmar") }
                        },
                        dismissButton = { TextButton(onClick = { confirm = null; doubleConfirm = false }) {
                            Text("Cancelar")
                        } }
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun LoadingPanel(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Mint)
            Spacer(Modifier.height(14.dp))
            Text(message, color = Muted)
        }
    }
}

@Composable
private fun DashboardScreen(
    usage: DeviceUsage, state: ScanState, granted: Boolean, busy: Boolean,
    onGrant: () -> Unit, onStart: () -> Unit, onStop: () -> Unit,
    onFiles: () -> Unit, onHistory: () -> Unit
) {
    val ratio = if (usage.total == 0L) 0f else (usage.used.toFloat() / usage.total).coerceIn(0f, 1f)
    val report = state.report
    LazyColumn(modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Seu armazenamento, sem mistérios.", fontSize = 17.sp, color = Muted) }
        item {
            PanelCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(progress = { ratio }, modifier = Modifier.size(88.dp),
                        color = Mint, trackColor = Color(0xFF344B61), strokeWidth = 8.dp)
                    Spacer(Modifier.width(20.dp))
                    Column {
                        Text("Armazenamento interno", fontSize = 12.sp, color = Muted)
                        Text("${(ratio * 100).toInt()}% ocupado", fontSize = 22.sp,
                            fontWeight = FontWeight.Bold)
                        Text("${readableSize(usage.free)} livres", color = Mint)
                    }
                }
                HorizontalDivider(color = Color(0xFF385069))
                Text("${readableSize(usage.used)} usados de ${readableSize(usage.total)}", color = Muted)
            }
        }
        item {
            PanelCard {
                Text("Mega Scan inteligente", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(if (!granted) "Para analisar arquivos fora da galeria, autorize o acesso aos arquivos compartilhados."
                    else "Descubra arquivos grandes, duplicados, temporários, ocultos e muito mais.", color = Muted)
                when {
                    !granted -> Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                        Text("Autorizar acesso a arquivos")
                    }
                    state.running -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(state.progress?.stage ?: "Preparando…", color = Mint)
                        Text("${state.progress?.files ?: 0} arquivos · " +
                            "${readableSize(state.progress?.bytes ?: 0L)} identificados", color = Muted)
                        Text(state.progress?.location.orEmpty(), maxLines = 2,
                            overflow = TextOverflow.Ellipsis, color = Muted, fontSize = 11.sp)
                        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                            Text("Interromper")
                        }
                    }
                    else -> Button(onClick = onStart, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (report == null) "Analisar agora" else "Fazer nova análise")
                    }
                }
                Text("A análise é local e pode continuar com a tela fechada quando o Android permitir. " +
                    "Nada é excluído automaticamente.", color = Muted, fontSize = 12.sp)
            }
        }
        if (report != null) {
            item {
                PanelCard {
                    Text("O que encontramos", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SummaryMetric("Arquivos", "${report.files.size}", Modifier.weight(1f))
                        SummaryMetric("Grandes", "${report.largeFiles.size}", Modifier.weight(1f))
                        SummaryMetric("Grupos iguais", "${report.duplicateGroups.size}", Modifier.weight(1f))
                    }
                    val extras = report.duplicateGroups.flatMap { it.sortedBy { item -> item.path }.drop(1) }
                    val candidates = (extras + report.temporaryCandidates).distinctBy { it.path }
                    Text("${readableSize(candidates.sumOf { it.bytes })} em possíveis candidatos à revisão. " +
                        "Não é uma promessa de espaço recuperável.", color = Mint, fontSize = 13.sp)
                    Text("Pastas ocultas: ${report.hiddenFolders.size} · Protegidas ignoradas: " +
                        "${report.protectedFoldersSkipped} · Pastas ilegíveis: ${report.unreadableFolders}",
                        color = Muted, fontSize = 12.sp)
                    if (report.incomplete || report.hashLimitReached || !report.mediaIndexChecked || report.resourcesLimited) {
                        Text(if (report.resourcesLimited)
                            "Comparação de duplicados pausada por aquecimento elevado do aparelho."
                            else "Análise parcial: alguns arquivos, comparações ou índices ficaram fora da leitura.",
                            color = Amber, fontSize = 12.sp)
                    }
                    Button(onClick = onFiles, modifier = Modifier.fillMaxWidth()) { Text("Revisar arquivos") }
                }
            }
        }
        item { OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
            Text("Ver histórico detalhado")
        } }
        item { Text("Pastas privadas de outros apps e partes do sistema permanecem inacessíveis " +
            "sem root. O Smart Clean informa os limites da análise.", fontSize = 11.sp, color = Muted) }
    }
}

@Composable
private fun SummaryMetric(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, color = Mint, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(title, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun FilesScreen(
    report: MegaReport?, category: FileCategory, selected: Set<String>, busy: Boolean,
    onFilter: (FileCategory) -> Unit, onCheck: (String, Boolean) -> Unit,
    onSelect: (List<MegaFile>) -> Unit, onDeselect: (List<MegaFile>) -> Unit,
    onDuplicates: () -> Unit, onProtect: (String) -> Unit,
    onMove: () -> Unit, onDelete: () -> Unit
) {
    if (report == null) {
        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            Text("Faça um Mega Scan na aba Início para explorar seus arquivos.", color = Muted)
        }
        return
    }
    val catalog by produceState<FileResultsCatalog?>(initialValue = null, report) {
        value = withContext(Dispatchers.Default) {
            val job = currentCoroutineContext()[Job]
            FileResultsCatalog.build(report) { job?.ensureActive() }
        }
    }
    if (catalog == null) {
        LoadingPanel("Organizando os resultados…")
        return
    }
    val index = catalog!!
    val slice = index[category]
    var shownCount by remember(report, category) { mutableIntStateOf(80) }
    val visible = remember(slice, shownCount) { slice.page(shownCount) }
    val listState = remember(report, category) { LazyListState() }
    LaunchedEffect(listState, slice) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastIndex ->
                if (lastIndex >= shownCount - 8 && shownCount < slice.count) {
                    shownCount = (shownCount + 80).coerceAtMost(slice.count)
                }
            }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 15.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FileCategory.entries.forEach { item ->
                FilterChip(selected = category == item, onClick = { onFilter(item) },
                    label = { Text("${item.title} (${index[item].count})") })
            }
        }
        Text("${slice.count} arquivos · ${readableSize(slice.totalBytes)}",
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp), color = Mint)
        if (category == FileCategory.OUTSIDE_INDEX) {
            Text(if (report.mediaIndexChecked) "Mídias possivelmente ausentes do índice da galeria; confira antes de excluir."
                else "O Android não autorizou consultar o índice de mídias nesta análise.",
                color = Amber, modifier = Modifier.padding(horizontal = 18.dp), fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 15.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !busy, onClick = { onSelect(slice.files) }) { Text("Selecionar todos") }
            TextButton(enabled = !busy, onClick = { onDeselect(slice.files) }) { Text("Desmarcar") }
            if (category == FileCategory.DUPLICATES) {
                TextButton(enabled = !busy, onClick = onDuplicates) {
                    Text("Marcar extras mantendo uma cópia")
                }
            }
        }
        Text("Nada vem pré-selecionado. Arquivos ocultos e temporários podem ser importantes.",
            modifier = Modifier.padding(horizontal = 18.dp), color = Muted, fontSize = 11.sp)
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(13.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(visible, key = { it.path }) { file ->
                val checked = file.path in selected
                Row(Modifier.fillMaxWidth().border(1.dp,
                    if (checked) Mint else Color(0xFF344B62), RoundedCornerShape(12.dp))
                    .clickable(enabled = !busy) { onCheck(file.path, !checked) }
                    .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, enabled = !busy, onCheckedChange = { onCheck(file.path, it) })
                    SmartPreview(file)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, fontWeight = FontWeight.SemiBold, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text(file.folder, color = Muted, fontSize = 10.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text(readableSize(file.bytes), color = Mint, fontSize = 12.sp)
                        TextButton(enabled = !busy, onClick = { onProtect(file.folder) },
                            contentPadding = PaddingValues(0.dp), modifier = Modifier.height(30.dp)) {
                            Text("Não tocar nesta pasta", fontSize = 11.sp)
                        }
                    }
                }
            }
            if (shownCount < slice.count) item(key = "load-more") {
                OutlinedButton(onClick = {
                    shownCount = (shownCount + 80).coerceAtMost(slice.count)
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Mostrar mais · ${visible.size} de ${slice.count}")
                }
            }
        }
        if (selected.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(Surface).padding(9.dp)) {
                Text("${selected.size} marcados", color = Mint, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = onMove, modifier = Modifier.weight(1f)) {
                        Text("Lixeira")
                    }
                    OutlinedButton(enabled = !busy, onClick = onDelete, modifier = Modifier.weight(1f)) {
                        Text("Excluir de vez")
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartPreview(file: MegaFile) {
    val image by produceState<Bitmap?>(initialValue = null, file.path, file.modified) {
        value = ThumbnailRepository.load(file)
    }
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF293E53)),
        contentAlignment = Alignment.Center) {
        if (image != null) Image(image!!.asImageBitmap(), file.name, Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop)
        else Text(when (file.kind) {
            FileKind.PHOTO, FileKind.SCREENSHOT -> "IMG"
            FileKind.VIDEO, FileKind.SCREEN_RECORDING -> "▶"
            FileKind.MUSIC, FileKind.AUDIO -> "♫"
            FileKind.PDF -> "PDF"
            FileKind.DOCUMENT -> "DOC"
            FileKind.ARCHIVE -> "ZIP"
            FileKind.APK -> "APK"
            else -> "FILE"
        }, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun HealthScreen(
    health: DeviceHealth,
    onRefresh: () -> Unit, onUsage: () -> Unit, onPower: () -> Unit,
    onDisplay: () -> Unit, onApps: () -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item {
            PanelCard {
                Text("Leituras do aparelho", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                SummaryMetric("Temperatura da bateria", health.batteryCelsius?.let { "%.1f °C".format(it) } ?: "Indisponível")
                Text("Estado térmico: ${DeviceDiagnostics.thermalText(health.thermalStatus)}", color = Muted)
                Text("Carga: ${health.batteryPercent?.let { "$it%" } ?: "Indisponível"}" +
                    if (health.charging) " · Carregando" else "", color = Muted)
                HorizontalDivider(color = Color(0xFF375069))
                SummaryMetric("RAM disponível", readableSize(health.availableRamBytes))
                Text("Total: ${readableSize(health.totalRamBytes)} · " +
                    if (health.lowMemory) "Android sinalizou memória baixa" else "Sem alerta de memória baixa",
                    color = Muted)
                Text("O Android gerencia a RAM. A memória livre varia naturalmente; nenhuma ação garante desempenho maior.",
                    fontSize = 12.sp, color = Muted)
                OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text("Atualizar medições reais")
                }
            }
        }
        item {
            PanelCard {
                Text("Ações assistidas", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("O aplicativo abre as configurações do Android; você decide cada alteração. " +
                    "Reduzir atividades pode diminuir o consumo, mas não resfria fisicamente o aparelho.", color = Muted)
                OutlinedButton(onClick = onPower, modifier = Modifier.fillMaxWidth()) {
                    Text("Revisar economia de bateria")
                }
                OutlinedButton(onClick = onDisplay, modifier = Modifier.fillMaxWidth()) {
                    Text("Ajustar brilho")
                }
                OutlinedButton(onClick = onApps, modifier = Modifier.fillMaxWidth()) {
                    Text("Gerenciar aplicativos")
                }
                Text("Ao retornar ao Smart Clean, a temperatura e a RAM são medidas novamente " +
                    "e registradas no histórico. Isso não prova que uma ação causou a diferença.",
                    color = Muted, fontSize = 11.sp)
            }
        }
        item {
            PanelCard {
                Text("Aplicativos usados recentemente", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                if (!health.usagePermissionGranted) {
                    Text("Permita acesso às estatísticas de uso para ver o tempo em primeiro plano nas últimas 24 horas.",
                        color = Muted)
                    Button(onClick = onUsage, modifier = Modifier.fillMaxWidth()) {
                        Text("Autorizar informações de uso")
                    }
                } else if (health.appUsage.isEmpty()) {
                    Text("Nenhum dado de uso disponível.", color = Muted)
                } else {
                    health.appUsage.forEach { app ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(app.label, modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text("${app.foregroundMs / 60_000} min", color = Mint)
                        }
                    }
                }
                Text("Tempo de uso não é medição de CPU e não identifica quais apps estão aquecendo o celular.",
                    color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun TrashScreen(
    items: List<TrashItem>, selected: Set<String>, busy: Boolean,
    onCheck: (String, Boolean) -> Unit, onRestore: () -> Unit, onDelete: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("Arquivos aqui ainda ocupam espaço e podem ser restaurados.", color = Muted,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        Text("${items.size} itens · ${readableSize(items.sumOf { it.bytes })}", color = Mint,
            modifier = Modifier.padding(horizontal = 18.dp))
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Sua lixeira está vazia.", color = Muted)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(items, key = { it.id }) { item ->
                    Row(Modifier.fillMaxWidth().border(1.dp, Color(0xFF385069),
                        RoundedCornerShape(12.dp)).clickable(enabled = !busy) {
                        onCheck(item.id, item.id !in selected)
                    }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.id in selected, enabled = !busy,
                            onCheckedChange = { onCheck(item.id, it) })
                        Column {
                            Text(File(item.originalPath).name, fontWeight = FontWeight.SemiBold)
                            Text(item.originalPath, color = Muted, fontSize = 10.sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                            Text(readableSize(item.bytes), color = Mint, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (selected.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().background(Surface).padding(11.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRestore, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Restaurar (${selected.size})")
                    }
                    OutlinedButton(onClick = onDelete, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Excluir de vez")
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(items: List<HistoryEntry>, onClear: () -> Unit) {
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    val limits = remember { mutableStateMapOf<String, Int>() }
    var confirmClear by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Somente neste aparelho. Cada ação registra os arquivos envolvidos, " +
                "as falhas e as medições antes/depois.", color = Muted)
        }
        items(items, key = { it.id }) { item ->
            PanelCard {
                Text(item.action, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(DateFormat.getDateTimeInstance().format(Date(item.timestamp)), color = Muted)
                Text("${item.successCount}/${item.outcomes.size} arquivos concluídos", color = Mint)
                Text("Armazenamento livre: ${readableSize(item.beforeFree)} → ${readableSize(item.afterFree)}",
                    color = Muted, fontSize = 12.sp)
                Text("Bateria: ${item.beforeBattery?.let { "$it °C" } ?: "indisponível"} → " +
                    "${item.afterBattery?.let { "$it °C" } ?: "indisponível"}", color = Muted, fontSize = 12.sp)
                Text("RAM livre: ${readableSize(item.beforeRam)} → ${readableSize(item.afterRam)}",
                    color = Muted, fontSize = 12.sp)
                if (item.outcomes.isNotEmpty()) {
                    TextButton(onClick = {
                        expanded = if (item.id in expanded) expanded - item.id else expanded + item.id
                    }) { Text(if (item.id in expanded) "Ocultar arquivos" else "Mostrar arquivos envolvidos") }
                }
                if (item.id in expanded) {
                    val limit = limits[item.id] ?: 40
                    item.outcomes.take(limit).forEach { outcome ->
                        Text("${if (outcome.success) "✓" else "!"} ${outcome.path} · " +
                            "${readableSize(outcome.bytes)} · ${outcome.detail}", color = Muted, fontSize = 11.sp)
                    }
                    if (limit < item.outcomes.size) {
                        TextButton(onClick = { limits[item.id] = limit + 40 }) {
                            Text("Mostrar mais arquivos ($limit/${item.outcomes.size})")
                        }
                    }
                }
            }
        }
        if (items.isNotEmpty()) item { OutlinedButton(onClick = { confirmClear = true }) {
            Text("Apagar histórico local")
        } }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("Apagar registros?") },
        text = { Text("O histórico local será removido. Esta ação não mexe nos arquivos do celular.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("Apagar histórico") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } })
}

@Composable
private fun PreferencesScreen(
    largeMb: Int, deep: Boolean, excluded: Set<String>,
    onLimit: (Int) -> Unit, onDeep: (Boolean) -> Unit,
    onUnprotect: (String) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            PanelCard {
                Text("Limite para arquivos grandes", fontWeight = FontWeight.Bold)
                Text("A partir de $largeMb MB", color = Mint)
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(50, 100, 250, 500, 1000).forEach { limit ->
                        FilterChip(selected = largeMb == limit, onClick = { onLimit(limit) },
                            label = { Text("$limit MB") })
                    }
                }
            }
        }
        item {
            PanelCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Comparação profunda", fontWeight = FontWeight.Bold)
                        Text("Analisa duplicados muito grandes, consumindo mais tempo, bateria e leitura de dados.",
                            color = Muted, fontSize = 12.sp)
                    }
                    Switch(checked = deep, onCheckedChange = onDeep)
                }
                Text("As novas preferências serão aplicadas no próximo Mega Scan.",
                    color = Muted, fontSize = 11.sp)
            }
        }
        item {
            PanelCard {
                Text("Pastas marcadas como 'não tocar'", fontWeight = FontWeight.Bold)
                if (excluded.isEmpty()) Text("Nenhuma pasta adicionada.", color = Muted)
                excluded.sorted().forEach { path ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(path, modifier = Modifier.weight(1f), fontSize = 12.sp, color = Muted)
                        TextButton(onClick = { onUnprotect(path) }) { Text("Remover") }
                    }
                }
                Text("Pastas do sistema e a lixeira são protegidas permanentemente.",
                    color = Muted, fontSize = 11.sp)
            }
        }
    }
}
