package de.dervomsee.voice2txt

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import de.dervomsee.voice2txt.audio.AudioDecoder
import de.dervomsee.voice2txt.whisper.ModelDownloader
import de.dervomsee.voice2txt.whisper.WhisperContext
import de.dervomsee.voice2txt.whisper.availableModels
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Dieser Test demonstriert, wie eine MP3-Datei aus den Assets transkribiert werden kann.
 * WICHTIG: Damit dieser Test erfolgreich ist, muss:
 * 1. Eine Datei namens "test_speech.mp3" im Ordner app/src/androidTest/assets/ liegen.
 * 2. Das Whisper-Modell (Tiny Q8_0) auf dem Gerät heruntergeladen sein.
 */
class TranscriptionTest {

    @Test
    fun testMp3Transcription() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assetManager = context.assets
        val testFileName = "test_speech.mp3"

        // Prüfen, ob die Testdatei überhaupt existiert
        val assets = assetManager.list("") ?: emptyArray()
        if (!assets.contains(testFileName)) {
            println("Test übersprungen: $testFileName nicht in assets gefunden.")
            return@runBlocking
        }

        // 1. MP3 aus Assets in temporäre Datei kopieren (MediaExtractor benötigt File-Pfad oder FD)
        val tempFile = File(context.cacheDir, testFileName)
        assetManager.open(testFileName).use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }

        // 2. Audio decodieren
        val decoder = AudioDecoder()
        val pcmData = decoder.decodeToFloatArray(context, Uri.fromFile(tempFile))
        assertTrue("Audio-Dekodierung fehlgeschlagen", pcmData != null && pcmData.isNotEmpty())

        // 3. Whisper Model laden
        val model = availableModels[1] // Tiny Q8_0
        if (!ModelDownloader.isModelDownloaded(context, model.fileName)) {
            println("Test übersprungen: Modell ${model.fileName} nicht auf dem Gerät gefunden.")
            return@runBlocking
        }
        
        val modelFile = ModelDownloader.getModelFile(context, model.fileName)
        val whisper = WhisperContext.createContextFromFile(modelFile.absolutePath, false)

        try {
            // 4. Transkribieren
            // Wir nutzen "auto" Sprache für den Test
            val result = whisper.transcribeData(pcmData!!, language = "auto")
            
            // 5. Verifizieren
            println("Transkription Ergebnis: $result")
            assertTrue("Das Transkriptionsergebnis sollte nicht leer sein", result.isNotBlank())
        } finally {
            whisper.release()
            tempFile.delete()
        }
    }
}
