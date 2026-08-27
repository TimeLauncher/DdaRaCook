package com.example.myapplication

import com.example.myapplication.recipeimport.looksLikeYoutubeLink
import com.example.myapplication.recipeimport.serverCheckTypeToApp
import com.example.myapplication.recipeimport.importedStepCropTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun youtubeLinkGateMatchesServerParser() {
        listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/shorts/dQw4w9WgXcQ",
            " https://youtu.be/dQw4w9WgXcQ "
        ).forEach { assertTrue(it, looksLikeYoutubeLink(it)) }

        listOf(
            "",
            "김치볶음밥",
            // 서버가 스킴 없는 형태를 거부하므로 여기서도 막아 왕복을 아낀다.
            "youtu.be/dQw4w9WgXcQ",
            "https://vimeo.com/12345",
            "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"
        ).forEach { assertFalse(it, looksLikeYoutubeLink(it)) }
    }
}
