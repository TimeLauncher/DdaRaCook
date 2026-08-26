package com.example.myapplication

import com.example.myapplication.recipeimport.serverCheckTypeToApp
import com.example.myapplication.recipeimport.importedStepCropTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeRecipeApiServiceTest {
    @Test
    fun serverCheckTypesMapToStoredAppEnumNames() {
        val mappings = mapOf(
            "PRESENCE" to CheckType.PRESENCE,
            "COUNT" to CheckType.COUNT,
            "IDENTIFY" to CheckType.IDENTIFICATION,
            "COLOR_CHANGE" to CheckType.COLOR_CHANGE,
            "STATE_CHANGE" to CheckType.STATE_TRANSITION,
            "TIME_ONLY" to CheckType.TIMER_ONLY
        )
        mappings.forEach { (serverType, appType) ->
            assertEquals(appType, serverCheckTypeToApp(serverType))
        }
    }

    @Test
    fun extractedStepsChooseCropPolicyWithoutServerCropMetadata() {
        assertEquals(ImageCropTarget.AUTO_ROI, importedStepCropTarget(isAutoCheck = true))
        assertEquals(ImageCropTarget.LEGACY_BOTTOM_60, importedStepCropTarget(isAutoCheck = false))
    }
}
