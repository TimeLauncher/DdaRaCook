package com.example.myapplication

object RecipeFixtures {
    fun sampleRecipes(): List<Recipe> {
        return listOf(
            Recipe(
                id = "kimchi",
                title = "김치볶음밥",
                heroNote = "대표 MVP 레시피 · 6단계 자동 확인 데모",
                isMvpReady = true,
                ingredients = listOf(
                    Ingredient("김치", "1컵"),
                    Ingredient("밥", "2공기"),
                    Ingredient("양파", "1/2개"),
                    Ingredient("대파", "1대"),
                    Ingredient("계란", "2개"),
                    Ingredient("참기름", "약간")
                ),
                steps = listOf(
                    RecipeStep(
                        order = 1,
                        instruction = "팬에 기름을 두르고 달군다",
                        checkType = CheckType.STATE_TRANSITION,
                        checkCondition = "팬 표면에 기름이 얇게 퍼지고 열이 올라왔는가",
                        inspectionPolicy = InspectionPolicy(15, 10, 2, 1, 60),
                        targetIngredients = listOf("팬", "식용유"),
                        voicePrompt = "1단계. 팬에 기름을 두르고 달구세요.",
                        helperText = "최초 15초 후 상태를 확인합니다",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 2,
                        instruction = "양파를 넣는다",
                        checkType = CheckType.PRESENCE,
                        checkCondition = "팬 안에 양파가 들어왔는가",
                        inspectionPolicy = InspectionPolicy(10, 8, 2, 1, 45),
                        targetIngredients = listOf("양파"),
                        voicePrompt = "2단계. 양파를 팬에 넣으세요.",
                        helperText = "양파가 보이면 바로 다음 단계로 넘길 수 있습니다",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 3,
                        instruction = "양파가 반투명해질 때까지 볶는다",
                        checkType = CheckType.COLOR_CHANGE,
                        checkCondition = "팬 안의 양파가 흰색에서 반투명 상태로 변했는가",
                        inspectionPolicy = InspectionPolicy(40, 15, 3, 2, 360),
                        targetIngredients = listOf("양파"),
                        voicePrompt = "3단계. 양파가 반투명해질 때까지 볶으세요.",
                        helperText = "검사 시점에만 카메라를 2~3초 사용합니다",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 4,
                        instruction = "김치를 넣고 볶는다",
                        checkType = CheckType.IDENTIFICATION,
                        checkCondition = "팬 안에 김치가 들어가 볶아지고 있는가",
                        inspectionPolicy = InspectionPolicy(10, 12, 2, 1, 180),
                        targetIngredients = listOf("김치"),
                        voicePrompt = "4단계. 김치를 넣고 볶으세요.",
                        helperText = "김치 존재를 1회 DONE으로 확인합니다",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 5,
                        instruction = "밥을 넣고 김치와 섞는다",
                        checkType = CheckType.PRESENCE,
                        checkCondition = "밥이 팬 안에서 김치와 섞이고 있는가",
                        inspectionPolicy = InspectionPolicy(15, 15, 2, 1, 240),
                        targetIngredients = listOf("밥"),
                        voicePrompt = "5단계. 밥을 넣고 고르게 섞으세요.",
                        helperText = "예상 시간을 넘기면 계속할지 안내합니다",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 6,
                        instruction = "참기름을 넣고 마무리한다",
                        checkType = CheckType.TIMER_ONLY,
                        checkCondition = null,
                        inspectionPolicy = InspectionPolicy(30, 20, 2, 1, 120),
                        targetIngredients = listOf("참기름"),
                        voicePrompt = "6단계. 참기름을 넣고 마무리하세요.",
                        helperText = "마지막 단계는 수동 진행으로 마무리합니다",
                        isAutoCheck = false
                    )
                )
            ),
            Recipe(
                id = "doenjang",
                title = "된장찌개",
                heroNote = "정적 예시 · 시연 대상 아님",
                isMvpReady = false,
                ingredients = listOf(
                    Ingredient("된장", "2큰술"),
                    Ingredient("애호박", "1/3개"),
                    Ingredient("두부", "1/2모"),
                    Ingredient("양파", "1/2개")
                ),
                steps = listOf(
                    RecipeStep(
                        order = 1,
                        instruction = "육수를 끓인다",
                        checkType = CheckType.STATE_TRANSITION,
                        checkCondition = "육수가 끓기 시작했는가",
                        inspectionPolicy = InspectionPolicy(45, 20, 2, 1, 300),
                        targetIngredients = listOf("냄비"),
                        voicePrompt = "육수가 끓기 시작하면 다음 단계로 갑니다.",
                        helperText = "예시 레시피",
                        isAutoCheck = true
                    )
                )
            ),
            Recipe(
                id = "eggroll",
                title = "계란말이",
                heroNote = "정적 예시 · 시연 대상 아님",
                isMvpReady = false,
                ingredients = listOf(
                    Ingredient("계란", "4개"),
                    Ingredient("대파", "약간"),
                    Ingredient("식용유", "약간")
                ),
                steps = listOf(
                    RecipeStep(
                        order = 1,
                        instruction = "계란을 풀고 팬을 준비한다",
                        checkType = CheckType.TIMER_ONLY,
                        checkCondition = null,
                        inspectionPolicy = InspectionPolicy(30, 20, 2, 1, 120),
                        targetIngredients = listOf("계란물"),
                        voicePrompt = "계란물을 준비한 뒤 팬으로 이동합니다.",
                        helperText = "예시 레시피",
                        isAutoCheck = false
                    )
                )
            )
        )
    }
}
