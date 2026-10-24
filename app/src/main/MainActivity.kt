package com.juliano.limpacel

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Background = Color(0xFF0D1725)
private val Surface = Color(0xFF17273A)
private val TextMuted = Color(0xFFAABCD0)
private val Mint = Color(0xFF31D4AF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(13, 23, 37)
        window.navigationBarColor = android.graphics.Color.rgb(13, 23, 37)

        setContent {
            val scanner = remember { StorageScanner(applicationContext) }
            val scope = rememberCoroutineScope()
            var folder by remember {
                mutableStateOf(getPreferences(MODE_PRIVATE).getString("tree_uri", null)?.let(Uri::parse))
            }
            var refreshKey by remember { mutableIntStateOf(0) }
            var usage by remember { mutableStateOf(scanner.diskUsage()) }
            var report by remember { mutableStateOf<ScanReport?>(null) }
            var scanning by remember { mutableStateOf(false) }
            var deleting by remember { mutableStateOf(false) }
            var errorMessage by remember { mutableStateOf<String?>(null) }
            var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
            var showDeleteConfirmation by remember { mutableStateOf(false) }
            var currentFilter by remember { mutableStateOf(FileFilter.LARGE) }

            val folderPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    try {
                        contentResolver.takePersistableUriPermission(uri, flags)
                    } catch (_: SecurityException) {
                        // Alguns provedores permitem somente leitura.
                        try {
                            contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: SecurityException) {
                            errorMessage = "Não foi possível manter acesso a essa pasta."
                        }
                    }
                    getPreferences(MODE_PRIVATE).edit()
                        .putString("tree_uri", uri.toString()).apply()
                    folder = uri
                    report = null
                    selectedIds = emptySet()
                    refreshKey++
                }
            }

            LaunchedEffect(folder, refreshKey) {
                if (folder != null) {
                    scanning = true
                    errorMessage = null
                    selectedIds = emptySet()
                    report = try {
                        withContext(Dispatchers.IO) { scanner.scan(folder!!) }
                    } catch (e: Exception) {
                        errorMessage = e.message ?: "Falha ao analisar a pasta"
                        null
                    } finally {
                        scanning = false
                    }
                }
                usage = withContext(Dispatchers.IO) { scanner.diskUsage() }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Mint,
                    onPrimary = Background,
                    background = Background,
                    surface = Surface,
                    onSurface = Color.White
                )
            ) {
                CleanerScreen(
                    usage = usage,
                    folder = folder,
                    report = report,
                    scanning = scanning || deleting,
                    errorMessage = errorMessage,
                    selectedIds = selectedIds,
                    filter = currentFilter,
                    onChangeFilter = { currentFilter = it },
                    onPickFolder = { folderPicker.launch(null) },
                    onManageStorage = {
                        try {
                            startActivity(Intent(StorageManager.ACTION_MANAGE_STORAGE))
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    },
                    onRescan = { refreshKey++ },
                    onSelectionChange = { id, checked ->
                        selectedIds = if (checked) selectedIds + id else selectedIds - id
                    },
                    onDelete = { showDeleteConfirmation = true }
                )

                val toRemove = report?.files?.filter { it.id in selectedIds }.orEmpty()
                if (showDeleteConfirmation) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirmation = false },
                        title = { Text("Confirmar exclusão") },
                        text = {
                            Text(
                                "Você selecionou ${toRemove.size} arquivo(s), somando " +
                                    "${formatBytes(toRemove.sumOf { it.sizeBytes })}. " +
                                    "A remoção pode ser permanente e não poderá ser desfeita. " +
                                    "Verifique se manteve uma cópia dos arquivos importantes."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showDeleteConfirmation = false
                                if (toRemove.isNotEmpty()) {
                                    scope.launch {
                                        deleting = true
                                        val result = withContext(Dispatchers.IO) {
                                            scanner.deleteSelected(toRemove.map { it.uri })
                                        }
                                        deleting = false
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Removidos: ${result.removed}; falhas: ${result.failed}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        selectedIds = emptySet()
                                        refreshKey++
                                    }
                                }
                            }) { Text("Excluir arquivos") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirmation = false }) {
                                Text("Cancelar")
                            }
                        }
                    )
                }
            }
        }
    }
}

private enum class FileFilter(val title: String) {
    LARGE("Grandes"), DUPLICATES("Duplicados"), ALL("Todos")
}

@Composable
private fun CleanerScreen(
    usage: DiskUsage,
    folder: Uri?,
    report: ScanReport?,
    scanning: Boolean,
    errorMessage: String?,
    selectedIds: Set<String>,
    filter: FileFilter,
    onChangeFilter: (FileFilter) -> Unit,
    onPickFolder: () -> Unit,
    onManageStorage: () -> Unit,
    onRescan: () -> Unit,
    onSelectionChange: (String, Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val usageRatio = if (usage.totalBytes <= 0) 0f
        else (usage.usedBytes.toFloat() / usage.totalBytes).coerceIn(0f, 1f)
    val candidates = when (filter) {
        FileFilter.LARGE -> report?.largeFiles.orEmpty()
        FileFilter.DUPLICATES -> report?.files?.filter { it.id in report.duplicateIds }.orEmpty()
        FileFilter.ALL -> report?.files.orEmpty()
    }
    val visibleFiles = candidates.take(100)

    Column(
        modifier = Modifier.fillMaxSize().background(Background).safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        Text("LIMPACEL", fontSize = 13.sp, color = Mint, fontWeight = FontWeight.Bold)
        Text("Seu celular, mais leve.", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("Encontre o que ocupa espaço e escolha o que remover.", color = TextMuted)

        Card(colors = CardDefaults.cardColors(containerColor = Surface),
            shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        progress = { usageRatio },
                        strokeWidth = 8.dp,
                        color = Mint,
                        trackColor = Color(0xFF32465B),
                        modifier = Modifier.size(100.dp)
                    )
                    Spacer(Modifier.width(18.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("ARMAZENAMENTO", color = TextMuted, fontSize = 11.sp)
                        Text("${(usageRatio * 100).toInt()}% usado", fontSize = 24.sp,
                            fontWeight = FontWeight.Bold)
                        Text("${formatBytes(usage.freeBytes)} livres", color = Mint)
                    }
                }
                HorizontalDivider(color = Color(0xFF33445A))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Em uso: ${formatBytes(usage.usedBytes)}", color = TextMuted,
                        fontSize = 12.sp)
                    Text("Total: ${formatBytes(usage.totalBytes)}", color = TextMuted,
                        fontSize = 12.sp)
                }
                OutlinedButton(onClick = onManageStorage, modifier = Modifier.fillMaxWidth()) {
                    Text("Configurações de armazenamento")
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Surface),
            shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text("Analisar uma pasta", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (folder == null) "Escolha uma pasta para localizar arquivos grandes e duplicados."
                    else "Selecionada: ${folder.lastPathSegment?.substringAfterLast(':') ?: "pasta autorizada"}",
                    color = TextMuted, fontSize = 13.sp
                )
                Button(onClick = onPickFolder, enabled = !scanning,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (folder == null) "Escolher pasta" else "Trocar pasta")
                }
                if (folder != null) {
                    TextButton(onClick = onRescan, enabled = !scanning,
                        modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Analisar novamente")
                    }
                }
            }
        }

        if (scanning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(25.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(12.dp))
                Text("Processando arquivos com segurança…", color = TextMuted)
            }
        }
        errorMessage?.let {
            Text("Aviso: $it", color = Color(0xFFFFA59E))
        }

        if (report != null) {
            Text("Resultado da análise", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                ResultMetric("Arquivos", "${report.files.size}", Modifier.weight(1f))
                ResultMetric("Grandes", "${report.largeFiles.size}", Modifier.weight(1f))
                ResultMetric("Grupos iguais", "${report.duplicateGroups.size}", Modifier.weight(1f))
            }
            Text(
                "A pasta analisada contém ${formatBytes(report.knownBytes)} em arquivos identificados.",
                color = TextMuted, fontSize = 13.sp
            )
            if (report.wasTruncated || report.hashingLimited || report.skippedEntries > 0) {
                Text(
                    "Análise parcial: ${report.skippedEntries} itens não puderam ser lidos; " +
                        "a verificação possui limites de quantidade e tamanho.",
                    color = Color(0xFFFFCC83), fontSize = 12.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FileFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { onChangeFilter(option) },
                        label = { Text(option.title) }
                    )
                }
            }
            Text(
                if (filter == FileFilter.DUPLICATES)
                    "Arquivos com conteúdo idêntico. Confira quais cópias deseja manter."
                else "Selecione apenas os arquivos que você reconhece e deseja remover.",
                color = TextMuted, fontSize = 13.sp
            )

            if (candidates.isEmpty()) {
                Text("Nenhum arquivo nesta categoria.", color = TextMuted)
            }
            visibleFiles.forEach { file ->
                val checked = file.id in selectedIds
                Row(
                    Modifier.fillMaxWidth().border(
                        1.dp, if (checked) Mint else Color(0xFF304359), RoundedCornerShape(14.dp)
                    ).clickable(enabled = !scanning) { onSelectionChange(file.id, !checked) }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked, enabled = !scanning,
                        onCheckedChange = { onSelectionChange(file.id, it) })
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(formatBytes(file.sizeBytes), color = TextMuted, fontSize = 12.sp)
                    }
                    if (file.id in report.duplicateIds) {
                        Text("Igual", fontSize = 11.sp, color = Mint)
                    }
                }
            }
            if (candidates.size > 100) {
                Text("Mostrando os 100 primeiros de ${candidates.size} arquivos.",
                    color = TextMuted, fontSize = 12.sp)
            }
            if (selectedIds.isNotEmpty()) {
                Button(
                    onClick = onDelete,
                    enabled = !scanning,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE77B73))
                ) {
                    Text("Excluir ${selectedIds.size} arquivo(s)", color = Background)
                }
            }
        }

        Text("Limpeza sob seu controle · Sem acesso irrestrito aos seus dados",
            color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ResultMetric(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.background(Surface, RoundedCornerShape(16.dp)).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(label, color = TextMuted, fontSize = 11.sp)
    }
}
