package dev.fitface.studio.feature.editor

import dev.fitface.studio.core.model.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class StyleManagementViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val repository = mockk<WatchFaceRepository>()
    private val frame = PreviewFrame(1, 1, intArrayOf(0))
    private val snapshot = EditorSnapshot(7, "00008", null, "face.apk",
        styleNames = listOf("style0.bin", "style1.bin", "style2.bin"), activeStyleName = "style0.bin",
        preview = frame, composedPreview = frame, widgetOverlay = frame, widgetImageLayers = emptyList(),
        widgets = emptyList(), imageCount = 0, validationErrors = emptyList(), validationWarnings = emptyList(),
        isDirty = false, audit = null)
    private lateinit var vm: StyleManagementViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.styleManagement() } returns StyleManagement(snapshot, "revision", emptyMap(), emptyMap())
        vm = StyleManagementViewModel(repository, DiagnosticsLog())
    }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun settle() = scope.advanceUntilIdle()

    @Test fun protectedTargetAodAndLastStyleCannotBeChosenAndDeleteRequiresReview() {
        vm.open(7, "style1.bin", "style1.bin"); settle()
        vm.toggle("style1.bin"); vm.toggle("aod.bin")
        assertTrue(vm.state.value.chosen.isEmpty())
        vm.toggle("style0.bin"); vm.delete(); settle()
        coVerify(exactly = 0) { repository.deleteStyles(any(), any()) }
        vm.toggle("style2.bin"); assertEquals(2, vm.state.value.chosen.size)
        vm.close(); vm.open(7, null, "style0.bin"); settle()
        vm.toggle("style1.bin"); vm.toggle("style2.bin")
        assertEquals(setOf("style0.bin", "style1.bin"), vm.state.value.chosen)
        vm.review(); vm.toggle("style1.bin")
        assertEquals(2, vm.state.value.chosen.size)
    }
    @Test fun failureStaysInConfirmationAndRetryUsesTheReviewedRevision() {
        vm.open(7, null, "style1.bin"); settle(); vm.review()
        coEvery { repository.deleteStyles(setOf("style1.bin"), "revision") } throws WatchFaceException("Disk full")
        vm.delete(); settle()
        assertEquals("Disk full", vm.state.value.error)
        assertTrue(vm.state.value.confirming)
        assertNull(vm.state.value.deleted)
        coEvery { repository.deleteStyles(setOf("style1.bin"), "revision") } returns snapshot.copy(isDirty = true)
        vm.delete(); settle()
        assertTrue(vm.state.value.deleted!!.isDirty)
    }
    @Test fun aSlowCommitCannotBeClosedOrSubmittedTwice() {
        vm.open(7, null, "style1.bin"); settle(); vm.review()
        val pending = CompletableDeferred<EditorSnapshot>()
        coEvery { repository.deleteStyles(any(), any()) } coAnswers { pending.await() }
        vm.delete(); settle(); vm.close(); vm.delete(); vm.back()
        assertTrue(vm.state.value.busy); assertTrue(vm.state.value.confirming)
        pending.complete(snapshot.copy(isDirty = true)); settle()
        coVerify(exactly = 1) { repository.deleteStyles(any(), any()) }
    }
}
