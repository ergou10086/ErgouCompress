package org.ergoutree.compress

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.ergoutree.compress.presentation.*
import org.ergoutree.compress.workspace.*
import kotlin.test.*

/** 在真实 Compose 语义树验证可发现操作、列表选择和任务页，避免只检查静态字符串。 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceUiTest {
    @Test fun homeExposesTopMenusAndFileImportWithoutAParameterSidebar() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val controller = WorkspaceController(WorkspaceFixture(), scope)
            var page: WorkspacePage? = null
            setContent { FluentTheme(controller.state.value.preferences) { Box(Modifier.size(1180.dp, 860.dp)) { WorkspaceApp(controller, openPage = { page = it }) } } }
            onNodeWithText("将文件夹或视频拖到这里").assertIsDisplayed()
            onNodeWithText("添加文件").assertHasClickAction()
            onNodeWithText("设置").performClick(); assertEquals(WorkspacePage.SETTINGS, page)
            onNodeWithText("历史").performClick(); assertEquals(WorkspacePage.HISTORY, page)
            onNodeWithText("处理选项  ›").assertIsNotEnabled()
        } finally { scope.cancel() }
    }
    @Test fun scanRowsCanBeDeselectedAndTaskOptionsReflectTheSelection() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val controller = WorkspaceController(WorkspaceFixture(), scope)
            controller.initialize(); controller.importPaths(listOf("/input/a.mp4"))
            setContent { FluentTheme(controller.state.value.preferences) { Box(Modifier.size(1180.dp, 860.dp)) { WorkspaceApp(controller) } } }
            waitUntil(timeoutMillis = 5000) { controller.state.value.scanComplete }
            onNodeWithText("待采样估算").assertIsDisplayed()
            onNodeWithText("处理选项  ›").assertIsEnabled()
            onNodeWithText("取消选择").performClick()
            onNodeWithText("处理选项  ›").assertIsNotEnabled()
            onNodeWithText("全选可用项").performClick()
            onNodeWithText("处理选项  ›").assertIsEnabled()
        } finally { scope.cancel() }
    }
    @Test fun optionsExposeOutputProtectionAndStartOnlyAfterReview() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val controller = WorkspaceController(WorkspaceFixture(), scope)
            controller.initialize(); controller.importPaths(listOf("/input/a.mp4"))
            setContent { FluentTheme(controller.state.value.preferences) { Box(Modifier.size(840.dp, 840.dp)) { TaskOptionsPage(controller, {}) {} } } }
            waitUntil(timeoutMillis = 5000) { controller.state.value.scanComplete }
            onNodeWithText("开始处理").assertIsEnabled().performClick()
            waitUntil(timeoutMillis = 5000) { controller.state.value.jobs.isNotEmpty() }
            assertEquals(1, controller.state.value.jobs.single().snapshot.plans.size)
        } finally { scope.cancel() }
    }
}
