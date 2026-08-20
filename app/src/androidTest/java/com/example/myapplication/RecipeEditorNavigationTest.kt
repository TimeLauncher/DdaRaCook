package com.example.myapplication

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
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
    fun bottomNavigationGapConsumesTapWithoutOpeningContentBehindIt() {
        composeRule.onNodeWithText("오늘 저녁, 준비됐나요?").fetchSemanticsNode()

        composeRule.onNodeWithContentDescription("하단 탐색 영역").performTouchInput {
            click(percentOffset(0.25f, 0.5f))
        }

        composeRule.onNodeWithText("오늘 저녁, 준비됐나요?").fetchSemanticsNode()
    }

    @Test
    fun homeOpensRecipeEditorAndSystemBackReturnsHome() {
        composeRule.onNodeWithText("오늘 저녁, 준비됐나요?").fetchSemanticsNode()
        composeRule.onNodeWithText("레시피").performClick()
        composeRule.onNodeWithText("내 레시피").fetchSemanticsNode()
        composeRule.onNodeWithContentDescription("소세지야채볶음 대표 이미지").fetchSemanticsNode()
        composeRule.onNodeWithText("추가").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("추가").performClick()
        composeRule.onNodeWithText("레시피 제목").fetchSemanticsNode()
        assertTrue(composeRule.onAllNodesWithText("요리 단계").fetchSemanticsNodes().isNotEmpty())

        pressBack()

        composeRule.onNodeWithText("오늘 저녁, 준비됐나요?").fetchSemanticsNode()
    }

    @Test
    fun myPageOpensRequestedFeaturesAndSystemBackReturnsHome() {
        composeRule.onNodeWithText("마이").performClick()

        composeRule.onNodeWithContentDescription("찜한 레시피 메뉴").fetchSemanticsNode()
        composeRule.onNodeWithContentDescription("최근 본 레시피 메뉴").fetchSemanticsNode()
        composeRule.onNodeWithContentDescription("설정").performClick()
        composeRule.onNodeWithText("음성 안내").fetchSemanticsNode()

        pressBack()
        composeRule.onNodeWithContentDescription("찜한 레시피 메뉴").fetchSemanticsNode()
        pressBack()
        composeRule.onNodeWithText("오늘 저녁, 준비됐나요?").fetchSemanticsNode()
    }

    @Test
    fun homeBookmarkScrapsRecipeAndShowsItOnMyPage() {
        composeRule.onNodeWithText("레시피").performClick()
        val wasScrapped = composeRule
            .onAllNodesWithContentDescription("스크랩 해제: 소세지야채볶음")
            .fetchSemanticsNodes()
            .isNotEmpty()
        if (wasScrapped) {
            composeRule.onNodeWithContentDescription("스크랩 해제: 소세지야채볶음").performClick()
        }
        composeRule.onNodeWithContentDescription("스크랩 추가: 소세지야채볶음").performClick()
        composeRule.onNodeWithContentDescription("스크랩 해제: 소세지야채볶음").fetchSemanticsNode()

        composeRule.onNodeWithText("마이").performClick()
        composeRule.onNodeWithContentDescription("찜한 레시피 메뉴").performClick()
        composeRule.onNodeWithText("소세지야채볶음").fetchSemanticsNode()

        pressBack()
        pressBack()
        if (!wasScrapped) {
            composeRule.onNodeWithText("레시피").performClick()
            composeRule.onNodeWithContentDescription("스크랩 해제: 소세지야채볶음").performClick()
            composeRule.onNodeWithContentDescription("스크랩 추가: 소세지야채볶음").fetchSemanticsNode()
        }
    }

    @Test
    fun openingRecipeImmediatelyAddsItToViewedRecipes() {
        composeRule.onNodeWithText("레시피").performClick()
        composeRule.onNodeWithContentDescription("우삼겹 파스타 레시피 카드").performClick()
        composeRule.onNodeWithText("우삼겹 파스타").fetchSemanticsNode()

        pressBack()
        composeRule.onNodeWithText("마이").performClick()
        composeRule.onNodeWithContentDescription("최근 본 레시피 메뉴").performClick()

        composeRule.onNodeWithText("우삼겹 파스타").fetchSemanticsNode()
    }

    @Test
    fun presentationCardUsesScriptedPhotoWithTheSameAutomaticControls() {
        composeRule.onNodeWithText("레시피").performClick()
        composeRule
            .onNodeWithContentDescription("소세지 야채볶음 발표용 레시피 카드")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("확인해줘").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("확인해줘").fetchSemanticsNode()
        assertTrue(composeRule.onAllNodesWithText("지금 할 일").fetchSemanticsNodes().isEmpty())

        assertTrue(
            composeRule.onAllNodesWithContentDescription("1단계 최근 촬영")
                .fetchSemanticsNodes()
                .isEmpty()
        )
        composeRule.waitUntil(timeoutMillis = 4_000L) {
            composeRule.onAllNodesWithContentDescription("1단계 최근 촬영")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val rootWidth = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        val exampleWidth = composeRule.onNodeWithContentDescription("단계 예시 사진")
            .fetchSemanticsNode().boundsInRoot.width
        val captureWidth = composeRule.onNodeWithContentDescription("1단계 최근 촬영")
            .fetchSemanticsNode().boundsInRoot.width
        assertTrue(exampleWidth <= rootWidth * 0.65f)
        assertTrue(captureWidth <= rootWidth * 0.65f)

        composeRule.waitUntil(timeoutMillis = 16_000L) {
            composeRule.onAllNodesWithContentDescription("4단계 최근 촬영")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertTrue(
            composeRule.onAllNodesWithText("눈으로 비교해보세요")
                .fetchSemanticsNodes()
                .isEmpty()
        )
    }
}
