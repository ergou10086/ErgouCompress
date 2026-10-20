package org.ergoutree.compress

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.ergoutree.compress.presentation.*
import org.ergoutree.compress.workspace.*
import kotlin.test.*

/** 在真实 Compose 语义树验证无侧栏主页、直接开始任务与参数冲突入口。 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceUiTest {
    @Test fun homeExposesTopMenusAndFileImportWithoutAParameterSidebar() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val controller = WorkspaceController(WorkspaceFixture(), scope)
            var page: WorkspacePage? = null
            setContent {
                FluentTheme(controller.state.value.preferences) {
                    Box(Modifier.size(1240.dp, 920.dp)) { WorkspaceApp(controller, openPage = { page = it }) }
                }
            }
            waitUntil(timeoutMillis = 5000) { controller.state.value.initialized }
            onNodeWithText("将文件或文件夹拖到此窗口").assertIsDisplayed()
            onNode(hasText("添加文件") and hasClickAction()).assertHasClickAction()
            onNodeWithText("设置").performClick(); assertEquals(WorkspacePage.SETTINGS, page)
            onNodeWithText("历史").performClick(); assertEquals(WorkspacePage.HISTORY, page)
            onNodeWithText("开始处理").assertIsNotEnabled()
        } finally { scope.cancel() }
    }

    @Test fun selectionCanBeClearedAndRestoredFromTheReviewHeader() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val controller = WorkspaceController(WorkspaceFixture(), scope)
            controller.initialize(); controller.importPaths(listOf("/input/a.mp4"))
            setContent {
                FluentTheme(controller.state.value.preferences) {
                    Box(Modifier.size(1240.dp, 920.dp)) { WorkspaceApp(controller) }
                }
            }
            waitUntil(timeoutMillis = 5000) { controller.state.value.scanComplete }
            onNodeWithText("待估算").assertExists()
            onNodeWithText("开始处理").assertIsEnabled()
            onNodeWithContentDescription("全选可处理项").performClick()
            onNodeWithText("开始处理").assertIsNotEnabled()
            onNodeWithContentDescription("全选可处理项").performClick()
            onNodeWithText("开始处理").assertIsEnabled()
        } finally { scope.cancel() }
    }

    @Test fun mainCanStartWithCurrentParametersWithoutOpeningOptions() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val controller = WorkspaceController(WorkspaceFixture(), scope)
            controller.initialize(); controller.importPaths(listOf("/input/a.mp4"))
            setContent {
                FluentTheme(controller.state.value.preferences) {
                    Box(Modifier.size(1240.dp, 920.dp)) { WorkspaceApp(controller) }
                }
            }
            waitUntil(timeoutMillis = 5000) { controller.state.value.scanComplete }
            onNodeWithText("开始处理").assertIsEnabled().performClick()
            waitUntil(timeoutMillis = 5000) { controller.state.value.jobs.isNotEmpty() }
            assertEquals(1, controller.state.value.jobs.single().snapshot.plans.size)
            assertTrue(controller.state.value.rows.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun optionsExposeOutputProtectionAndStartOnlyAfterReview() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val controller = WorkspaceController(WorkspaceFixture(), scope)
            controller.initialize(); controller.importPaths(listOf("/input/a.mp4"))
            setContent {
                FluentTheme(controller.state.value.preferences) {
                    Box(Modifier.size(1020.dp, 900.dp)) { TaskOptionsPage(controller, {}, {}) }
                }
            }
            waitUntil(timeoutMillis = 5000) { controller.state.value.scanComplete }
            onNodeWithText("处理选项").assertIsDisplayed()
            onNodeWithText("开始处理").assertIsEnabled().performClick()
            waitUntil(timeoutMillis = 5000) { controller.state.value.jobs.isNotEmpty() }
            assertEquals(1, controller.state.value.jobs.single().snapshot.plans.size)
        } finally { scope.cancel() }
    }
}
