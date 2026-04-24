package de.dervomsee.voice2txt.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

sealed class DownloadStatus {
    data class Success(val uri: Uri) : DownloadStatus()
    data class Progress(val percentage: Int) : DownloadStatus()
    data class Error(val message: String) : DownloadStatus()
}

class ModelDownloader(private val context: Context) {

    fun downloadModel(url: String, fileName: String): Flow<DownloadStatus> = callbackFlow {
        Log.d("ModelDownloader", "Initiating download from: $url as $fileName")
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading AI Model")
            .setDescription("Preparing engine...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalFilesDir(context, null, fileName)

        val downloadId = try {
            downloadManager.enqueue(request)
        } catch (e: Exception) {
            Log.e("ModelDownloader", "Failed to enqueue download", e)
            trySend(DownloadStatus.Error("Failed to start download: ${e.message}"))
            close()
            return@callbackFlow
        }
        
        Log.d("ModelDownloader", "Download enqueued with ID: $downloadId")

        val pollerJob = launch(Dispatchers.IO) {
            while (isActive) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val bytesDownloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val bytesTotalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    
                    val bytesDownloaded = if (bytesDownloadedIdx != -1) cursor.getLong(bytesDownloadedIdx) else 0L
                    val bytesTotal = if (bytesTotalIdx != -1) cursor.getLong(bytesTotalIdx) else 0L
                    
                    if (bytesTotal > 0) {
                        val percentage = ((bytesDownloaded * 100) / bytesTotal).toInt()
                        trySend(DownloadStatus.Progress(percentage))
                    }
                    
                    val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (statusIdx != -1) {
                        val status = cursor.getInt(statusIdx)
                        if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                            cursor.close()
                            break 
                        }
                    }
                }
                cursor?.close()
                delay(1000)
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val localUri = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))
                            trySend(DownloadStatus.Success(Uri.parse(localUri)))
                        } else {
                            val reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON))
                            trySend(DownloadStatus.Error("Download failed (Code $reason)"))
                        }
                    }
                    cursor?.close()
                    close()
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }

        awaitClose {
            pollerJob.cancel()
            try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
        }
    }

    suspend fun moveModelToPrivateStorage(uri: Uri, targetSubDir: String, targetFileName: String): File = withContext(Dispatchers.IO) {
        val modelsDir = File(context.filesDir, targetSubDir)
        if (!modelsDir.exists()) modelsDir.mkdirs()
        
        val targetFile = File(modelsDir, targetFileName)
        
        val header = ByteArray(8)
        try {
            context.contentResolver.openInputStream(uri)?.use { it.read(header) }
        } catch (e: Exception) {
            Log.e("ModelDownloader", "Failed to read header", e)
        }
        
        val isStandardZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        val isPaddedZip = header[4] == 0x50.toByte() && header[5] == 0x4B.toByte()

        if (isStandardZip || isPaddedZip) {
            Log.d("ModelDownloader", "ZIP bundle detected (Padded: $isPaddedZip). Searching for model...")
            
            try {
                context.contentResolver.openInputStream(uri)?.use { rawInput ->
                    if (isPaddedZip) rawInput.read(ByteArray(4)) // Skip 4 bytes
                    
                    ZipInputStream(rawInput).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            Log.d("ModelDownloader", "Checking ZIP entry: '${entry.name}' (${entry.size} bytes)")
                            
                            // Match criteria: 
                            // 1. Files ending with .tflite or .litertlm
                            // 2. The specific Google internal name 'TF_LITE_PREFILL_DECODE'
                            // 3. Or just the largest file in the ZIP (fallback)
                            val isModelFile = entry.name.contains(".tflite", ignoreCase = true) || 
                                            entry.name.contains(".litertlm", ignoreCase = true) ||
                                            entry.name == "TF_LITE_PREFILL_DECODE"

                            if (isModelFile && !entry.isDirectory) {
                                Log.d("ModelDownloader", "Found model entry: ${entry.name}. Extracting...")
                                FileOutputStream(targetFile).use { fos ->
                                    val buffer = ByteArray(16384)
                                    var read = zis.read(buffer)
                                    while (read != -1) {
                                        fos.write(buffer, 0, read)
                                        read = zis.read(buffer)
                                    }
                                }
                                Log.d("ModelDownloader", "Extraction complete: ${targetFile.length()} bytes")
                                return@withContext targetFile
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ModelDownloader", "Extraction failed", e)
                if (targetFile.exists()) targetFile.delete()
                throw e
            }
            throw Exception("No model file found inside ZIP (searched for .tflite, .litertlm or TF_LITE_PREFILL_DECODE)")
        } else {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Could not open input stream")
            Log.d("ModelDownloader", "Stored flat file: ${targetFile.length()} bytes")
        }

        return@withContext targetFile
    }

    fun clearAllModels() {
        val modelsDir = File(context.filesDir, "models")
        if (modelsDir.exists()) {
            modelsDir.deleteRecursively()
            Log.d("ModelDownloader", "All models cleared.")
        }
    }
}
