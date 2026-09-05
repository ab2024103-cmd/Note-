package com.notepadpro.shared.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NotePadProLogoTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sharedLogoResourceLoadsAndRendersWithAnAccessibleLabel() {
        compose.setContent {
            MaterialTheme {
                NotePadProLogo(
                    modifier = Modifier.size(48.dp),
                    contentDescription = "NotePad Pro logo"
                )
            }
        }
        val logo = compose.onNodeWithContentDescription("NotePad Pro logo").assertIsDisplayed()
        val pixels = logo.captureToImage().toPixelMap()
        // Check actual artwork, not just an empty Image's layout bounds.
        assertEquals(Color(0xFF3949AB), pixels[pixels.width / 2, pixels.height / 10])
        assertEquals(Color.White, pixels[pixels.width * 2 / 5, pixels.height * 3 / 10])
    }

    @Test
    fun decorativeLogoDoesNotRepeatTheAdjacentAppName() {
        compose.setContent {
            MaterialTheme {
                NotePadProLogo(modifier = Modifier.size(24.dp).testTag("brand"))
            }
        }
        compose.onNodeWithTag("brand")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    }
}
