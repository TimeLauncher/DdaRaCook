package com.example.myapplication

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CookingSessionViewModelInstrumentedTest {
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
