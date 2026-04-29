package de.dervomsee.voice2txt.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder {
    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null

    @SuppressLint("MissingPermission")
    suspend fun startRecording(onBufferAvailable: (FloatArray) -> Unit) = withContext(Dispatchers.IO) {
        if (isRecording.get()) return@withContext
        isRecording.set(true)

        recordingThread = Thread({
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize * 2
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                isRecording.set(false)
                return@Thread
            }

            audioRecord.startRecording()
            val buffer = ShortArray(minBufferSize)
            
            while (isRecording.get()) {
                val readCount = audioRecord.read(buffer, 0, buffer.size)
                if (readCount > 0) {
                    val floatBuffer = FloatArray(readCount)
                    for (i in 0 until readCount) {
                        floatBuffer[i] = buffer[i] / 32768.0f
                    }
                    onBufferAvailable(floatBuffer)
                }
            }

            audioRecord.stop()
            audioRecord.release()
        }, "AudioRecorderThread")

        recordingThread?.start()
    }

    fun stopRecording() {
        isRecording.set(false)
        recordingThread?.join(1000)
        recordingThread = null
    }
}
