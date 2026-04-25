package de.dervomsee.voice2txt.ui

import android.app.Application
import android.net.Uri
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.dervomsee.voice2txt.audio.AudioConverter
import de.dervomsee.voice2txt.data.SettingsRepository
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

    private val settingsRepository = SettingsRepository(application)
    private val audioConverter = AudioConverter(application, settingsRepository.silenceThreshold).apply {
        setBandpassEnabled(settingsRepository.enableBandpass)
        setBandpassFrequencies(settingsRepository.lowFreq, settingsRepository.highFreq)
    }
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

    var debugPlayAudio by mutableStateOf(settingsRepository.debugPlayAudio)
        private set

    var silenceThreshold by mutableIntStateOf(settingsRepository.silenceThreshold)
        private set

    var enableBandpass by mutableStateOf(settingsRepository.enableBandpass)
        private set

    var lowFreq by mutableIntStateOf(settingsRepository.lowFreq)
        private set

    var highFreq by mutableIntStateOf(settingsRepository.highFreq)
        private set

    var audioStats by mutableStateOf<AudioStats?>(null)
        private set

    private var debugAudioTrack: AudioTrack? = null

    data class AudioStats(
        val min: Int,
        val max: Int,
        val peak: Int
    )

    fun toggleDebugPlayAudio() {
        debugPlayAudio = !debugPlayAudio
        settingsRepository.debugPlayAudio = debugPlayAudio
    }

    fun updateSilenceThreshold(threshold: Int) {
        silenceThreshold = threshold
        settingsRepository.silenceThreshold = threshold
        audioConverter.setSilenceThreshold(threshold)
    }

    fun toggleBandpass() {
        enableBandpass = !enableBandpass
        settingsRepository.enableBandpass = enableBandpass
        audioConverter.setBandpassEnabled(enableBandpass)
    }

    fun updateBandpassFrequencies(low: Int, high: Int) {
        lowFreq = low
        highFreq = high
        settingsRepository.lowFreq = low
        settingsRepository.highFreq = high
        audioConverter.setBandpassFrequencies(low, high)
    }

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
                audioStats = null
                if (debugPlayAudio) {
                    initDebugAudioTrack()
                }
                withContext(Dispatchers.IO) {
                    currentEngine.transcribe { onData ->
                        audioConverter.convertToPcm(uri) { pcm ->
                            if (pcm.isNotEmpty()) {
                                updateStats(pcm)
                                if (debugPlayAudio) {
                                    debugAudioTrack?.write(pcm, 0, pcm.size)
                                }
                            }
                            onData(pcm)
                        }
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
                releaseDebugAudioTrack()
            }
        }
    }

    private fun updateStats(pcm: ShortArray) {
        var min = 0
        var max = 0
        var sumSq = 0.0
        for (s in pcm) {
            val v = s.toInt()
            if (v < min) min = v
            if (v > max) max = v
            sumSq += v.toDouble() * v.toDouble()
        }
        val peak = kotlin.math.max(kotlin.math.abs(min), kotlin.math.abs(max))

        // Update with the most "extreme" values seen so far for tuning
        val current = audioStats
        audioStats = if (current == null) {
            AudioStats(min, max, peak)
        } else {
            AudioStats(
                kotlin.math.min(current.min, min),
                kotlin.math.max(current.max, max),
                peak // Peak is current for this chunk
            )
        }
    }

    private fun initDebugAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        debugAudioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        debugAudioTrack?.play()
    }

    private fun releaseDebugAudioTrack() {
        debugAudioTrack?.apply {
            try {
                stop()
                release()
            } catch (_: Exception) {
                // ignore
            }
        }
        debugAudioTrack = null
    }

    override fun onCleared() {
        super.onCleared()
        releaseDebugAudioTrack()
    }
}
