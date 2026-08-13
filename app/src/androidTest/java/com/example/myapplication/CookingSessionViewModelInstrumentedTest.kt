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
    fun manualSessionCanAdvanceAndReturnWithoutLosingStep() {
        val viewModel = CookingSessionViewModel(ApplicationProvider.getApplicationContext<Application>())
        viewModel.startWithoutGlasses()
        assertEquals(AppScreen.S8_MANUAL, viewModel.uiState.value.currentScreen)

        viewModel.continueToNextStep()
        assertEquals(1, viewModel.uiState.value.session?.currentStepIndex)
        viewModel.moveToPreviousStep()

        assertEquals(0, viewModel.uiState.value.session?.currentStepIndex)
        assertTrue(viewModel.uiState.value.session?.logs?.any { it.overrideType == "UNDO_DONE" } == true)
    }
}
