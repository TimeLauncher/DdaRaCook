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
    val inspectionPolicy: InspectionPolicy?,
    val targetIngredients: List<String>,
    val voicePrompt: String,
    val helperText: String,
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

enum class CheckType(val label: String, val userLabel: String) {
    PRESENCE("존재 여부", "자동 확인"),
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
