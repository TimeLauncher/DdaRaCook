package com.example.myapplication

/**
 * 발표 영상 전용 자동모드 구성.
 *
 * 실제 카메라와 판정 서버를 사용하지 않으며, 이 파일과 연결 지점만 제거하면 기능 전체를
 * 걷어낼 수 있도록 제품 레시피 정의와 분리한다.
 */
internal object PresentationSimulation {
    const val CARD_ID = "presentation-sausage-simulation"
    const val RECIPE_ID = "sausage-vegetable-stir-fry"
    const val EVENT_TYPE = "PRESENTATION_SIMULATION"

    fun homeCard(recipes: List<Recipe>): Recipe? = recipes
        .firstOrNull { it.id == RECIPE_ID }
        ?.copy(
            id = CARD_ID,
            title = "소세지 야채볶음",
            heroNote = "발표 영상용 자동모드",
            isMvpReady = false
        )

    fun captureImageResource(stepOrder: Int): Int? = when (stepOrder) {
        1 -> R.drawable.presentation_sausage_step_1
        2 -> R.drawable.presentation_sausage_step_2
        3 -> R.drawable.presentation_sausage_step_3
        4 -> R.drawable.presentation_sausage_step_4
        5 -> R.drawable.presentation_sausage_step_5
        else -> null
    }
}
