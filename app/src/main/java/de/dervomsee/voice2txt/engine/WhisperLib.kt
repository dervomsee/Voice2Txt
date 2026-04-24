package de.dervomsee.voice2txt.engine

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Optimized logic for Whisper Mel-Spectrogram and Tokenizer
 */
class WhisperLib(private val vocabFile: File) {

    private var nMel: Int = 80
    private var nFftHalf: Int = 201
    private lateinit var melFilters: Array<FloatArray>
    private val vocab = mutableMapOf<Int, String>()

    init {
        loadVocabAndFilters()
    }

    private fun loadVocabAndFilters() {
        if (!vocabFile.exists()) return
        
        try {
            FileInputStream(vocabFile).use { fis ->
                val buffer = ByteArray(4)
                fis.read(buffer) // Magic
                fis.read(buffer)
                nMel = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).int
                fis.read(buffer)
                nFftHalf = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).int
                
                melFilters = Array(nMel) { FloatArray(nFftHalf) }
                val floatBuffer = ByteArray(4)
                for (i in 0 until nMel) {
                    for (j in 0 until nFftHalf) {
                        fis.read(floatBuffer)
                        melFilters[i][j] = ByteBuffer.wrap(floatBuffer).order(ByteOrder.LITTLE_ENDIAN).float
                    }
                }
                
                fis.read(buffer)
                val vocabSize = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).int
                for (i in 0 until vocabSize) {
                    fis.read(buffer)
                    val len = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).int
                    val stringBuffer = ByteArray(len)
                    fis.read(stringBuffer)
                    vocab[i] = String(stringBuffer, Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            Log.e("WhisperLib", "Failed to load vocab/filters", e)
        }
    }

    fun getMelSpectrogram(audio: FloatArray): FloatArray {
        val nFft = 400
        val hopLength = 160
        val nFrames = 3000
        val melData = FloatArray(nMel * nFrames)
        
        val hannWindow = FloatArray(nFft) { i -> 
            0.5f * (1.0 - cos(2.0 * PI * i / (nFft - 1))).toFloat()
        }

        // FFT optimization: Precompute sin/cos for a faster DFT
        val cosTable = FloatArray(nFftHalf * nFft)
        val sinTable = FloatArray(nFftHalf * nFft)
        for (k in 0 until nFftHalf) {
            for (n in 0 until nFft) {
                val angle = 2.0 * PI * k * n / nFft
                cosTable[k * nFft + n] = cos(angle).toFloat()
                sinTable[k * nFft + n] = sin(angle).toFloat()
            }
        }
        
        for (i in 0 until nFrames) {
            val start = i * hopLength
            val frame = FloatArray(nFft)
            for (j in 0 until nFft) {
                if (start + j < audio.size) {
                    frame[j] = audio[start + j] * hannWindow[j]
                }
            }
            
            val powerSpec = FloatArray(nFftHalf)
            for (k in 0 until nFftHalf) {
                var re = 0f
                var im = 0f
                val offset = k * nFft
                for (n in 0 until nFft) {
                    re += frame[n] * cosTable[offset + n]
                    im -= frame[n] * sinTable[offset + n]
                }
                powerSpec[k] = re * re + im * im
            }
            
            for (m in 0 until nMel) {
                var sum = 0f
                val filters = melFilters[m]
                for (k in 0 until nFftHalf) {
                    sum += powerSpec[k] * filters[k]
                }
                val valLog = log10(max(sum, 1e-10f))
                melData[m * nFrames + i] = (valLog + 4.0f) / 4.0f
            }
        }
        return melData
    }

    fun decode(tokens: IntArray): String {
        val result = StringBuilder()
        for (token in tokens) {
            if (token >= 50257) continue // Skip special tokens/timestamps
            
            val s = vocab[token] ?: continue
            // Whisper 'Ġ' is a space. Clean up other control characters.
            val clean = s.replace("Ġ", " ")
                .filter { it.isPrintable() || it.isWhitespace() }
            
            result.append(clean)
        }
        return result.toString().trim()
    }

    private fun Char.isPrintable(): Boolean {
        val block = Character.UnicodeBlock.of(this)
        return (!Character.isISOControl(this)) &&
                block != null &&
                block != Character.UnicodeBlock.SPECIALS
    }
}
