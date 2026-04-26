package de.dervomsee.voice2txt.whisper

object WhisperLib {
    init {
        System.loadLibrary("voice2txt")
    }

    external fun initContext(modelPath: String): Long
    external fun freeContext(contextPtr: Long)
    external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray, language: String): Int
    external fun getTextSegmentCount(contextPtr: Long): Int
    external fun getTextSegment(contextPtr: Long, index: Int): String
    external fun getSystemInfo(): String
}
