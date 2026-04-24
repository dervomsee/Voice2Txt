package de.dervomsee.voice2txt.engine

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.Accelerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WhisperSpeechEngine(private val context: Context) : SpeechEngine {
    override var name: String = "Whisper (Local LiteRT)"
        private set

    private var compiledModel: CompiledModel? = null
    private var environment: Environment? = null
    private var whisperLib: WhisperLib? = null
    private var currentModelFileName: String = "whisper_tiny.tflite"
    private var useHardwareAcceleration = true
    
    private val modelFile: File
        get() = File(context.filesDir, "models/$currentModelFileName")
        
    private val vocabFile: File
        get() = File(context.filesDir, "models/filters_vocab_gen.bin")

    fun setModel(fileName: String) {
        if (currentModelFileName != fileName) {
            currentModelFileName = fileName
            close()
        }
    }

    private fun close() {
        compiledModel?.close()
        environment?.close()
        compiledModel = null
        environment = null
        whisperLib = null
    }

    override fun transcribe(onPcmData: ((ShortArray) -> Unit) -> Unit): Flow<SpeechResult> = callbackFlow {
        if (!modelFile.exists() || !vocabFile.exists()) {
            trySend(SpeechResult.Error("Whisper files missing."))
            close()
            return@callbackFlow
        }
        
        withContext(Dispatchers.Default) {
            try {
                if (compiledModel == null) {
                    Log.d("WhisperEngine", "Initializing CompiledModel (HA=$useHardwareAcceleration)...")
                    environment = Environment.create(emptyMap())
                    
                    try {
                        // The 'whisper_tiny' model often has issues with LiteRT GPU/NPU acceleration 
                        // on certain drivers (kTfLiteArenaRw error). 
                        // We force CPU for tiny and attempt HA for others.
                        val isTiny = currentModelFileName.contains("tiny")
                        
                        if (useHardwareAcceleration && !isTiny) {
                            // Priority: NPU -> GPU -> CPU
                            val options = CompiledModel.Options(Accelerator.NPU, Accelerator.GPU)
                            compiledModel = CompiledModel.create(modelFile.absolutePath, options, environment!!)
                            Log.d("WhisperEngine", "Model compiled with Hardware Acceleration.")
                        } else {
                            val reason = if (isTiny) "Forced CPU for Tiny model" else "HA disabled/failed"
                            throw Exception(reason)
                        }
                    } catch (e: Exception) {
                        Log.w("WhisperEngine", "Hardware acceleration failed, falling back to CPU", e)
                        useHardwareAcceleration = false
                        // Fallback to CPU
                        val options = CompiledModel.Options(Accelerator.CPU)
                        compiledModel = CompiledModel.create(modelFile.absolutePath, options, environment!!)
                        Log.d("WhisperEngine", "Model compiled with CPU fallback.")
                    }

                    whisperLib = WhisperLib(vocabFile)
                }

                trySend(SpeechResult.Partial("Processing with Hardware Acceleration..."))
                
                val maxSamples = 16000 * 30
                val audioBuffer = FloatArray(maxSamples)
                var totalSamples = 0

                onPcmData { pcm ->
                    for (sample in pcm) {
                        if (totalSamples < maxSamples) {
                            audioBuffer[totalSamples] = sample.toFloat() / 32768f
                            totalSamples++
                        }
                    }
                }

                if (totalSamples > 0) {
                    val melData = whisperLib!!.getMelSpectrogram(audioBuffer)
                    
                    val inputBuffers = compiledModel!!.createInputBuffers()
                    val outputBuffers = compiledModel!!.createOutputBuffers()
                    
                    inputBuffers[0].writeFloat(melData)
                    
                    Log.d("WhisperEngine", "Running accelerated inference...")
                    compiledModel!!.run(inputBuffers, outputBuffers)
                    
                    val tokens = outputBuffers[0].readInt()
                    val text = whisperLib!!.decode(tokens)
                    
                    if (text.isNotEmpty()) {
                        trySend(SpeechResult.Final(text))
                    } else {
                        trySend(SpeechResult.Final("[No speech detected]"))
                    }
                } else {
                    trySend(SpeechResult.Error("No audio detected"))
                }
                close()
            } catch (e: Exception) {
                Log.e("WhisperEngine", "Accelerated process failed", e)
                trySend(SpeechResult.Error("Whisper error: ${e.message}"))
                close()
            }
        }
    }

    fun isAvailable(): Boolean = modelFile.exists() && vocabFile.exists()
}
