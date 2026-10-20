package org.ergoutree.compress

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import org.ergoutree.compress.domain.DesktopPreferences
import org.ergoutree.compress.workspace.FHelpButton
import org.ergoutree.compress.workspace.FTextField
import org.ergoutree.compress.workspace.FluentTheme
import kotlin.test.Test

/** 验证新 Fluent 输入框与帮助入口的可访问名称及说明展开。 */
@OptIn(ExperimentalTestApi::class)
class HelpTipTest {
    @Test fun visibleSettingHasNamedEditableFieldAndAccessibleHelpAction() = runComposeUiTest {
        setContent {
            FluentTheme(DesktopPreferences()) {
                Column {
                    FTextField(value = "23", onValueChange = {}, label = "质量", description = "质量影响体积")
                    FHelpButton("质量", "帮助：质量值范围为 0 到 51")
                }
            }
        }
        onNodeWithContentDescription("质量输入框").assertExists().performTextReplacement("28")
        onNodeWithContentDescription("质量帮助").assertHasClickAction().performClick()
        waitForIdle()
        onNodeWithText("帮助：质量值范围为 0 到 51").assertExists()
    }
}
