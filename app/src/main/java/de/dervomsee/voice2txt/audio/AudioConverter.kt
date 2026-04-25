package de.dervomsee.voice2txt.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.sqrt

class AudioConverter(private val context: Context, private var silenceThreshold: Int = 500) {

    private var bandpassFilter: BandpassFilter? = null
    private var isBandpassEnabled = true
    private var lowFreq = 300.0
    private var highFreq = 3000.0

    fun setSilenceThreshold(threshold: Int) {
        silenceThreshold = threshold
    }

    fun setBandpassEnabled(enabled: Boolean) {
        isBandpassEnabled = enabled
    }

    fun setBandpassFrequencies(low: Int, high: Int) {
        if (low.toDouble() != lowFreq || high.toDouble() != highFreq) {
            lowFreq = low.toDouble()
            highFreq = high.toDouble()
            bandpassFilter = null // Force re-creation with new frequencies
        }
    }

    /**
     * Converts audio from the given [uri] to 16kHz Mono 16-bit PCM.
     * Uses a simple VAD to trim leading silence to satisfy ML Kit requirements.
     */
    fun convertToPcm(uri: Uri, onData: (ShortArray) -> Unit) {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: return
        } catch (e: Exception) {
            Log.e("AudioConverter", "Failed to set data source: ${e.message}")
            return
        }

        var trackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                trackIndex = i
                inputFormat = format
                break
            }
        }

        if (trackIndex == -1 || inputFormat == null) return

        extractor.selectTrack(trackIndex)
        
        val decoder = try {
            MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        } catch (e: Exception) {
            Log.e("AudioConverter", "Failed to create decoder: ${e.message}")
            return
        }
        
        try {
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
        } catch (e: Exception) {
            Log.e("AudioConverter", "Failed to start decoder: ${e.message}")
            return
        }

        val info = MediaCodec.BufferInfo()
        var isEOS = false
        var currentOutputFormat = decoder.outputFormat
        
        var speechStarted = false
        var anyDataSent = false

        try {
            while (true) {
                if (!isEOS) {
                    val inIndex = decoder.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, 10000)
                if (outIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outIndex)!!
                    
                    // Respect buffer offset and limit
                    outputBuffer.position(info.offset)
                    outputBuffer.limit(info.offset + info.size)

                    val pcmData = ShortArray(info.size / 2)
                    outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcmData)

                    val sampleRate = if (currentOutputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        currentOutputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else 16000

                    val channels = if (currentOutputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        currentOutputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else 1

                    var processedPcm = processPcm(pcmData, sampleRate, channels)

                    // Apply Bandpass filter (adjustable range) if enabled
                    if (isBandpassEnabled && processedPcm.isNotEmpty()) {
                        if (bandpassFilter == null) {
                            // Geometric mean for center frequency
                            val center = sqrt(lowFreq * highFreq)
                            // Bandwidth in octaves
                            val octaves = log2(highFreq / lowFreq)
                            bandpassFilter = BandpassFilter(16000.0, center, octaves)
                        }
                        for (i in processedPcm.indices) {
                            processedPcm[i] = bandpassFilter!!.process(processedPcm[i])
                        }
                    }

                    // Noise Gate / VAD: Filter silence throughout the entire audio using peak detection
                    if (processedPcm.isNotEmpty()) {
                        var hasSpeech = false
                        for (sample in processedPcm) {
                            if (abs(sample.toInt()) > silenceThreshold) {
                                hasSpeech = true
                                break
                            }
                        }
                        
                        if (hasSpeech) {
                            speechStarted = true
                        } else if (!speechStarted) {
                            processedPcm = ShortArray(0)
                        }
                    }

                    if (processedPcm.isNotEmpty()) {
                        anyDataSent = true
                        onData(processedPcm)
                    }
                    
                    decoder.releaseOutputBuffer(outIndex, false)
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    currentOutputFormat = decoder.outputFormat
                }

                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    // Ensure we send something if the file was all silence or very short
                    if (!anyDataSent) {
                        onData(ShortArray(1600)) // 100ms of silence fallback
                    }
                    break
                }
            }
        } catch (e: Exception) {
            val msg = e.message ?: ""
            val name = e.javaClass.simpleName
            if (msg.contains("EPIPE") || msg.contains("Broken pipe") || name.contains("RecognitionStoppedException")) {
                Log.d("AudioConverter", "Pipe closed by consumer (expected at end of session)")
            } else {
                Log.e("AudioConverter", "Decoding error: $msg ($name)", e)
            }
        } finally {
            try { decoder.stop() } catch (_: Exception) {}
            decoder.release()
            extractor.release()
            bandpassFilter?.reset()
        }
    }

    private fun processPcm(input: ShortArray, inputRate: Int, channels: Int): ShortArray {
        var pcm = input
        if (channels > 1) {
            pcm = convertToMono(pcm, channels)
        }
        if (inputRate != 16000) {
            pcm = resample(pcm, inputRate)
        }
        return pcm
    }

    private fun convertToMono(stereo: ShortArray, channels: Int): ShortArray {
        val mono = ShortArray(stereo.size / channels)
        for (i in mono.indices) {
            var sum = 0
            for (c in 0 until channels) {
                sum += stereo[i * channels + c]
            }
            mono[i] = (sum / channels).toShort()
        }
        return mono
    }

    private fun resample(input: ShortArray, inputRate: Int): ShortArray {
        val outputRate = 16000
        val newLength = (input.size.toLong() * outputRate / inputRate).toInt()
        val output = ShortArray(newLength)
        for (i in 0 until newLength) {
            val pos = i.toFloat() * inputRate / outputRate
            val index = pos.toInt()
            val frac = pos - index
            if (index + 1 < input.size) {
                output[i] = (input[index] * (1.0f - frac) + input[index + 1] * frac).toInt().toShort()
            } else if (index < input.size) {
                output[i] = input[index]
            }
        }
        return output
    }

}
