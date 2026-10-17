package org.ergoutree.compress

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import org.ergoutree.compress.project.SettingField
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class HelpTipTest {
    @Test fun visibleSettingHasNamedEditableFieldAndAccessibleHelpAction() = runComposeUiTest {
        setContent { SettingField("质量", "23", "质量影响体积", true) {} }
        onNodeWithContentDescription("质量输入框").assertExists().performTextReplacement("28")
        onNodeWithContentDescription("质量帮助").assertHasClickAction().performClick()
        onNodeWithText("质量影响体积").assertIsDisplayed()
        onNodeWithContentDescription("质量帮助").performClick()
        onNodeWithText("质量影响体积").assertDoesNotExist()
    }
}
