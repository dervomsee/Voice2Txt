package de.dervomsee.voice2txt.summary

import kotlinx.coroutines.flow.Flow

interface SummaryEngine {
    fun summarize(text: String, prompt: String? = null): Flow<SummaryResult>
    fun isAvailable(): Boolean
}

sealed class SummaryResult {
    data class Partial(val text: String) : SummaryResult()
    data class Final(val text: String) : SummaryResult()
    data class Error(val message: String) : SummaryResult()
}
