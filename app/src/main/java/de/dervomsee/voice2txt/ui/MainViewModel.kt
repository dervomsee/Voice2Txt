package de.dervomsee.voice2txt.ui

import android.app.Application
import android.util.Log
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
    data object Benchmark : Screen()
}

data class BenchmarkResult(
    val modelName: String,
    val isGpu: Boolean,
    val inferenceTimeMs: Long,
    val rtf: Float,
    val transcription: String
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsManager = SettingsManager(application)

    var currentScreen by mutableStateOf<Screen>(Screen.Main)
        private set

    var transcription by mutableStateOf("")
        private set

    var isRecording by mutableStateOf(false)
        private set

    var isTranscribing by mutableStateOf(false)
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

    var benchmarkResults by mutableStateOf<List<BenchmarkResult>>(emptyList())
        private set

    var isBenchmarking by mutableStateOf(false)
        private set

    var benchmarkStatus by mutableStateOf("")
        private set

    var benchmarkSampleData by mutableStateOf<FloatArray?>(null)
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

    private var isAborted = false

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

    fun deleteModel(model: WhisperModel) {
        if (ModelDownloader.deleteModel(getApplication(), model.fileName)) {
            if (selectedModel.fileName == model.fileName) {
                whisperContext?.release()
                whisperContext = null
                statusMessage = getApplication<Application>().getString(R.string.model_required)
            }
            // Trigger UI update
            val currentModels = availableModelsList
            availableModelsList = emptyList()
            availableModelsList = currentModels
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
        } else if (isTranscribing) {
            stopTranscription()
        } else {
            startRecording()
        }
    }

    fun stopTranscription() {
        isAborted = true
        whisperContext?.stopTranscription()
        statusMessage = getApplication<Application>().getString(R.string.transcription_aborted)
    }

    fun toggleBenchmarkRecording() {
        if (isRecording) {
            stopBenchmarkRecording()
        } else {
            startBenchmarkRecording()
        }
    }

    private fun startBenchmarkRecording() {
        viewModelScope.launch {
            recordedData.clear()
            isRecording = true
            audioRecorder.startRecording { buffer ->
                recordedData.addAll(buffer.toList())
            }
        }
    }

    private fun stopBenchmarkRecording() {
        isRecording = false
        audioRecorder.stopRecording()
        benchmarkSampleData = recordedData.toFloatArray()
    }

    fun runBenchmark() {
        if (benchmarkSampleData == null) return
        
        viewModelScope.launch(Dispatchers.IO) {
            isBenchmarking = true
            benchmarkResults = emptyList()
            
            val downloadedModels = availableModelsList.filter { 
                ModelDownloader.isModelDownloaded(getApplication(), it.fileName) 
            }
            
            if (downloadedModels.isEmpty()) {
                benchmarkStatus = getApplication<Application>().getString(R.string.benchmark_no_models)
                isBenchmarking = false
                return@launch
            }

            // Save current state to restore later
            val previousModel = selectedModel
            val previousGpu = useGpu

            try {
                for (model in downloadedModels) {
                    // Benchmark CPU
                    benchmarkModel(model, false)
                    // Benchmark GPU
                    benchmarkModel(model, true)
                }
            } finally {
                // Restore original model
                selectedModel = previousModel
                useGpu = previousGpu
                loadModel()
                isBenchmarking = false
                benchmarkStatus = ""
            }
        }
    }

    private suspend fun benchmarkModel(model: WhisperModel, gpu: Boolean) {
        val deviceStr = if (gpu) "GPU" else "CPU"
        benchmarkStatus = getApplication<Application>().getString(R.string.benchmark_processing, model.name, deviceStr)
        
        try {
            val modelFile = ModelDownloader.getModelFile(getApplication(), model.fileName)
            val context = WhisperContext.createContextFromFile(modelFile.absolutePath, gpu)
            
            val data = benchmarkSampleData ?: return
            val durationMs = (data.size.toFloat() / 16000f) * 1000f
            
            var result = ""
            val inferenceTimeMs = measureTimeMillis {
                result = context.transcribeData(data, selectedLanguage, whisperThreads)
            }
            
            val rtf = if (inferenceTimeMs > 0) durationMs / inferenceTimeMs.toFloat() else 0f
            
            val benchmarkResult = BenchmarkResult(
                modelName = model.name,
                isGpu = gpu,
                inferenceTimeMs = inferenceTimeMs,
                rtf = rtf,
                transcription = result
            )
            
            withContext(Dispatchers.Main) {
                benchmarkResults = benchmarkResults + benchmarkResult
            }
            
            context.release()
        } catch (e: Exception) {
            Log.e("Benchmark", "Failed to benchmark ${model.name} on $deviceStr", e)
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
                isTranscribing = true
                isAborted = false
                var result = ""
                val durationMs = (data.size.toFloat() / 16000f) * 1000f
                
                val inferenceTimeMs = measureTimeMillis {
                    result = whisperContext?.transcribeData(data, selectedLanguage, whisperThreads) ?: ""
                }
                
                if (inferenceTimeMs > 0) {
                    lastPerformanceRtf = durationMs / inferenceTimeMs.toFloat()
                }

                transcription = result
                isTranscribing = false
                
                if (!isAborted) {
                    statusMessage = getApplication<Application>().getString(R.string.transcription_finished, lastPerformanceRtf)
                } else {
                    lastPerformanceRtf = 0f
                }
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
