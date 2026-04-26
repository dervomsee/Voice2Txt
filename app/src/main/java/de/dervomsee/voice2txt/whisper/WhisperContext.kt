package de.dervomsee.voice2txt.whisper

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WhisperContext private constructor(private var ptr: Long) {
    
    companion object {
        private const val TAG = "WhisperContext"

        fun createContextFromFile(modelPath: String): WhisperContext {
            val ptr = WhisperLib.initContext(modelPath)
            if (ptr == 0L) {
                throw RuntimeException("Failed to initialize Whisper context from $modelPath")
            }
            return WhisperContext(ptr)
        }

        fun getSystemInfo(): String = WhisperLib.getSystemInfo()
    }

    suspend fun transcribeData(data: FloatArray, numThreads: Int = 4): String = withContext(Dispatchers.Default) {
        if (ptr == 0L) return@withContext ""

        val result = WhisperLib.fullTranscribe(ptr, numThreads, data)
        if (result != 0) {
            Log.e(TAG, "Transcription failed with error code $result")
            return@withContext ""
        }

        val segmentCount = WhisperLib.getTextSegmentCount(ptr)
        val sb = StringBuilder()
        for (i in 0 until segmentCount) {
            sb.append(WhisperLib.getTextSegment(ptr, i))
        }
        sb.toString()
    }

    fun release() {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0L
        }
    }
}
