package de.dervomsee.voice2txt.summary

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.summarization.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.guava.await

class MLKitSummaryEngine(private val context: Context) : SummaryEngine {
    
    private var summarizer: Summarizer? = null

    private object FeatureStatus {
        const val UNAVAILABLE = 0
        const val DOWNLOADABLE = 1
        const val DOWNLOADING = 2
        const val AVAILABLE = 3
    }

    override fun isAvailable(): Boolean {
        return true
    }

    override fun summarize(text: String, prompt: String?): Flow<SummaryResult> = flow {
        try {
            if (summarizer == null) {
                val options = SummarizerOptions.builder(context)
                    .build()
                summarizer = Summarization.getClient(options)
            }

            Log.d("MLKitSummary", "Checking summarization feature status...")
            val status = summarizer?.checkFeatureStatus()?.await() ?: FeatureStatus.UNAVAILABLE
            Log.d("MLKitSummary", "Summarization status: $status")

            when (status) {
                FeatureStatus.AVAILABLE -> {
                    performSummarization(text)
                }
                FeatureStatus.DOWNLOADABLE -> {
                    emit(SummaryResult.Partial("Downloading summarization model..."))
                    val callback = object : DownloadCallback {
                        override fun onDownloadCompleted() { Log.d("MLKitSummary", "Download completed") }
                        override fun onDownloadFailed(e: GenAiException) { Log.e("MLKitSummary", "Download failed", e) }
                        override fun onDownloadProgress(l: Long) { Log.v("MLKitSummary", "Progress: $l") }
                        override fun onDownloadStarted(l: Long) { Log.d("MLKitSummary", "Started: $l") }
                    }
                    summarizer?.downloadFeature(callback)?.await()
                    emit(SummaryResult.Partial("Model downloaded. Starting summarization..."))
                    performSummarization(text)
                }
                FeatureStatus.DOWNLOADING -> {
                    emit(SummaryResult.Error("Model is still downloading. Please try again in a moment."))
                }
                else -> {
                    emit(SummaryResult.Error("Summarization is not supported on this device (Status: $status)"))
                }
            }
        } catch (e: Exception) {
            Log.e("MLKitSummary", "Summarization process failed", e)
            emit(SummaryResult.Error("Summarization failed: ${e.localizedMessage}"))
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<SummaryResult>.performSummarization(text: String) {
        val request = SummarizationRequest.builder(text).build()
        val result = summarizer?.runInference(request)?.await()
        
        if (result != null) {
            emit(SummaryResult.Final(result.summary))
        } else {
            emit(SummaryResult.Error("Summarization returned no result"))
        }
    }
}
