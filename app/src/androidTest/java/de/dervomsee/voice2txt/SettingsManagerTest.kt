package de.dervomsee.voice2txt

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.dervomsee.voice2txt.settings.SettingsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsManagerTest {
    private lateinit var settingsManager: SettingsManager

    @Before
    fun setup() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        settingsManager = SettingsManager(appContext)
    }

    @Test
    fun testSettingsPersistence() = runBlocking {
        // 1. Set values
        val testLanguage = "fr"
        val testGpu = true
        val testModel = "ggml-small-q8_0.bin"

        settingsManager.setSelectedLanguage(testLanguage)
        settingsManager.setUseGpu(testGpu)
        settingsManager.setSelectedModelFile(testModel)

        // 2. Verify values
        assertEquals(testLanguage, settingsManager.selectedLanguage.first())
        assertEquals(testGpu, settingsManager.useGpu.first())
        assertEquals(testModel, settingsManager.selectedModelFile.first())
    }
}
