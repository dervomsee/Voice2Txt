package de.dervomsee.voice2txt.engine

import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.concurrent.thread

class GenAISpeechEngine : SpeechEngine {
    override var name: String = "ML Kit"
        private set

    private class RecognitionStoppedException : RuntimeException("Recognition session stopped")

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

        val advancedClient = SpeechRecognition.getClient(advancedOptions)
        val basicClient = SpeechRecognition.getClient(basicOptions)

        return try {
            mapOf(
                "Advanced (GenAI)" to statusToString(advancedClient.checkStatus()),
                "Basic (Traditional)" to statusToString(basicClient.checkStatus())
            )
        } finally {
            try { advancedClient.close() } catch (_: Exception) {}
            try { basicClient.close() } catch (_: Exception) {}
        }
    }

    private fun statusToString(status: Int): String = when (status) {
        Status.UNAVAILABLE -> "Unavailable"
        Status.DOWNLOADABLE -> "Downloadable"
        Status.DOWNLOADING -> "Downloading"
        Status.AVAILABLE -> "Available"
        else -> "Unknown ($status)"
    }

    override fun transcribe(onPcmData: ((ShortArray) -> Unit) -> Unit): Flow<SpeechResult> = callbackFlow {
        // Use a simplified locale to avoid issues with some models
        val currentLocale = Locale.getDefault()

        val advancedOptions = speechRecognizerOptions {
            locale = currentLocale
            preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
        }

        val basicOptions = speechRecognizerOptions {
            locale = currentLocale
            preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
        }

        var client: SpeechRecognizer
        var status: Int

        // Determine best available engine
        val advancedClient = SpeechRecognition.getClient(advancedOptions)
        val advancedStatus = advancedClient.checkStatus()

        if (advancedStatus == Status.UNAVAILABLE) {
            try { advancedClient.close() } catch (_: Exception) {}
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
                try { 
                    withContext(NonCancellable) {
                        client.stopRecognition() 
                    }
                } catch (_: Exception) {}
                try { client.close() } catch (_: Exception) {}
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
        
        val isSessionActive = java.util.concurrent.atomic.AtomicBoolean(true)

        thread(name = "PCMStreamer") {
            try {
                // Use AutoCloseOutputStream to ensure PFD is handled correctly
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                    onPcmData { pcm ->
                        if (!isSessionActive.get()) throw RecognitionStoppedException()
                        try {
                            val byteBuffer = java.nio.ByteBuffer.allocate(pcm.size * 2)
                            byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            byteBuffer.asShortBuffer().put(pcm)
                            output.write(byteBuffer.array())
                        } catch (e: java.io.IOException) {
                            val msg = e.message ?: ""
                            if (!msg.contains("EPIPE") && !msg.contains("Broken pipe")) {
                                Log.e("GenAISpeechEngine", "Write error: $msg")
                            }
                            isSessionActive.set(false)
                            throw RecognitionStoppedException()
                        }
                    }
                }
            } catch (e: RecognitionStoppedException) {
                Log.d("GenAISpeechEngine", "Streaming stopped (session ended)")
            } catch (e: InterruptedException) {
                Log.d("GenAISpeechEngine", "Streamer thread interrupted")
            } catch (e: Exception) {
                Log.e("GenAISpeechEngine", "Pipe error: ${e.message}")
            } finally {
                isSessionActive.set(false)
                // writeSide is closed by AutoCloseOutputStream
            }
        }

        val request = speechRecognizerRequest {
            this.audioSource = AudioSource.fromPfd(readSide)
        }

        try {
            Log.d("GenAISpeechEngine", "Starting ML Kit recognition...")
            client.startRecognition(request).collect { response ->
                when (response) {
                    is SpeechRecognizerResponse.PartialTextResponse -> {
                        Log.v("GenAISpeechEngine", "Partial: ${response.text}")
                        trySend(SpeechResult.Partial(response.text))
                    }
                    is SpeechRecognizerResponse.FinalTextResponse -> {
                        Log.d("GenAISpeechEngine", "Final: ${response.text}")
                        trySend(SpeechResult.Final(response.text))
                    }
                    is SpeechRecognizerResponse.ErrorResponse -> {
                        val error = response.e
                        Log.e("GenAISpeechEngine", "ML Kit Error Response: ${error.message} (Code: ${error.errorCode})", error)
                        trySend(SpeechResult.Error("Engine Error: ${error.message} (Code: ${error.errorCode})"))
                        isSessionActive.set(false)
                        channel.close()
                    }
                    is SpeechRecognizerResponse.CompletedResponse -> {
                        Log.d("GenAISpeechEngine", "Recognition completed")
                        isSessionActive.set(false)
                        channel.close()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GenAISpeechEngine", "Exception during startRecognition collect: ${e.localizedMessage}", e)
            isSessionActive.set(false)
            if (e is kotlinx.coroutines.CancellationException) {
                throw e
            }
            trySend(SpeechResult.Error("Session failed: ${e.localizedMessage}"))
            channel.close()
        } finally {
            isSessionActive.set(false)
            // CRITICAL: We only close the readSide here, AFTER startRecognition(request).collect finishes.
            // Closing it earlier in a separate launch block (as we did before) causes INVALID_REQUEST.
            try { readSide.close() } catch (_: Exception) {}
            // writeSide is closed by the PCMStreamer thread's .use block.
        }
    }
}
