package de.dervomsee.voice2txt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import de.dervomsee.voice2txt.ui.AppInfo
import de.dervomsee.voice2txt.ui.MainViewModel
import de.dervomsee.voice2txt.ui.theme.Voice2TxtTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted: Boolean ->
        if (isGranted) {
            pendingUri?.let { viewModel.processAudio(it) }
            pendingUri = null
        }
    }

    private var pendingUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        handleIntent(intent)

        setContent {
            Voice2TxtTheme {
                MainScreen(viewModel) {
                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        
        uri?.let {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                viewModel.processAudio(it)
            } else {
                pendingUri = it
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, onGrantPermission: () -> Unit) {
    var showMenu by remember { mutableStateOf(value = false) }
    val showSettings = remember { mutableStateOf(value = false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.select_engine))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        viewModel.engines.forEach { engine ->
                            DropdownMenuItem(
                                text = { Text(engine.name) },
                                onClick = {
                                    viewModel.selectEngine(engine)
                                    showMenu = false
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { 
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Info, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.info))
                                }
                            },
                            onClick = { 
                                viewModel.loadAppInfo()
                                showMenu = false 
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.settings))
                                }
                            },
                            onClick = {
                                showSettings.value = true
                                showMenu = false
                            }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (viewModel.transcriptionText.isNotEmpty()) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val shareText = stringResource(R.string.share_text)
                FloatingActionButton(onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, viewModel.transcriptionText)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, shareText))
                }) {
                    Icon(Icons.Default.Share, contentDescription = shareText)
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = viewModel.currentEngine.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            if (viewModel.isProcessing) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.processing))
            }

            viewModel.errorText?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (error.contains("PERMISSION") || error.contains("permission")) {
                    Button(onClick = onGrantPermission) {
                        Text(stringResource(R.string.grant_permission))
                    }
                }
            }

            SelectionContainer {
                Text(
                    text = viewModel.transcriptionText.ifEmpty { stringResource(R.string.transcription_hint) },
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }

    viewModel.appInfo?.let { info ->
        AppInfoDialog(info = info, onDismiss = { viewModel.dismissAppInfo() })
    }

    if (showSettings.value) {
        SettingsDialog(
            viewModel = viewModel,
            onDismiss = { showSettings.value = false }
        )
    }
}

@Composable
fun SettingsDialog(viewModel: MainViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column {
                viewModel.audioStats?.let { stats ->
                    Text(
                        text = stringResource(R.string.audio_stats),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(stringResource(R.string.min_max, stats.min, stats.max))
                    Text(stringResource(R.string.peak_value, stats.peak))
                    Spacer(Modifier.height(16.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.debug_play_audio))
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = viewModel.debugPlayAudio,
                        onCheckedChange = { viewModel.toggleDebugPlayAudio() }
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.enable_bandpass))
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = viewModel.enableBandpass,
                        onCheckedChange = { viewModel.toggleBandpass() }
                    )
                }
                if (viewModel.enableBandpass) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.low_freq, viewModel.lowFreq))
                    Slider(
                        value = viewModel.lowFreq.toFloat(),
                        onValueChange = { viewModel.updateBandpassFrequencies(it.toInt(), viewModel.highFreq) },
                        valueRange = 50f..1000f,
                        steps = 19
                    )
                    Text(stringResource(R.string.high_freq, viewModel.highFreq))
                    Slider(
                        value = viewModel.highFreq.toFloat(),
                        onValueChange = { viewModel.updateBandpassFrequencies(viewModel.lowFreq, it.toInt()) },
                        valueRange = 1000f..6000f,
                        steps = 13
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.silence_threshold) + ": ${viewModel.silenceThreshold}")
                Slider(
                    value = viewModel.silenceThreshold.toFloat(),
                    onValueChange = { viewModel.updateSilenceThreshold(it.toInt()) },
                    valueRange = 0f..2000f,
                    steps = 50
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
fun AppInfoDialog(info: AppInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_info_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                InfoRow(label = stringResource(R.string.version), value = info.appVersion)
                InfoRow(label = stringResource(R.string.android_version), value = info.androidVersion)
                InfoRow(label = stringResource(R.string.api_level), value = info.apiLevel.toString())
                InfoRow(label = stringResource(R.string.device), value = info.deviceInfo)
                
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = stringResource(R.string.ai_status), fontWeight = FontWeight.Bold)
                info.aiStatus.forEach { (name, status) ->
                    InfoRow(label = name, value = status)
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = stringResource(R.string.about_text))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = "$label:", fontWeight = FontWeight.Medium)
        Text(text = value)
    }
}

@Composable
fun SelectionContainer(content: @Composable () -> Unit) {
    content()
}
