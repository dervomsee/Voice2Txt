package de.dervomsee.voice2txt.engine

import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.util.Locale
import kotlin.concurrent.thread

class GenAISpeechEngine : SpeechEngine {
    override var name: String = "ML Kit"
        private set

    private object Status {
        const val UNAVAILABLE = 0
        const val DOWNLOADABLE = 1
        const val DOWNLOADING = 2
        const val AVAILABLE = 3
    }

    /**
     * Returns the current status of the AI engines.
     */
    suspend fun getDetailedStatus(): Map<String, String> {
        val advancedOptions = speechRecognizerOptions {
            locale = Locale.getDefault()
            preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
        }
        val basicOptions = speechRecognizerOptions {
            locale = Locale.getDefault()
            preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
        }

        return mapOf(
            "Advanced (GenAI)" to statusToString(SpeechRecognition.getClient(advancedOptions).checkStatus()),
            "Basic (Traditional)" to statusToString(SpeechRecognition.getClient(basicOptions).checkStatus())
        )
    }

    private fun statusToString(status: Int): String = when (status) {
        Status.UNAVAILABLE -> "Unavailable"
        Status.DOWNLOADABLE -> "Downloadable"
        Status.DOWNLOADING -> "Downloading"
        Status.AVAILABLE -> "Available"
        else -> "Unknown ($status)"
    }

    override fun transcribe(onPcmData: ((ShortArray) -> Unit) -> Unit): Flow<SpeechResult> = callbackFlow {
        val advancedOptions = speechRecognizerOptions {
            locale = Locale.getDefault()
            preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
        }

        val basicOptions = speechRecognizerOptions {
            locale = Locale.getDefault()
            preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
        }

        var client: SpeechRecognizer
        var status: Int

        // Determine best available engine
        val advancedClient = SpeechRecognition.getClient(advancedOptions)
        val advancedStatus = advancedClient.checkStatus()

        if (advancedStatus == Status.UNAVAILABLE) {
            client = SpeechRecognition.getClient(basicOptions)
            status = client.checkStatus()
            name = "ML Kit (Basic)"
        } else {
            client = advancedClient
            status = advancedStatus
            name = "ML Kit (Advanced)"
        }

        when (status) {
            Status.AVAILABLE -> {
                startRecognitionSession(client, onPcmData)
            }
            Status.DOWNLOADABLE -> {
                client.download().collect { downloadStatus ->
                    val statusStr = downloadStatus.toString()
                    if (statusStr.contains("DownloadCompleted")) {
                        startRecognitionSession(client, onPcmData)
                    } else if (statusStr.contains("DownloadFailed")) {
                        trySend(SpeechResult.Error("Model download failed"))
                        close()
                    }
                }
            }
            Status.DOWNLOADING -> {
                trySend(SpeechResult.Error("Model is still downloading. Please try again soon."))
                close()
            }
            else -> {
                trySend(SpeechResult.Error("Speech recognition is not supported (Status: $status)"))
                close()
            }
        }

        awaitClose {
            launch {
                try { client.stopRecognition() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<SpeechResult>.startRecognitionSession(
        client: SpeechRecognizer,
        onPcmData: ((ShortArray) -> Unit) -> Unit
    ) {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        thread(name = "PCMStreamer") {
            try {
                FileOutputStream(writeSide.fileDescriptor).use { output ->
                    onPcmData { pcm ->
                        val byteBuffer = java.nio.ByteBuffer.allocate(pcm.size * 2)
                        byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        byteBuffer.asShortBuffer().put(pcm)
                        output.write(byteBuffer.array())
                    }
                }
            } catch (e: Exception) {
                Log.e("GenAISpeechEngine", "Pipe error: ${e.message}")
            } finally {
                try { writeSide.close() } catch (_: Exception) {}
            }
        }

        val request = speechRecognizerRequest {
            this.audioSource = AudioSource.fromPfd(readSide)
        }

        try {
            client.startRecognition(request).collect { response ->
                when (response) {
                    is SpeechRecognizerResponse.PartialTextResponse -> {
                        trySend(SpeechResult.Partial(response.text))
                    }
                    is SpeechRecognizerResponse.FinalTextResponse -> {
                        trySend(SpeechResult.Final(response.text))
                    }
                    is SpeechRecognizerResponse.ErrorResponse -> {
                        trySend(SpeechResult.Error("Engine Error: $response"))
                        close()
                    }
                    is SpeechRecognizerResponse.CompletedResponse -> {
                        close()
                    }
                }
            }
        } catch (e: Exception) {
            trySend(SpeechResult.Error("Session failed: ${e.localizedMessage}"))
            close()
        } finally {
            try { readSide.close() } catch (_: Exception) {}
        }
    }
}
