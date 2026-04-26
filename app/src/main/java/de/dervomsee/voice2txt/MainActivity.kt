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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.dervomsee.voice2txt.ui.MainViewModel
import de.dervomsee.voice2txt.ui.theme.Voice2TxtTheme

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
                text = stringResource(R.string.status_label, viewModel.statusMessage),
                style = MaterialTheme.typography.bodyMedium
            )

            if (viewModel.isDownloading) {
                LinearProgressIndicator(
                    progress = { viewModel.downloadProgress },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
                Text(text = stringResource(R.string.downloading_progress, (viewModel.downloadProgress * 100).toInt()))
            }

            Spacer(modifier = Modifier.height(16.dp))

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
                if (viewModel.statusMessage.contains("Download required", ignoreCase = true)) {
                    Button(onClick = { viewModel.downloadModel() }) {
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
}
