package com.example.myapplication

import androidx.compose.runtime.Immutable

@Immutable
data class Recipe(
    val id: String,
    val title: String,
    val ingredients: List<Ingredient>,
    val steps: List<RecipeStep>,
    val heroNote: String,
    val isMvpReady: Boolean
) {
    val totalDurationLabel: String
        get() = "${steps.sumOf { it.inspectionPolicy?.maxExpectedSeconds ?: 0 } / 60}분"
}

@Immutable
data class Ingredient(
    val name: String,
    val amount: String
)

@Immutable
data class RecipeStep(
    val order: Int,
    val instruction: String,
    val checkType: CheckType,
    val checkCondition: String?,
    /**
     * 완료 조건이 **시작 시점 대비 변화**를 묻는가 (CONTRACT §3.2).
     *
     * 판정 유형으로는 가를 수 없다. 쏘야 1단계와 4단계는 둘 다 STATE_TRANSITION 이지만
     * 1단계는 "덩어리가 없는가"(절대 판정)이고 4단계는 "처음보다 벌어졌는가"(상대 판정)다.
     * true 면 기준 사진을 단계 시작 직후가 아니라 재료가 팬에 들어간 뒤에 찍고,
     * 판정 요청에 `startImage` 로 함께 보낸다.
     */
    val needsStartImage: Boolean,
    val inspectionPolicy: InspectionPolicy?,
    val targetIngredients: List<String>,
    val voicePrompt: String,
    val isAutoCheck: Boolean
)

@Immutable
data class InspectionPolicy(
    val earliestCheckSeconds: Int,
    val checkIntervalSeconds: Int,
    val burstSeconds: Int,
    val requiredConsecutiveDone: Int,
    val maxExpectedSeconds: Int
)

internal const val AUTOMATIC_INSPECTION_INTERVAL_SECONDS = 30

/**
 * `needsStartImage` 단계에서 기준 사진을 찍기까지 기다리는 시간.
 *
 * 단계 안내가 나간 직후에는 아직 재료가 팬에 들어가지 않았다. 그 상태를 기준으로 삼으면
 * "시작 시점 대비 변화"를 물을 수 없다. 재료가 들어갈 만한 시간을 준 뒤에 찍는다.
 */
internal const val BASELINE_CAPTURE_DELAY_SECONDS = 15

internal fun List<Recipe>.withAutomaticInspectionInterval(): List<Recipe> = map { recipe ->
    recipe.copy(
        steps = recipe.steps.map { step ->
            val policy = step.inspectionPolicy
            if (!step.isAutoCheck || policy == null) {
                step
            } else {
                step.copy(
                    inspectionPolicy = policy.copy(
                        earliestCheckSeconds = AUTOMATIC_INSPECTION_INTERVAL_SECONDS,
                        checkIntervalSeconds = AUTOMATIC_INSPECTION_INTERVAL_SECONDS,
                        maxExpectedSeconds = maxOf(
                            policy.maxExpectedSeconds,
                            AUTOMATIC_INSPECTION_INTERVAL_SECONDS
                        )
                    )
                )
            }
        }
    )
}

enum class CheckType(val label: String, val userLabel: String) {
    PRESENCE("존재 여부", "자동 확인"),
    COUNT("개수 확인", "자동 확인"),
    IDENTIFICATION("재료 식별", "자동 확인"),
    COLOR_CHANGE("색상 변화", "확인 보조"),
    STATE_TRANSITION("상태 전환", "확인 보조"),
    TIMER_ONLY("시간 전용", "수동 진행");
}

enum class JudgmentVerdict {
    DONE,
    NOT_DONE,
    CANNOT_TELL
}

enum class ReasonCode(val label: String) {
    VISIBLE_CHANGE("변화 확인"),
    NO_CHANGE("변화 없음"),
    TARGET_NOT_VISIBLE("대상이 안 보임"),
    BLURRY("흐림"),
    CONDITION_NOT_MET("조건 미충족"),
    MODEL_UNCERTAIN("모델 불확실"),
    NETWORK_RETRY("네트워크 재시도"),
    OTHER("기타")
}

fun Recipe.validationErrors(): List<String> = buildList {
    if (title.isBlank()) add("레시피 제목이 필요합니다.")
    if (steps.isEmpty()) add("조리 단계가 한 개 이상 필요합니다.")
    steps.forEach { step ->
        if (step.instruction.isBlank()) add("${step.order}단계 안내 문구가 필요합니다.")
        if (step.isAutoCheck && step.checkType != CheckType.TIMER_ONLY) {
            if (step.checkCondition.isNullOrBlank()) add("${step.order}단계 완료 조건이 필요합니다.")
            val policy = step.inspectionPolicy
            if (policy == null) add("${step.order}단계 자동 검사 정책이 필요합니다.")
            else {
                if (policy.earliestCheckSeconds < 0) add("${step.order}단계 최초 검사 시간은 0 이상이어야 합니다.")
                if (policy.checkIntervalSeconds <= 0) add("${step.order}단계 재검사 간격은 1초 이상이어야 합니다.")
                if (policy.requiredConsecutiveDone <= 0) add("${step.order}단계 연속 DONE 기준은 1 이상이어야 합니다.")
                if (policy.maxExpectedSeconds < policy.earliestCheckSeconds) add("${step.order}단계 최대 시간은 최초 검사 시간 이상이어야 합니다.")
            }
        }
    }
}
