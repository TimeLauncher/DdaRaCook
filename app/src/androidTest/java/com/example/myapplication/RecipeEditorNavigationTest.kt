package com.example.myapplication

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class RecipeEditorNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeOpensRecipeEditor() {
        composeRule.onNodeWithText("내 레시피").fetchSemanticsNode()
        composeRule.onNodeWithText("레시피 추가").performClick()
        composeRule.onNodeWithText("레시피 제목").fetchSemanticsNode()
        composeRule.onNodeWithText("단계 추가").fetchSemanticsNode()
    }
}
