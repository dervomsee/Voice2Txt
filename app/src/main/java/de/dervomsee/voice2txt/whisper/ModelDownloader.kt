package de.dervomsee.voice2txt.whisper

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ModelDownloader {
    private const val TAG = "ModelDownloader"
    private const val API_URL = "https://huggingface.co/api/models/ggerganov/whisper.cpp"

    suspend fun fetchAvailableModels(): List<WhisperModel> = withContext(Dispatchers.IO) {
        try {
            val url = URL(API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                val jsonObject = JSONObject(response.toString())
                val siblings = jsonObject.getJSONArray("siblings")
                val models = mutableListOf<WhisperModel>()

                for (i in 0 until siblings.length()) {
                    val rfilename = siblings.getJSONObject(i).getString("rfilename")
                    if (rfilename.endsWith(".bin") && rfilename.startsWith("ggml-")) {
                        models.add(WhisperModel.fromFileName(rfilename))
                    }
                }
                
                // Sort models: tiny first, then base, small, medium, large
                return@withContext models.sortedWith(compareBy<WhisperModel> { model ->
                    when {
                        model.fileName.contains("tiny") -> 0
                        model.fileName.contains("base") -> 1
                        model.fileName.contains("small") -> 2
                        model.fileName.contains("medium") -> 3
                        model.fileName.contains("large") -> 4
                        else -> 5
                    }
                }.thenBy { it.fileName })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch models from API", e)
        }
        return@withContext availableModels
    }

    fun getModelFile(context: Context, fileName: String): File {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()
        return File(modelsDir, fileName)
    }

    fun isModelDownloaded(context: Context, fileName: String): Boolean {
        return getModelFile(context, fileName).exists()
    }

    suspend fun downloadModel(context: Context, model: WhisperModel, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val outputFile = getModelFile(context, model.fileName)
        if (outputFile.exists()) return@withContext true

        try {
            val url = URL(model.url)
            val connection = url.openConnection() as HttpURLConnection
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Server returned HTTP ${connection.responseCode}")
                return@withContext false
            }

            val fileLength = connection.contentLength
            val input = connection.inputStream
            val output = outputFile.outputStream()

            val data = ByteArray(4096)
            var total: Long = 0
            var count: Int
            while (input.read(data).also { count = it } != -1) {
                total += count
                if (fileLength > 0) {
                    onProgress(total.toFloat() / fileLength)
                }
                output.write(data, 0, count)
            }

            output.flush()
            output.close()
            input.close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            if (outputFile.exists()) outputFile.delete()
            false
        }
    }
}
