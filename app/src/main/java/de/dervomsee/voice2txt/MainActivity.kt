package de.dervomsee.voice2txt

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import de.dervomsee.voice2txt.ui.BenchmarkResult
import de.dervomsee.voice2txt.ui.MainViewModel
import de.dervomsee.voice2txt.ui.Screen
import de.dervomsee.voice2txt.ui.theme.Voice2TxtTheme
import de.dervomsee.voice2txt.whisper.ModelDownloader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Voice2TxtTheme {
                AppContent()
            }
        }
    }
}

@Composable
fun AppContent(viewModel: MainViewModel = viewModel()) {
    // Handle system back gesture/button
    BackHandler(enabled = viewModel.currentScreen !is Screen.Main) {
        viewModel.navigateTo(Screen.Main)
    }

    Crossfade(targetState = viewModel.currentScreen, label = "ScreenTransition") { screen ->
        when (screen) {
            is Screen.Main -> MainScreen(viewModel)
            is Screen.Settings -> SettingsScreen(viewModel)
            is Screen.Benchmark -> BenchmarkScreen(viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    var showMenu by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.toggleRecording()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_benchmark)) },
                            onClick = {
                                showMenu = false
                                viewModel.navigateTo(Screen.Benchmark)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_settings)) },
                            onClick = {
                                showMenu = false
                                viewModel.navigateTo(Screen.Settings)
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = viewModel.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )

            if (viewModel.lastPerformanceRtf > 0f) {
                Text(
                    text = stringResource(R.string.performance_label, viewModel.lastPerformanceRtf),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = viewModel.transcription.ifEmpty { stringResource(R.string.transcription_hint) },
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(
                    if (viewModel.isRecording) stringResource(R.string.stop_recording)
                    else stringResource(R.string.start_recording)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(viewModel: MainViewModel) {
    var selectedResult by remember { mutableStateOf<BenchmarkResult?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.toggleBenchmarkRecording()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.benchmark_title)) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateTo(Screen.Main) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (viewModel.isBenchmarking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = viewModel.benchmarkStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        modifier = Modifier.weight(1f),
                        colors = if (viewModel.isRecording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
                    ) {
                        Text(if (viewModel.isRecording) stringResource(R.string.benchmark_stop_recording) else stringResource(R.string.benchmark_start_recording))
                    }

                    Button(
                        onClick = { viewModel.runBenchmark() },
                        enabled = viewModel.benchmarkSampleData != null && !viewModel.isRecording,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.benchmark_start))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Table Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.benchmark_col_model), Modifier.weight(2f), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.benchmark_col_device), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.benchmark_col_time), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.benchmark_col_rtf), Modifier.weight(1f), fontWeight = FontWeight.Bold)
            }

            HorizontalDivider()

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(viewModel.benchmarkResults) { result ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedResult = result }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(result.modelName, Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (result.isGpu) stringResource(R.string.benchmark_gpu) else stringResource(R.string.benchmark_cpu),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text("${result.inferenceTimeMs}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(String.format("%.2f", result.rtf), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    selectedResult?.let { result ->
        AlertDialog(
            onDismissRequest = { selectedResult = null },
            title = {
                Text(
                    stringResource(
                        R.string.benchmark_transcription_title,
                        result.modelName,
                        if (result.isGpu) stringResource(R.string.benchmark_gpu) else stringResource(R.string.benchmark_cpu)
                    )
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(result.transcription)
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedResult = null }) {
                    Text("OK")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    var showModelDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateTo(Screen.Main) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Model Selection
            Text(
                text = stringResource(R.string.settings_model_label),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Start)
            )
            
            OutlinedCard(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                onClick = { showModelDialog = true }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = viewModel.selectedModel.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (ModelDownloader.isModelDownloaded(context, viewModel.selectedModel.fileName)) 
                            stringResource(R.string.model_downloaded_tag) 
                        else stringResource(R.string.model_required),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (!ModelDownloader.isModelDownloaded(context, viewModel.selectedModel.fileName)) {
                Button(
                    onClick = { viewModel.downloadModel() },
                    enabled = !viewModel.isDownloading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.download_model))
                }
            }

            if (viewModel.isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { viewModel.downloadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.downloading_progress, (viewModel.downloadProgress * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Language Selection
            Text(
                text = stringResource(R.string.settings_language_label),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Start)
            )

            var showLanguageDialog by remember { mutableStateOf(false) }
            val currentLanguage = de.dervomsee.voice2txt.whisper.whisperLanguages.find { it.code == viewModel.selectedLanguage }
            
            OutlinedCard(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                onClick = { showLanguageDialog = true }
            ) {
                Text(
                    text = currentLanguage?.name ?: viewModel.selectedLanguage,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            if (showLanguageDialog) {
                AlertDialog(
                    onDismissRequest = { showLanguageDialog = false },
                    title = { Text(stringResource(R.string.settings_language_label)) },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            de.dervomsee.voice2txt.whisper.whisperLanguages.forEach { language ->
                                TextButton(
                                    onClick = {
                                        viewModel.setLanguage(language.code)
                                        showLanguageDialog = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = language.name,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Start
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showLanguageDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // GPU Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.gpu_label),
                    style = MaterialTheme.typography.titleMedium
                )
                Switch(
                    checked = viewModel.useGpu,
                    onCheckedChange = { viewModel.toggleGpu(it) }
                )
            }
        }
    }

    if (showModelDialog) {
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.select_model_title))
                    IconButton(onClick = { viewModel.refreshModels() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh_models)
                        )
                    }
                }
            },
            text = {
                Box(modifier = Modifier.heightIn(max = 400.dp)) {
                    if (viewModel.isLoadingModels) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(R.string.loading_models),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    } else {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            viewModel.availableModelsList.forEach { model ->
                                val downloaded = ModelDownloader.isModelDownloaded(context, model.fileName)
                                TextButton(
                                    onClick = {
                                        viewModel.selectModel(model)
                                        showModelDialog = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = model.name,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (downloaded) {
                                            Text(
                                                text = stringResource(R.string.model_downloaded_tag),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
