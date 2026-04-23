package de.dervomsee.voice2txt.engine

import kotlinx.coroutines.flow.Flow

interface SpeechEngine {
    val name: String
    fun transcribe(onPcmData: ((ShortArray) -> Unit) -> Unit): Flow<SpeechResult>
}

sealed class SpeechResult {
    data class Partial(val text: String) : SpeechResult()
    data class Final(val text: String) : SpeechResult()
    data class Error(val message: String) : SpeechResult()
}
