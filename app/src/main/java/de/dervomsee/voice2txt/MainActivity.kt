package de.dervomsee.voice2txt

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.IntentCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import de.dervomsee.voice2txt.ui.BenchmarkResult
import de.dervomsee.voice2txt.ui.MainViewModel
import de.dervomsee.voice2txt.ui.Screen
import de.dervomsee.voice2txt.ui.theme.Voice2TxtTheme
import de.dervomsee.voice2txt.whisper.ModelDownloader
import kotlinx.coroutines.launch

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Handle the intent if it's an audio file being shared
        val viewModel = androidx.lifecycle.ViewModelProvider(this).get(MainViewModel::class.java)
        handleIntent(intent, viewModel)
    }

    internal fun handleIntent(intent: Intent?, viewModel: MainViewModel) {
        if (intent == null) return
        
        Log.d("MainActivity", "handleIntent: action=${intent.action}, type=${intent.type}")
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("audio/") == true) {
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            
            Log.d("MainActivity", "Extracted URI: $uri")
            uri?.let {
                viewModel.transcribeFile(it)
            }
        }
    }
}

@Composable
fun AppContent(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = context as? MainActivity

    LaunchedEffect(activity) {
        activity?.let {
            it.handleIntent(it.intent, viewModel)
        }
    }

    // Handle system back gesture/button
    BackHandler(enabled = viewModel.currentScreen !is Screen.Main) {
        viewModel.navigateTo(Screen.Main)
    }

    Crossfade(targetState = viewModel.currentScreen, label = "ScreenTransition") { screen ->
        when (screen) {
            is Screen.Main -> MainScreen(viewModel)
            is Screen.Settings -> SettingsScreen(viewModel)
            is Screen.Benchmark -> BenchmarkScreen(viewModel)
            is Screen.About -> AboutScreen(viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    var showMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.toggleRecording()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.transcribeFile(it) }
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
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_about)) },
                            onClick = {
                                showMenu = false
                                viewModel.navigateTo(Screen.About)
                            }
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
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

            if (viewModel.isTranscribing) {
                LinearProgressIndicator(
                    progress = { viewModel.transcriptionProgress },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    val copiedMessage = stringResource(R.string.copied_to_clipboard)
                    Text(
                        text = viewModel.transcription.ifEmpty { stringResource(R.string.transcription_hint) },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { /* Normal click */ },
                                onLongClick = {
                                    if (viewModel.transcription.isNotEmpty()) {
                                        clipboardManager.setText(AnnotatedString(viewModel.transcription))
                                        scope.launch {
                                            snackbarHostState.showSnackbar(copiedMessage)
                                        }
                                    }
                                }
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier.weight(1f).height(56.dp)
                ) {
                    Text(
                        when {
                            viewModel.isRecording -> stringResource(R.string.stop_recording)
                            viewModel.isTranscribing -> stringResource(R.string.stop_transcription)
                            else -> stringResource(R.string.start_recording)
                        }
                    )
                }

                Button(
                    onClick = {
                        filePickerLauncher.launch("audio/*")
                    },
                    enabled = !viewModel.isRecording && !viewModel.isTranscribing,
                    modifier = Modifier.weight(1f).height(56.dp)
                ) {
                    Text(stringResource(R.string.open_audio_file))
                }
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

    val benchmarkFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.loadBenchmarkFile(it) }
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
                Button(
                    onClick = { benchmarkFilePicker.launch("audio/*") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    enabled = !viewModel.isRecording
                ) {
                    Text(stringResource(R.string.benchmark_open_file))
                }

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
fun AboutScreen(viewModel: MainViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
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
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.about_version, viewModel.appVersion),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = stringResource(R.string.about_copyright),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.about_license),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            Text(
                text = stringResource(R.string.about_whisper_info),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Start)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = viewModel.whisperSystemInfo,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    var showModelDialog by remember { mutableStateOf(false) }
    var modelToDelete by remember { mutableStateOf<de.dervomsee.voice2txt.whisper.WhisperModel?>(null) }

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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = {
                                            viewModel.selectModel(model)
                                            showModelDialog = false
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = model.name,
                                                modifier = Modifier.weight(1f),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Start
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
                                    if (downloaded) {
                                        IconButton(onClick = { modelToDelete = model }) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = stringResource(R.string.delete_model),
                                                tint = MaterialTheme.colorScheme.error
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

    modelToDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            title = { Text(stringResource(R.string.delete_model)) },
            text = { Text(stringResource(R.string.delete_model_confirmation, model.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteModel(model)
                        modelToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete_model_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { modelToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
