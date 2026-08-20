package com.example.myapplication

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.camera.WearableCameraState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CookingSessionViewModelInstrumentedTest {
    @Test
    fun presentationSimulationAutomaticallyAdvancesAfterIntroAndScriptedCapture() = runBlocking {
        val viewModel = CookingSessionViewModel(ApplicationProvider.getApplicationContext<Application>())

        viewModel.openPresentationSimulationDetail()
        assertEquals(AppScreen.S2_RECIPE_DETAIL, viewModel.uiState.value.currentScreen)

        delay(800L)
        assertEquals(AppScreen.S4_DEVICE, viewModel.uiState.value.currentScreen)

        delay(800L)
        assertEquals(AppScreen.S5_COOKING, viewModel.uiState.value.currentScreen)
        assertFalse(viewModel.uiState.value.presentationCaptureVisible)

        delay(2_500L)
        assertFalse(viewModel.uiState.value.presentationCaptureVisible)

        delay(700L)
        assertTrue(viewModel.uiState.value.presentationCaptureVisible)

        delay(800L)
        assertEquals(1, viewModel.uiState.value.session?.currentStepIndex)
        assertFalse(viewModel.uiState.value.presentationCaptureVisible)

        val summary = withTimeout(18_000L) {
            viewModel.uiState.first { it.currentScreen == AppScreen.S9_SUMMARY }
        }
        assertEquals(5, summary.session?.completedStepOrders?.size)
        assertEquals(CookingPhase.SESSION_COMPLETED, summary.session?.phase)
    }

    @Test
    fun presentationSimulationUsesScriptedFramesWithoutCameraOrJudgmentState() {
        val viewModel = CookingSessionViewModel(ApplicationProvider.getApplicationContext<Application>())

        viewModel.openPresentationSimulationDetail()

        val detail = viewModel.uiState.value
        assertTrue(detail.isPresentationSimulation)
        assertEquals(AppScreen.S2_RECIPE_DETAIL, detail.currentScreen)
        assertEquals(PresentationSimulation.RECIPE_ID, detail.selectedRecipeId)
        assertNull(detail.session)

        viewModel.openPresentationSimulationDevicePreparation()

        val device = viewModel.uiState.value
        assertTrue(device.isPresentationSimulation)
        assertEquals(AppScreen.S4_DEVICE, device.currentScreen)
        assertEquals(WearableCameraState.Ready, device.cameraState)

        viewModel.startPresentationSimulation()

        val first = viewModel.uiState.value
        assertTrue(first.isPresentationSimulation)
        assertEquals(AppScreen.S5_COOKING, first.currentScreen)
        assertEquals(PresentationSimulation.RECIPE_ID, first.selectedRecipeId)
        assertEquals(0, first.session?.currentStepIndex)
        assertEquals(CookingPhase.WAITING_FOR_CHECK, first.session?.phase)
        assertNull(first.currentCaptureOutcome)
        assertNull(first.nextInspectionInSeconds)
        assertFalse(first.judgingInFlight)
        assertFalse(first.presentationCaptureVisible)

        viewModel.revealPresentationCapture()
        assertTrue(viewModel.uiState.value.presentationCaptureVisible)

        viewModel.continuePresentationSimulation()

        val second = viewModel.uiState.value
        assertEquals(1, second.session?.currentStepIndex)
        assertEquals(CookingPhase.WAITING_FOR_CHECK, second.session?.phase)
        assertNull(second.currentCaptureOutcome)
        assertFalse(second.judgingInFlight)
        assertFalse(second.presentationCaptureVisible)
    }

    @Test
    fun selectingRecipeImmediatelyUpdatesViewedRecipeState() {
        val viewModel = CookingSessionViewModel(ApplicationProvider.getApplicationContext<Application>())
        val recipeId = viewModel.uiState.value.recipes.first { it.id == "beef-brisket-pasta" }.id

        viewModel.selectRecipe(recipeId)

        assertEquals(recipeId, viewModel.uiState.value.viewedRecipeIds.firstOrNull())
    }

    @Test
    fun previousFromFirstStepReturnsToDevicePreparationAndKeepsSession() {
        val viewModel = CookingSessionViewModel(ApplicationProvider.getApplicationContext<Application>())
        viewModel.startWithoutGlasses()

        viewModel.moveToPreviousStep()

        assertEquals(AppScreen.S4_DEVICE, viewModel.uiState.value.currentScreen)
        assertEquals(0, viewModel.uiState.value.session?.currentStepIndex)
        assertTrue(viewModel.uiState.value.hasResumableSession)
        assertTrue(viewModel.uiState.value.resumeAutoAfterDeviceSetup)
    }

    @Test
    fun backFromDevicePreparationReturnsToRecipeDetailWithoutStartingManualMode() {
        val viewModel = CookingSessionViewModel(ApplicationProvider.getApplicationContext<Application>())
        viewModel.startWithoutGlasses()
        viewModel.moveToPreviousStep()

        viewModel.backFromDevicePreparation()

        assertEquals(AppScreen.S2_RECIPE_DETAIL, viewModel.uiState.value.currentScreen)
        assertEquals(SessionMode.MANUAL_ONLY, viewModel.uiState.value.session?.mode)
        assertTrue(viewModel.uiState.value.hasResumableSession)
    }

    @Test
    fun manualSessionCanAdvanceAndReturnWithoutLosingStep() {
        val viewModel = CookingSessionViewModel(ApplicationProvider.getApplicationContext<Application>())
        viewModel.startWithoutGlasses()
        assertEquals(AppScreen.S8_MANUAL, viewModel.uiState.value.currentScreen)

        viewModel.continueManualButtonToNextStep()
        assertEquals(1, viewModel.uiState.value.session?.currentStepIndex)
        assertEquals(SessionMode.MANUAL_ONLY, viewModel.uiState.value.session?.mode)
        assertEquals(AppScreen.S8_MANUAL, viewModel.uiState.value.currentScreen)
        viewModel.continueManualButtonToNextStep()
        assertEquals(2, viewModel.uiState.value.session?.currentStepIndex)
        assertEquals(SessionMode.MANUAL_ONLY, viewModel.uiState.value.session?.mode)
        viewModel.moveToPreviousStep()

        assertEquals(1, viewModel.uiState.value.session?.currentStepIndex)
        assertTrue(viewModel.uiState.value.session?.logs?.any { it.overrideType == "UNDO_DONE" } == true)
    }
}
