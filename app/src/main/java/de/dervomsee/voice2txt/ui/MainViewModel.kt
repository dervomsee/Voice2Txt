package de.dervomsee.voice2txt.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.dervomsee.voice2txt.R
import de.dervomsee.voice2txt.audio.AudioRecorder
import de.dervomsee.voice2txt.settings.SettingsManager
import de.dervomsee.voice2txt.whisper.ModelDownloader
import de.dervomsee.voice2txt.whisper.WhisperContext
import de.dervomsee.voice2txt.whisper.WhisperModel
import de.dervomsee.voice2txt.whisper.availableModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

sealed class Screen {
    data object Main : Screen()
    data object Settings : Screen()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsManager = SettingsManager(application)

    var currentScreen by mutableStateOf<Screen>(Screen.Main)
        private set

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

    var selectedLanguage by mutableStateOf("de")
        private set

    var useGpu by mutableStateOf(false)
        private set

    var selectedModel by mutableStateOf(availableModels[1]) // Default to Tiny Q8_0
        private set

    var availableModelsList by mutableStateOf(availableModels)
        private set

    var isLoadingModels by mutableStateOf(false)
        private set

    var lastPerformanceRtf by mutableStateOf(0f)
        private set

    private var whisperContext: WhisperContext? = null
    private val audioRecorder = AudioRecorder()
    private val recordedData = mutableListOf<Float>()
    
    // Using 6 threads for better balance on Pixel 8 (Tensor G3)
    private val whisperThreads = 6

    init {
        viewModelScope.launch {
            selectedLanguage = settingsManager.selectedLanguage.first()
            useGpu = settingsManager.useGpu.first()
            val modelFile = settingsManager.selectedModelFile.first()
            selectedModel = availableModels.find { it.fileName == modelFile } ?: availableModels[1]
            
            refreshModels()
            checkModel()
        }
    }

    fun navigateTo(screen: Screen) {
        currentScreen = screen
    }

    fun refreshModels() {
        viewModelScope.launch {
            isLoadingModels = true
            availableModelsList = ModelDownloader.fetchAvailableModels()
            isLoadingModels = false
        }
    }

    fun selectModel(model: WhisperModel) {
        selectedModel = model
        viewModelScope.launch {
            settingsManager.setSelectedModelFile(model.fileName)
        }
        checkModel()
    }

    private fun checkModel() {
        if (!ModelDownloader.isModelDownloaded(getApplication(), selectedModel.fileName)) {
            statusMessage = getApplication<Application>().getString(R.string.model_required)
            whisperContext?.release()
            whisperContext = null
        } else {
            loadModel()
        }
    }

    fun downloadModel() {
        if (isDownloading) return
        viewModelScope.launch {
            isDownloading = true
            statusMessage = getApplication<Application>().getString(R.string.status_label, "Downloading...")
            val success = ModelDownloader.downloadModel(getApplication(), selectedModel) { progress ->
                downloadProgress = progress
            }
            isDownloading = false
            if (success) {
                loadModel()
            } else {
                statusMessage = "Download failed."
            }
        }
    }

    private fun loadModel() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                statusMessage = getApplication<Application>().getString(R.string.model_loading, selectedModel.name)
                whisperContext?.release()
                val modelFile = ModelDownloader.getModelFile(getApplication(), selectedModel.fileName)
                whisperContext = WhisperContext.createContextFromFile(modelFile.absolutePath, useGpu)
                statusMessage = getApplication<Application>().getString(R.string.status_label, "Ready")
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

    fun setLanguage(lang: String) {
        selectedLanguage = lang
        viewModelScope.launch {
            settingsManager.setSelectedLanguage(lang)
        }
    }

    fun toggleGpu(enabled: Boolean) {
        useGpu = enabled
        viewModelScope.launch {
            settingsManager.setUseGpu(enabled)
        }
        loadModel()
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
                var result = ""
                val durationMs = (data.size.toFloat() / 16000f) * 1000f
                
                val inferenceTimeMs = measureTimeMillis {
                    result = whisperContext?.transcribeData(data, selectedLanguage, whisperThreads) ?: ""
                }
                
                if (inferenceTimeMs > 0) {
                    lastPerformanceRtf = durationMs / inferenceTimeMs.toFloat()
                }

                transcription = result
                statusMessage = getApplication<Application>().getString(R.string.transcription_finished, lastPerformanceRtf)
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
