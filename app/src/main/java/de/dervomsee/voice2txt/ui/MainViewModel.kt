package de.dervomsee.voice2txt.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.dervomsee.voice2txt.audio.AudioConverter
import de.dervomsee.voice2txt.download.DownloadStatus
import de.dervomsee.voice2txt.download.ModelDownloader
import de.dervomsee.voice2txt.engine.GenAISpeechEngine
import de.dervomsee.voice2txt.engine.SpeechEngine
import de.dervomsee.voice2txt.engine.SpeechResult
import de.dervomsee.voice2txt.engine.WhisperSpeechEngine
import de.dervomsee.voice2txt.summary.MLKitSummaryEngine
import de.dervomsee.voice2txt.summary.SummaryEngine
import de.dervomsee.voice2txt.summary.SummaryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AppInfo(
    val appVersion: String,
    val androidVersion: String,
    val apiLevel: Int,
    val deviceInfo: String,
    val aiStatus: Map<String, String>
)

enum class DownloadTarget {
    WHISPER
}

data class AiModel(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val sizeLabel: String,
    val fileName: String,
    val target: DownloadTarget,
    val secondaryUrl: String? = null,
    val secondaryFileName: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("voice2txt_prefs", Context.MODE_PRIVATE)
    private val audioConverter = AudioConverter(application)
    private val genAiEngine = GenAISpeechEngine()
    private val whisperEngine = WhisperSpeechEngine(application)
    private val summaryEngine = MLKitSummaryEngine(application)
    private val modelDownloader = ModelDownloader(application)

    // Models
    val whisperModels = listOf(
        AiModel(
            "whisper_tiny", "Whisper Tiny (EN/Multi)", "Fastest offline model",
            "https://huggingface.co/cik009/whisper/resolve/main/whisper-tiny.tflite",
            "~42MB", "whisper_tiny.tflite", DownloadTarget.WHISPER,
            "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/filters_vocab_multilingual.bin", "filters_vocab_gen.bin"
        ),
        AiModel(
            "whisper_base_de", "Whisper Base (DE)", "Better for German",
            "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/whisper-base.de.tflite",
            "~141MB", "whisper_base_de.tflite", DownloadTarget.WHISPER,
            "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/filters_vocab_multilingual.bin", "filters_vocab_gen.bin"
        )
    )

    val engines = listOf(genAiEngine, whisperEngine)

    var currentEngine by mutableStateOf<SpeechEngine>(engines[0])
        private set

    var selectedWhisperModel by mutableStateOf(whisperModels[0])
        private set

    var transcriptionText by mutableStateOf("")
        private set
        
    var summaryText by mutableStateOf("")
        private set

    var isProcessing by mutableStateOf(false)
        private set
        
    var isSummarizing by mutableStateOf(false)
        private set

    var errorText by mutableStateOf<String?>(null)
        private set

    var appInfo by mutableStateOf<AppInfo?>(null)
        private set
        
    var showDownloadDialog by mutableStateOf<AiModel?>(null)
        private set
        
    var downloadProgress by mutableStateOf<Float?>(null)
        private set

    var showModelManagementDialog by mutableStateOf(false)
        private set

    init {
        loadSettings()
    }

    private fun loadSettings() {
        try {
            val engineName = prefs.getString("selected_engine", genAiEngine.name)
            currentEngine = engines.find { it.name == engineName } ?: engines[0]

            val whisperModelId = prefs.getString("selected_whisper_model", whisperModels[0].id)
            selectedWhisperModel = whisperModels.find { it.id == whisperModelId } ?: whisperModels[0]
            whisperEngine.setModel(selectedWhisperModel.fileName)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to load settings", e)
        }
    }

    private fun saveSettings() {
        prefs.edit().apply {
            putString("selected_engine", currentEngine.name)
            putString("selected_whisper_model", selectedWhisperModel.id)
            apply()
        }
    }

    fun openModelManagement() { showModelManagementDialog = true }
    fun dismissModelManagement() { showModelManagementDialog = false }

    fun selectEngine(engine: SpeechEngine) {
        currentEngine = engine
        saveSettings()
    }

    fun selectWhisperModel(model: AiModel) {
        selectedWhisperModel = model
        whisperEngine.setModel(model.fileName)
        saveSettings()
    }

    fun clearModels() {
        modelDownloader.clearAllModels()
        loadAppInfo()
    }

    fun loadAppInfo() {
        viewModelScope.launch {
            val context = getApplication<Application>().applicationContext
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val aiStatus = mutableMapOf<String, String>()
            aiStatus.putAll(genAiEngine.getDetailedStatus())
            aiStatus["Whisper (${selectedWhisperModel.name})"] = if (whisperEngine.isAvailable()) "Available" else "Missing"
            
            appInfo = AppInfo(
                appVersion = "${packageInfo.versionName} (${packageInfo.longVersionCode})",
                androidVersion = Build.VERSION.RELEASE,
                apiLevel = Build.VERSION.SDK_INT,
                deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}",
                aiStatus = aiStatus
            )
        }
    }

    fun dismissAppInfo() { appInfo = null }

    fun isModelLocallyAvailable(model: AiModel): Boolean {
        val modelFile = File(getApplication<Application>().filesDir, "models/${model.fileName}")
        if (!modelFile.exists()) return false
        
        if (model.secondaryFileName != null) {
            val secFile = File(getApplication<Application>().filesDir, "models/${model.secondaryFileName}")
            if (!secFile.exists()) return false
        }
        
        return true
    }

    fun processAudio(uri: Uri) {
        if (currentEngine is WhisperSpeechEngine && !(currentEngine as WhisperSpeechEngine).isAvailable()) {
            showDownloadDialog = selectedWhisperModel
            pendingProcessUri = uri
            return
        }

        viewModelScope.launch {
            isProcessing = true
            errorText = null
            transcriptionText = ""
            summaryText = ""

            try {
                var finalizedText = ""
                withContext(Dispatchers.IO) {
                    currentEngine.transcribe { onData ->
                        audioConverter.convertToPcm(uri, onData)
                    }.collect { result ->
                        withContext(Dispatchers.Main) {
                            when (result) {
                                is SpeechResult.Partial -> transcriptionText = finalizedText + result.text
                                is SpeechResult.Final -> {
                                    finalizedText += result.text + " "
                                    transcriptionText = finalizedText
                                }
                                is SpeechResult.Error -> {
                                    errorText = result.message
                                    isProcessing = false
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                errorText = e.localizedMessage ?: "Processing error"
            } finally {
                isProcessing = false
            }
        }
    }
    
    private var pendingProcessUri: Uri? = null

    fun summarizeTranscription() {
        if (isSummarizing) return
        
        viewModelScope.launch {
            isSummarizing = true
            summaryText = ""
            try {
                summaryEngine.summarize(transcriptionText, null).collect { result ->
                    when (result) {
                        is SummaryResult.Partial -> {
                            summaryText = result.text
                        }
                        is SummaryResult.Final -> {
                            summaryText = result.text
                            isSummarizing = false
                        }
                        is SummaryResult.Error -> {
                            errorText = result.message
                            isSummarizing = false
                        }
                    }
                }
            } catch (e: Exception) {
                errorText = e.message
                isSummarizing = false
            }
        }
    }

    fun startModelDownload(model: AiModel) {
        Log.d("MainViewModel", "Starting download: ${model.name}")
        showDownloadDialog = null
        downloadProgress = 0f
        
        viewModelScope.launch {
            // Main model file
            modelDownloader.downloadModel(model.url, "temp_${model.fileName}").collect { status ->
                when (status) {
                    is DownloadStatus.Success -> {
                        withContext(Dispatchers.IO) {
                            try {
                                modelDownloader.moveModelToPrivateStorage(status.uri, "models", model.fileName)
                                
                                // Secondary file (e.g. filters/vocab)
                                if (model.secondaryUrl != null && model.secondaryFileName != null) {
                                    modelDownloader.downloadModel(model.secondaryUrl, "temp_${model.secondaryFileName}").collect { secStatus ->
                                        if (secStatus is DownloadStatus.Success) {
                                            modelDownloader.moveModelToPrivateStorage(secStatus.uri, "models", model.secondaryFileName)
                                            finishDownload(model)
                                        }
                                    }
                                } else {
                                    finishDownload(model)
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { errorText = "Storage error: ${e.message}" }
                            }
                        }
                    }
                    is DownloadStatus.Progress -> downloadProgress = status.percentage / 100f
                    is DownloadStatus.Error -> {
                        errorText = status.message
                        downloadProgress = null
                    }
                }
            }
        }
    }

    private fun finishDownload(model: AiModel) {
        downloadProgress = null
        if (model.target == DownloadTarget.WHISPER && pendingProcessUri != null) {
            processAudio(pendingProcessUri!!)
            pendingProcessUri = null
        }
    }
    
    fun dismissDownloadDialog() { showDownloadDialog = null }
}
