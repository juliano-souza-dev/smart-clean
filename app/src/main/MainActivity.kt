package com.juliano.limpacel

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ocean = Color(0xFF0C1624)
private val Panel = Color(0xFF18283B)
private val Pale = Color(0xFFA9BBD0)
private val Green = Color(0xFF34DBB5)
private val Warning = Color(0xFFFFCE84)

class MainActivity : ComponentActivity() {
    private var broadAccess by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        broadAccess = Environment.isExternalStorageManager()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(12, 22, 36)
        window.navigationBarColor = android.graphics.Color.rgb(12, 22, 36)

        setContent {
            val scanner = remember { MegaScanner(applicationContext) }
            val scope = rememberCoroutineScope()
            var usage by remember { mutableStateOf(scanner.usage()) }
            var report by remember { mutableStateOf<MegaReport?>(null) }
            var progress by remember { mutableStateOf<MegaProgress?>(null) }
            var scanning by remember { mutableStateOf(false) }
            var deleting by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
            var filter by remember { mutableStateOf(ResultFilter.LARGE) }
            var confirmDelete by remember { mutableStateOf(false) }
            var scanJob by remember { mutableStateOf<Job?>(null) }

            fun startMegaScan() {
                if (!broadAccess) return
                scanJob?.cancel()
                scanJob = scope.launch {
                    scanning = true
                    error = null
                    selected = emptySet()
                    report = null
                    progress = MegaProgress("Preparando análise", 0, 0, "Armazenamento compartilhado")
                    try {
                        report = scanner.scan { updated ->
                            withContext(Dispatchers.Main) { progress = updated }
                        }
                        usage = withContext(Dispatchers.IO) { scanner.usage() }
                    } catch (_: CancellationException) {
                        error = "Análise interrompida. Nenhum arquivo foi apagado."
                    } catch (e: Exception) {
                        error = e.message ?: "Não foi possível concluir o Mega Scan."
                    } finally {
                        scanning = false
                        progress = null
                    }
                }
            }

            LaunchedEffect(broadAccess) {
                if (broadAccess && report == null && !scanning) startMegaScan()
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Green, onPrimary = Ocean, background = Ocean,
                    surface = Panel, onSurface = Color.White
                )
            ) {
                MegaScreen(
                    accessGranted = broadAccess,
                    usage = usage,
                    report = report,
                    progress = progress,
                    busy = scanning || deleting,
                    error = error,
                    filter = filter,
                    selected = selected,
                    onFilter = { filter = it },
                    onGrantAccess = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        try { startActivity(intent) } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    },
                    onRescan = { startMegaScan() },
                    onStop = { scanJob?.cancel() },
                    onCheck = { path, value ->
                        selected = if (value) selected + path else selected - path
                    },
                    onSelectDuplicateExtras = {
                        val extras = report?.duplicateGroups.orEmpty().flatMap { group ->
                            group.sortedByDescending { it.modified }.drop(1)
                        }.mapTo(hashSetOf()) { it.path }
                        selected = selected + extras
                    },
                    onDelete = { confirmDelete = true }
                )

                if (confirmDelete) {
                    val files = report?.files.orEmpty().filter { it.path in selected }
                    val bytes = files.sumOf { it.bytes }
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text("Excluir arquivos selecionados?") },
                        text = {
                            Text(
                                "${files.size} arquivo(s), totalizando ${readableSize(bytes)}. " +
                                    "Esta exclusão pode ser permanente. Confira a lista e " +
                                    "mantenha uma cópia de tudo o que for importante."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmDelete = false
                                if (files.isNotEmpty()) scope.launch {
                                    deleting = true
                                    try {
                                        val result = withContext(Dispatchers.IO) {
                                            scanner.delete(files.map { it.path })
                                        }
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Excluídos: ${result.deleted} · Falhas: ${result.failed} · " +
                                                "${readableSize(result.freedBytes)} liberados",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        selected = emptySet()
                                        startMegaScan()
                                    } catch (e: Exception) {
                                        error = e.message ?: "Falha ao excluir arquivos."
                                    } finally {
                                        deleting = false
                                    }
                                }
                            }) { Text("Excluir definitivamente") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") }
                        }
                    )
                }
            }
        }
    }
}

private enum class ResultFilter(val title: String) {
    LARGE("Grandes"), DUPLICATES("Duplicados"), INSTALLERS("APKs"),
    TEMPORARY("Temporários"), ALL("Todos")
}

@Composable
private fun MegaScreen(
    accessGranted: Boolean,
    usage: DeviceUsage,
    report: MegaReport?,
    progress: MegaProgress?,
    busy: Boolean,
    error: String?,
    filter: ResultFilter,
    selected: Set<String>,
    onFilter: (ResultFilter) -> Unit,
    onGrantAccess: () -> Unit,
    onRescan: () -> Unit,
    onStop: () -> Unit,
    onCheck: (String, Boolean) -> Unit,
    onSelectDuplicateExtras: () -> Unit,
    onDelete: () -> Unit
) {
    val usedRatio = if (usage.total == 0L) 0f
        else (usage.used.toFloat() / usage.total).coerceIn(0f, 1f)
    val candidates = when (filter) {
        ResultFilter.LARGE -> report?.largeFiles.orEmpty()
        ResultFilter.DUPLICATES -> report?.let { result ->
            result.files.filter { it.path in result.duplicatePaths }
        }.orEmpty()
        ResultFilter.INSTALLERS -> report?.installers.orEmpty()
        ResultFilter.TEMPORARY -> report?.temporaryCandidates.orEmpty()
        ResultFilter.ALL -> report?.files.orEmpty()
    }

    Column(Modifier.fillMaxSize().background(Ocean).safeDrawingPadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 17.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("SMART CLEAN · MEGA SCAN", color = Green,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Descubra o que ocupa espaço.", fontSize = 25.sp,
                        fontWeight = FontWeight.Bold)
                    Text("O aplicativo procura os arquivos por você.", color = Pale)
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                progress = { usedRatio },
                                modifier = Modifier.size(94.dp), strokeWidth = 8.dp,
                                color = Green, trackColor = Color(0xFF31475C)
                            )
                            Spacer(Modifier.width(18.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("ARMAZENAMENTO INTERNO", color = Pale, fontSize = 11.sp)
                                Text("${(usedRatio * 100).toInt()}% em uso", fontSize = 23.sp,
                                    fontWeight = FontWeight.Bold)
                                Text("${readableSize(usage.free)} livres", color = Green)
                            }
                        }
                        HorizontalDivider(color = Color(0xFF2E4157))
                        Text("${readableSize(usage.used)} usados de ${readableSize(usage.total)}",
                            color = Pale, fontSize = 12.sp)
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Mega Scan automático", fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold)
                        Text(
                            if (!accessGranted)
                                "Autorize o gerenciamento dos arquivos compartilhados uma vez. " +
                                    "Não é necessário escolher nenhuma pasta."
                            else if (busy)
                                "Analisando o armazenamento. Nenhum arquivo será excluído automaticamente."
                            else "Verifique todo o armazenamento compartilhado com um toque.",
                            color = Pale, fontSize = 13.sp
                        )
                        if (!accessGranted) {
                            Button(onClick = onGrantAccess, modifier = Modifier.fillMaxWidth()) {
                                Text("Permitir acesso e iniciar")
                            }
                        } else if (busy) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(progress?.stage ?: "Processando…", color = Green)
                            Text(
                                "${progress?.files ?: 0} arquivos · " +
                                    "${readableSize(progress?.bytes ?: 0L)} identificados",
                                color = Pale, fontSize = 12.sp
                            )
                            Text(progress?.location.orEmpty(), color = Pale, fontSize = 11.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                                Text("Interromper análise")
                            }
                        } else {
                            Button(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
                                Text(if (report == null) "Iniciar Mega Scan" else "Fazer novo Mega Scan")
                            }
                        }
                    }
                }
            }

            if (error != null) item {
                Text(error, color = Warning, fontSize = 13.sp)
            }

            if (report != null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Resultado do Mega Scan", fontSize = 20.sp,
                            fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Metric("Arquivos", "${report.files.size}", Modifier.weight(1f))
                            Metric("Grandes", "${report.largeFiles.size}", Modifier.weight(1f))
                            Metric("Duplicados", "${report.duplicateGroups.size}", Modifier.weight(1f))
                        }
                        Text(
                            "${readableSize(report.indexedBytes)} encontrados em " +
                                "${report.roots.size} unidade(s) de armazenamento compartilhado.",
                            color = Pale, fontSize = 12.sp
                        )
                        if (report.incomplete || report.hashLimitReached || report.unreadableFolders > 0) {
                            Text(
                                "Algumas áreas são protegidas ou não puderam ser lidas. " +
                                    "A comparação de duplicados também possui limite de processamento.",
                                color = Warning, fontSize = 12.sp
                            )
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        ResultFilter.entries.forEach { value ->
                            FilterChip(selected = filter == value,
                                onClick = { onFilter(value) },
                                label = { Text(value.title) })
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${candidates.size} arquivo(s) nesta categoria",
                            fontWeight = FontWeight.SemiBold)
                        Text("Toque para selecionar. Confira o caminho completo antes de excluir.",
                            color = Pale, fontSize = 12.sp)
                        if (filter == ResultFilter.DUPLICATES && report.duplicateGroups.isNotEmpty()) {
                            OutlinedButton(onClick = onSelectDuplicateExtras) {
                                Text("Marcar cópias extras (mantendo uma de cada)")
                            }
                        }
                        if (filter == ResultFilter.TEMPORARY) {
                            Text("Arquivos classificados pelo nome; podem ser importantes. Revise-os.",
                                color = Warning, fontSize = 12.sp)
                        }
                    }
                }
                if (candidates.isEmpty()) item {
                    Text("Nenhum resultado nesta categoria.", color = Pale)
                }
                items(candidates, key = { it.path }) { file ->
                    val checked = file.path in selected
                    Row(
                        Modifier.fillMaxWidth().border(
                            1.dp, if (checked) Green else Color(0xFF324359),
                            RoundedCornerShape(14.dp)
                        ).clickable(enabled = !busy) { onCheck(file.path, !checked) }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { onCheck(file.path, it) },
                            enabled = !busy)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(file.name, fontWeight = FontWeight.Medium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(file.folder, fontSize = 11.sp, color = Pale,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(readableSize(file.bytes), fontSize = 12.sp, color = Green)
                        }
                    }
                }
            }

            item {
                Text("O Android mantém dados privados de outros aplicativos protegidos. " +
                    "O Smart Clean não usa root nem apaga arquivos sem confirmação.",
                    color = Pale, fontSize = 11.sp)
            }
        }

        if (selected.isNotEmpty()) {
            Button(
                onClick = onDelete, enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF28A82)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 10.dp)
            ) {
                Text("Revisar exclusão de ${selected.size} arquivo(s)", color = Ocean)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier.background(Panel, RoundedCornerShape(16.dp)).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(value, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 11.sp, color = Pale)
    }
}
