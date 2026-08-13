package com.example.myapplication

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue

class RecipeEditorNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeOpensRecipeEditor() {
        composeRule.onNodeWithText("내 레시피").fetchSemanticsNode()
        composeRule.onNodeWithContentDescription("김치볶음밥 대표 이미지").fetchSemanticsNode()
        composeRule.onNodeWithText("레시피 추가").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("레시피 추가").performClick()
        composeRule.onNodeWithText("레시피 제목").fetchSemanticsNode()
        assertTrue(composeRule.onAllNodesWithText("단계 추가").fetchSemanticsNodes().isNotEmpty())
    }
}
