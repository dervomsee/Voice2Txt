package de.dervomsee.voice2txt

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import de.dervomsee.voice2txt.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CopyTranscriptionUiTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun testLongPressToCopy() {
        val testText = "This is a test transcription"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        composeTestRule.activity.runOnUiThread {
            val vm = androidx.lifecycle.ViewModelProvider(composeTestRule.activity)[MainViewModel::class.java]
            vm.setTranscriptionForTesting(testText)
        }

        // Verify the text is displayed
        composeTestRule.onNodeWithText(testText).assertIsDisplayed()

        // Long press to copy
        composeTestRule.onNodeWithText(testText).performTouchInput {
            longClick()
        }

        // Verify Snackbar appears
        val copiedMessage = context.getString(R.string.copied_to_clipboard)
        composeTestRule.onNodeWithText(copiedMessage).assertIsDisplayed()

        // Verify Clipboard content
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        assertEquals(testText, clipData?.getItemAt(0)?.text?.toString())
    }
}
