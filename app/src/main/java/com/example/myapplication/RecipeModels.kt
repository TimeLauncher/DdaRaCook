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
    val isAutoCheck: Boolean,
    /**
     * 이 단계를 **마칠 때** 켜지는 병렬 타이머 (`ParallelTimer`).
     *
     * 단계에 들어설 때가 아니라 나갈 때 켠다. "면을 넣으세요"를 듣는 시점에는 아직 면이 물에
     * 들어가지 않았다. 사용자가 "다음"이라고 말한 그 순간이 면이 들어간 순간이다.
     */
    val parallelTimer: ParallelTimer? = null,
    /**
     * 앞 단계에서 켠 병렬 타이머가 끝나야 자동으로 들어설 수 있는 단계인가.
     *
     * 삶아둔 면을 넣는 단계는 면이 익기 전에 시작할 수 없다. 자동 진행만 붙잡고,
     * 사용자가 "다음"이라고 하면 그대로 보낸다 — 판정은 틀릴 수 있고 사용자가 항상 덮어쓴다(F5-1).
     */
    val waitsForParallelTimer: Boolean = false,
    /**
     * 기준 사진을 **단계 시작 즉시** 찍는가.
     *
     * 앞 단계가 "재료를 넣고 다음이라고 말해주세요"인 경우, 사용자가 "다음"이라고 한 그 시점이
     * 곧 재료가 팬에 들어간 시점이다. 기준점을 시간(15초)으로 추측하지 않고 사용자에게 받는다.
     */
    val baselineOnStepStart: Boolean = false
)

/**
 * 단계를 시작할 때 함께 켜지고, **다음 단계로 넘어가도 계속 도는** 시계.
 *
 * 냄비는 단계와 무관하게 끓는다. 면을 넣고 손질 단계로 넘어가도 8분은 계속 흘러야 하고,
 * 다 됐을 때는 그때 진행 중인 단계 위로 안내가 올라와야 한다.
 * 단계 경과 시간(`stepElapsedSeconds`)은 단계가 바뀌면 리셋되므로 이것을 대신할 수 없다.
 *
 * 만료 시각은 세션에 절대 시각으로 남는다(`CookingSession.parallelTimerEndsAtMs`).
 * 앱을 껐다 켜도 냄비는 계속 끓고 있기 때문이다.
 */
@Immutable
data class ParallelTimer(
    /** 조리 화면 남은 시간 칩에 쓰는 짧은 이름. 예: "면 삶기" */
    val label: String,
    val durationSeconds: Int,
    /** 만료 시 읽어주는 문구. F6-6에 따라 2문장 이내. */
    val doneAnnouncement: String
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

/** 검사 차례인데 기준 사진이 아직 없을 때 다시 시도하기까지의 간격. */
internal const val BASELINE_WAIT_RETRY_SECONDS = 5

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
        step.parallelTimer?.let { timer ->
            if (timer.durationSeconds <= 0) add("${step.order}단계 병렬 타이머 시간은 1초 이상이어야 합니다.")
            if (timer.label.isBlank()) add("${step.order}단계 병렬 타이머 이름이 필요합니다.")
            if (timer.doneAnnouncement.isBlank()) add("${step.order}단계 병렬 타이머 완료 안내가 필요합니다.")
        }
        // 기다릴 타이머가 앞에 없으면 자동 진행이 영영 오지 않는다.
        if (step.waitsForParallelTimer && steps.none { it.order < step.order && it.parallelTimer != null }) {
            add("${step.order}단계가 기다릴 병렬 타이머가 앞 단계에 없습니다.")
        }
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
