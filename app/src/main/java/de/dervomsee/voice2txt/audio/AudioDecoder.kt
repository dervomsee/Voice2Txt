package de.dervomsee.voice2txt.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

class AudioDecoder {
    companion object {
        private const val TAG = "AudioDecoder"
        private const val TARGET_SAMPLE_RATE = 16000
        private const val TIMEOUT_US = 10000L
    }

    suspend fun decodeToFloatArray(context: Context, uri: Uri): FloatArray? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            Log.d(TAG, "Attempting to decode URI: $uri")
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                extractor.setDataSource(fd.fileDescriptor)
            } ?: run {
                Log.e(TAG, "Failed to open file descriptor for URI: $uri")
                return@withContext null
            }

            val trackIndex = selectAudioTrack(extractor)
            if (trackIndex < 0) return@withContext null

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            val codec = MediaCodec.createDecoderByType(mime)
            
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmData = mutableListOf<Short>()
            val info = MediaCodec.BufferInfo()
            var isExtractorDone = false
            var isCodecDone = false

            while (!isCodecDone) {
                if (!isExtractorDone) {
                    val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isExtractorDone = true
                        } else {
                            codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex) ?: continue
                    val chunk = ShortArray(info.size / 2)
                    outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()[chunk]
                    pcmData.addAll(chunk.toList())
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isCodecDone = true
                    }
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            val sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            
            processPcmData(pcmData.toShortArray(), sourceSampleRate, channelCount)

        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio", e)
            null
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return i
        }
        return -1
    }

    private fun processPcmData(pcmData: ShortArray, sourceSampleRate: Int, channelCount: Int): FloatArray {
        // 1. Downmix to mono if needed
        val monoData = if (channelCount > 1) {
            val result = ShortArray(pcmData.size / channelCount)
            for (i in result.indices) {
                var sum = 0
                for (c in 0 until channelCount) {
                    sum += pcmData[(i * channelCount) + c]
                }
                result[i] = (sum / channelCount).toShort()
            }
            result
        } else {
            pcmData
        }

        // 2. Resample to 16kHz
        val resampledData = if (sourceSampleRate != TARGET_SAMPLE_RATE) {
            resampleLinear(monoData, sourceSampleRate, TARGET_SAMPLE_RATE)
        } else {
            monoData
        }

        // 3. Convert to FloatArray (-1.0 to 1.0)
        return FloatArray(resampledData.size) { i ->
            resampledData[i] / 32768.0f
        }
    }

    private fun resampleLinear(data: ShortArray, sourceRate: Int, targetRate: Int): ShortArray {
        val ratio = sourceRate.toDouble() / targetRate.toDouble()
        val targetSize = (data.size / ratio).toInt()
        val result = ShortArray(targetSize)

        for (i in 0 until targetSize) {
            val sourceIndex = i * ratio
            val index0 = sourceIndex.toInt()
            val index1 = (index0 + 1).coerceAtMost(data.size - 1)
            val fraction = sourceIndex - index0

            val v0 = data[index0].toDouble()
            val v1 = data[index1].toDouble()
            result[i] = (v0 + (v1 - v0) * fraction).toInt().toShort()
        }
        return result
    }
}
