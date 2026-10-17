package preflight

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AccessibilitySemanticsTest {
    @Test fun namedTextCanBeEditedThroughSemantics() = runComposeUiTest {
        setContent { AcceptanceControls(FileImportTarget({})) }
        onNodeWithContentDescription("文件导入区域").assertExists()
        onNodeWithContentDescription("输出帧率输入框").assert(hasSetTextAction())
            .performTextReplacement("30000/1001")
        onNodeWithText("当前输出帧率：30000/1001").assertExists()
    }
    @Test fun helpIsNamedActionableAndToggles() = runComposeUiTest {
        setContent { HelpTip("输出帧率", helpMessage) }
        val help = onNodeWithContentDescription("输出帧率帮助")
        help.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertHasClickAction()
        onNodeWithText(helpMessage).assertDoesNotExist()
        help.performClick()
        onNodeWithText(helpMessage).assertExists()
        help.performClick()
        onNodeWithText(helpMessage).assertDoesNotExist()
    }
    @Test fun focusedHelpIsReadableAndEscapeDismissesIt() = runComposeUiTest {
        setContent { HelpTip("输出帧率", helpMessage) }
        val help = onNodeWithContentDescription("输出帧率帮助")
        help.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        help.assertIsFocused()
        onNodeWithText(helpMessage).assertExists()
        help.performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
        onNodeWithText(helpMessage).assertDoesNotExist()
        help.assertIsFocused()
    }
    @Test fun tabReachesHelpAndSpaceOpensIt() = runComposeUiTest {
        setContent { AcceptanceControls(FileImportTarget({})) }
        val field=onNodeWithContentDescription("输出帧率输入框")
        field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        field.performKeyInput { keyDown(Key.Tab); keyUp(Key.Tab) }
        val help=onNodeWithContentDescription("输出帧率帮助")
        help.assertIsFocused().performKeyInput { keyDown(Key.Spacebar); keyUp(Key.Spacebar) }
        onNodeWithText(helpMessage).assertExists()
    }
}
