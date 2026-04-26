package de.dervomsee.voice2txt.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.dervomsee.voice2txt.audio.AudioRecorder
import de.dervomsee.voice2txt.whisper.ModelDownloader
import de.dervomsee.voice2txt.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    var transcription by mutableStateOf("")
        private set

    var isRecording by mutableStateOf(false)
        private set

    var isDownloading by mutableStateOf(false)
        private set

    var downloadProgress by mutableStateOf(0f)
        private set

    var statusMessage by mutableStateOf("")
        private set

    private var whisperContext: WhisperContext? = null
    private val audioRecorder = AudioRecorder()
    private val recordedData = mutableListOf<Float>()

    init {
        checkModel()
    }

    private fun checkModel() {
        if (!ModelDownloader.isModelDownloaded(getApplication())) {
            statusMessage = "Model not found. Download required."
        } else {
            loadModel()
        }
    }

    fun downloadModel() {
        viewModelScope.launch {
            isDownloading = true
            statusMessage = "Downloading model..."
            val success = ModelDownloader.downloadModel(getApplication()) { progress ->
                downloadProgress = progress
            }
            isDownloading = false
            if (success) {
                statusMessage = "Download complete."
                loadModel()
            } else {
                statusMessage = "Download failed."
            }
        }
    }

    private fun loadModel() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                statusMessage = "Loading model..."
                val modelFile = ModelDownloader.getModelFile(getApplication())
                whisperContext = WhisperContext.createContextFromFile(modelFile.absolutePath)
                statusMessage = "Model loaded. Ready to record."
            } catch (e: Exception) {
                statusMessage = "Failed to load model: ${e.localizedMessage}"
            }
        }
    }

    fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (whisperContext == null) {
            statusMessage = "Please load model first."
            return
        }
        viewModelScope.launch {
            recordedData.clear()
            isRecording = true
            statusMessage = "Recording..."
            audioRecorder.startRecording { buffer ->
                recordedData.addAll(buffer.toList())
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        statusMessage = "Processing..."
        audioRecorder.stopRecording()
        
        viewModelScope.launch {
            val data = recordedData.toFloatArray()
            if (data.isNotEmpty()) {
                val result = whisperContext?.transcribeData(data) ?: ""
                transcription = result
                statusMessage = "Transcription finished."
            } else {
                statusMessage = "No audio recorded."
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        whisperContext?.release()
    }
}
