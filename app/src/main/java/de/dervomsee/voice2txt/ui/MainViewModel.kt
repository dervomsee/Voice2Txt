package de.dervomsee.voice2txt.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.dervomsee.voice2txt.audio.AudioConverter
import de.dervomsee.voice2txt.engine.GenAISpeechEngine
import de.dervomsee.voice2txt.engine.SpeechEngine
import de.dervomsee.voice2txt.engine.SpeechResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(
    val appVersion: String,
    val androidVersion: String,
    val apiLevel: Int,
    val deviceInfo: String,
    val aiStatus: Map<String, String>
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val audioConverter = AudioConverter(application)
    private val genAiEngine = GenAISpeechEngine()

    val engines = listOf(genAiEngine)

    var currentEngine by mutableStateOf<SpeechEngine>(engines[0])
        private set

    var transcriptionText by mutableStateOf("")
        private set

    var isProcessing by mutableStateOf(false)
        private set

    var errorText by mutableStateOf<String?>(null)
        private set

    var appInfo by mutableStateOf<AppInfo?>(null)
        private set

    fun selectEngine(engine: SpeechEngine) {
        currentEngine = engine
    }

    fun loadAppInfo() {
        viewModelScope.launch {
            val context = getApplication<Application>().applicationContext
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val aiStatus = genAiEngine.getDetailedStatus()
            
            appInfo = AppInfo(
                appVersion = "${packageInfo.versionName} (${packageInfo.longVersionCode})",
                androidVersion = Build.VERSION.RELEASE,
                apiLevel = Build.VERSION.SDK_INT,
                deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}",
                aiStatus = aiStatus
            )
        }
    }

    fun dismissAppInfo() {
        appInfo = null
    }

    fun processAudio(uri: Uri) {
        viewModelScope.launch {
            isProcessing = true
            errorText = null
            transcriptionText = ""

            try {
                var finalizedText = ""
                withContext(Dispatchers.IO) {
                    currentEngine.transcribe { onData ->
                        audioConverter.convertToPcm(uri, onData)
                    }.collect { result ->
                        withContext(Dispatchers.Main) {
                            when (result) {
                                is SpeechResult.Partial -> {
                                    transcriptionText = finalizedText + result.text
                                }
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
}
