package de.dervomsee.voice2txt.whisper

object WhisperLib {
    init {
        System.loadLibrary("voice2txt")
    }

    external fun initContext(modelPath: String, useGpu: Boolean): Long
    external fun freeContext(contextPtr: Long)
    external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray, language: String, callback: WhisperProgressCallback?): Int
    external fun getTextSegmentCount(contextPtr: Long): Int
    external fun getTextSegment(contextPtr: Long, index: Int): String
    external fun getSystemInfo(): String
    external fun abortTranscription()

    interface WhisperProgressCallback {
        fun onProgress(progress: Int)
    }
}
