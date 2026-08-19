package com.example.myapplication

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecipeEditorNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeOpensRecipeEditor() {
        composeRule.onNodeWithText("내 레시피").fetchSemanticsNode()
        composeRule.onNodeWithContentDescription("소세지야채볶음 대표 이미지").fetchSemanticsNode()
        composeRule.onNodeWithText("추가").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("추가").performClick()
        composeRule.onNodeWithText("레시피 제목").fetchSemanticsNode()
        assertTrue(composeRule.onAllNodesWithText("요리 단계").fetchSemanticsNodes().isNotEmpty())
    }
}
