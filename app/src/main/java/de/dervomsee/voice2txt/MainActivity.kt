package de.dervomsee.voice2txt

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.dervomsee.voice2txt.ui.MainViewModel
import de.dervomsee.voice2txt.ui.theme.Voice2TxtTheme
import de.dervomsee.voice2txt.whisper.ModelDownloader
import de.dervomsee.voice2txt.whisper.availableModels

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Voice2TxtTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    var showModelDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.toggleRecording()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
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

            if (viewModel.isDownloading) {
                LinearProgressIndicator(
                    progress = { viewModel.downloadProgress },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
                Text(text = stringResource(R.string.downloading_progress, (viewModel.downloadProgress * 100).toInt()))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Model Selection Button
            Button(onClick = { showModelDialog = true }) {
                Text(stringResource(R.string.change_model) + ": ${viewModel.selectedModel.name}")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.lang_de))
                Switch(
                    checked = viewModel.selectedLanguage == "en",
                    onCheckedChange = { isEn ->
                        viewModel.setLanguage(if (isEn) "en" else "de")
                    },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Text(stringResource(R.string.lang_en))
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.gpu_label))
                Switch(
                    checked = viewModel.useGpu,
                    onCheckedChange = { viewModel.toggleGpu(it) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val isDownloaded = ModelDownloader.isModelDownloaded(context, viewModel.selectedModel.fileName)
                
                if (!isDownloaded) {
                    Button(onClick = { viewModel.downloadModel() }, enabled = !viewModel.isDownloading) {
                        Text(stringResource(R.string.download_model))
                    }
                } else {
                    Button(
                        onClick = {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        enabled = !viewModel.isDownloading
                    ) {
                        Text(
                            if (viewModel.isRecording) stringResource(R.string.stop_recording)
                            else stringResource(R.string.start_recording)
                        )
                    }
                }
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
