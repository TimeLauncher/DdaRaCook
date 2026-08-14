package com.example.myapplication

object RecipeFixtures {
    fun sampleRecipes(): List<Recipe> {
        return listOf(
            Recipe(
                id = "sausage-vegetable-stir-fry",
                title = "소세지야채볶음",
                heroNote = "대표 MVP 레시피 · 쏘야 5단계 자동 확인 데모",
                isMvpReady = true,
                ingredients = listOf(
                    Ingredient("비엔나소세지", "적당량"),
                    Ingredient("양파", "1/3개"),
                    Ingredient("파프리카", "1/3개"),
                    Ingredient("당근", "1/3개"),
                    Ingredient("식용유", "약간"),
                    Ingredient("케찹", "2큰술"),
                    Ingredient("고추장", "1큰술"),
                    Ingredient("굴소스", "1큰술"),
                    Ingredient("올리고당", "1큰술"),
                    Ingredient("다진마늘", "1/3큰술")
                ),
                steps = listOf(
                    RecipeStep(
                        order = 1,
                        instruction = "야채를 먹기 좋은 크기로 자르고 소세지에 칼집을 낸다",
                        checkType = CheckType.STATE_TRANSITION,
                        checkCondition = "도마 위에 통째로 남은 야채 덩어리가 없는가",
                        needsStartImage = false,
                        inspectionPolicy = InspectionPolicy(60, 40, 2, 2, 300),
                        targetIngredients = listOf("양파", "파프리카", "당근", "비엔나소세지"),
                        voicePrompt = "1단계. 야채를 먹기 좋은 크기로 자르고 소세지에 칼집을 내세요.",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 2,
                        instruction = "양념장을 만든다",
                        checkType = CheckType.TIMER_ONLY,
                        checkCondition = null,
                        needsStartImage = false,
                        inspectionPolicy = null,
                        targetIngredients = listOf("케찹", "고추장", "굴소스", "올리고당", "다진마늘"),
                        voicePrompt = "2단계. 케찹, 고추장, 굴소스, 올리고당과 다진마늘을 섞어 양념장을 만드세요.",
                        isAutoCheck = false
                    ),
                    RecipeStep(
                        order = 3,
                        instruction = "기름을 두르고 야채를 중약불로 볶는다",
                        checkType = CheckType.COLOR_CHANGE,
                        checkCondition = "시작 시점 사진과 비교해 팬 안의 양파가 조금이라도 더 반투명해졌는가",
                        needsStartImage = true,
                        inspectionPolicy = InspectionPolicy(120, 20, 3, 2, 300),
                        targetIngredients = listOf("양파", "파프리카", "당근", "식용유"),
                        voicePrompt = "3단계. 팬에 기름을 두르고 야채를 중약불로 볶으세요.",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 4,
                        instruction = "소세지를 넣고 2~3분간 볶는다",
                        checkType = CheckType.STATE_TRANSITION,
                        checkCondition = "시작 시점 사진과 비교해 소세지 칼집이 벌어졌는가",
                        needsStartImage = true,
                        inspectionPolicy = InspectionPolicy(120, 20, 3, 2, 240),
                        targetIngredients = listOf("비엔나소세지"),
                        voicePrompt = "4단계. 소세지를 넣고 2분에서 3분간 볶으세요.",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 5,
                        instruction = "양념장을 넣고 약불로 3분간 볶는다",
                        checkType = CheckType.TIMER_ONLY,
                        checkCondition = null,
                        needsStartImage = false,
                        inspectionPolicy = InspectionPolicy(180, 30, 2, 1, 180),
                        targetIngredients = listOf("양념장"),
                        voicePrompt = "5단계. 양념장을 넣고 약불에서 3분간 볶되, 연기가 나거나 기포가 심하게 올라오거나 소스가 타 보이면 즉시 불을 약하게 줄이세요.",
                        isAutoCheck = false
                    )
                )
            ),
            Recipe(
                id = "beef-brisket-pasta",
                title = "우삼겹 파스타",
                heroNote = "유튜브 원문 5단계 · 자동 확인 4단계 · 정확도 미측정",
                isMvpReady = false,
                ingredients = listOf(
                    Ingredient("우삼겹", "200g"),
                    Ingredient("대파", "1대"),
                    Ingredient("스파게티면", "1인분"),
                    Ingredient("깻잎", "3장"),
                    Ingredient("다진마늘", "1/2큰술"),
                    Ingredient("설탕", "1/2큰술"),
                    Ingredient("진간장", "1큰술"),
                    Ingredient("굴소스", "1큰술"),
                    Ingredient("후추", "약간")
                ),
                steps = listOf(
                    RecipeStep(
                        order = 1,
                        instruction = "끓는 물에 스파게티면을 넣고 8분 타이머를 건다",
                        checkType = CheckType.TIMER_ONLY,
                        checkCondition = null,
                        needsStartImage = false,
                        inspectionPolicy = InspectionPolicy(30, 20, 2, 1, 60),
                        targetIngredients = listOf("스파게티면"),
                        voicePrompt = "1단계. 끓는 물에 스파게티면을 넣고 다음이라고 말해주세요.",
                        isAutoCheck = false,
                        // 냄비는 여기 두고 손질·볶기로 넘어간다. 8분은 그동안에도 계속 흐른다.
                        parallelTimer = ParallelTimer(
                            label = "면 삶기",
                            durationSeconds = 480,
                            doneAnnouncement = "면 8분이 다 됐어요. 면을 건져 두세요."
                        )
                    ),
                    RecipeStep(
                        order = 2,
                        instruction = "대파를 어슷 썰고 깻잎을 채 썬다",
                        checkType = CheckType.STATE_TRANSITION,
                        checkCondition = "도마 위에 통째로 남은 대파가 없는가",
                        needsStartImage = false,
                        inspectionPolicy = InspectionPolicy(60, 40, 2, 2, 240),
                        targetIngredients = listOf("대파", "깻잎"),
                        voicePrompt = "2단계. 대파를 어슷 썰고 깻잎을 채 썰어 두세요.",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 3,
                        instruction = "팬에 우삼겹과 대파를 넣고 중불로 볶는다",
                        checkType = CheckType.COLOR_CHANGE,
                        checkCondition = "시작 시점 사진과 비교해 팬 안의 우삼겹에서 붉은 부분이 줄었는가",
                        needsStartImage = true,
                        inspectionPolicy = InspectionPolicy(90, 20, 3, 2, 240),
                        targetIngredients = listOf("우삼겹", "대파"),
                        voicePrompt = "3단계. 팬에 우삼겹과 대파를 넣고 중불에서 볶으세요.",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 4,
                        instruction = "다진마늘·설탕·진간장·굴소스를 넣고 볶는다",
                        checkType = CheckType.PRESENCE,
                        checkCondition = "팬 안에 갈색 간장 양념이 들어가 있는가",
                        needsStartImage = false,
                        inspectionPolicy = InspectionPolicy(30, 30, 2, 2, 150),
                        targetIngredients = listOf("다진마늘", "설탕", "진간장", "굴소스"),
                        voicePrompt = "4단계. 다진마늘, 설탕, 진간장, 굴소스를 넣고 볶으세요.",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 5,
                        instruction = "삶아둔 스파게티면을 넣고 양념과 함께 볶는다",
                        checkType = CheckType.COLOR_CHANGE,
                        checkCondition = "시작 시점 사진과 비교해 팬 안의 면이 흰색에서 갈색 양념색으로 물들었는가",
                        needsStartImage = true,
                        inspectionPolicy = InspectionPolicy(60, 20, 3, 2, 180),
                        targetIngredients = listOf("스파게티면"),
                        voicePrompt = "5단계. 삶아둔 스파게티면을 넣고 양념이 배도록 볶으세요.",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 6,
                        instruction = "깻잎과 후추를 올려 마무리한다",
                        checkType = CheckType.TIMER_ONLY,
                        checkCondition = null,
                        needsStartImage = false,
                        inspectionPolicy = InspectionPolicy(30, 20, 2, 1, 60),
                        targetIngredients = listOf("깻잎", "후추"),
                        voicePrompt = "6단계. 깻잎과 후추를 올려 마무리하세요.",
                        isAutoCheck = false
                    )
                )
            ),
            Recipe(
                id = "kimchi",
                title = "김치볶음밥",
                heroNote = "정적 예시 · 시연 대상 아님",
                isMvpReady = false,
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
                        needsStartImage = false,
                        inspectionPolicy = InspectionPolicy(30, 30, 2, 1, 60),
                        targetIngredients = listOf("팬", "식용유"),
                        voicePrompt = "1단계. 팬에 기름을 두르고 달구세요.",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 2,
                        instruction = "양파를 넣는다",
                        checkType = CheckType.PRESENCE,
                        checkCondition = "팬 안에 양파가 들어왔는가",
                        needsStartImage = false,
                        inspectionPolicy = InspectionPolicy(30, 30, 2, 1, 60),
                        targetIngredients = listOf("양파"),
                        voicePrompt = "2단계. 양파를 팬에 넣으세요.",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 3,
                        instruction = "양파가 반투명해질 때까지 볶는다",
                        checkType = CheckType.COLOR_CHANGE,
                        checkCondition = "팬 안의 양파가 흰색에서 반투명 상태로 변했는가",
                        needsStartImage = true,
                        inspectionPolicy = InspectionPolicy(30, 30, 3, 2, 360),
                        targetIngredients = listOf("양파"),
                        voicePrompt = "3단계. 양파가 반투명해질 때까지 볶으세요.",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 4,
                        instruction = "김치를 넣고 볶는다",
                        checkType = CheckType.IDENTIFICATION,
                        checkCondition = "팬 안에 김치가 들어가 볶아지고 있는가",
                        needsStartImage = false,
                        inspectionPolicy = InspectionPolicy(30, 30, 2, 1, 180),
                        targetIngredients = listOf("김치"),
                        voicePrompt = "4단계. 김치를 넣고 볶으세요.",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 5,
                        instruction = "밥을 넣고 김치와 섞는다",
                        checkType = CheckType.PRESENCE,
                        checkCondition = "밥이 팬 안에서 김치와 섞이고 있는가",
                        needsStartImage = false,
                        inspectionPolicy = InspectionPolicy(30, 30, 2, 1, 240),
                        targetIngredients = listOf("밥"),
                        voicePrompt = "5단계. 밥을 넣고 고르게 섞으세요.",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 6,
                        instruction = "참기름을 넣고 마무리한다",
                        checkType = CheckType.TIMER_ONLY,
                        checkCondition = null,
                        needsStartImage = false,
                        inspectionPolicy = InspectionPolicy(30, 20, 2, 1, 120),
                        targetIngredients = listOf("참기름"),
                        voicePrompt = "6단계. 참기름을 넣고 마무리하세요.",
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
                        needsStartImage = false,
                        inspectionPolicy = InspectionPolicy(30, 30, 2, 1, 300),
                        targetIngredients = listOf("냄비"),
                        voicePrompt = "육수가 끓기 시작하면 다음 단계로 갑니다.",
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
                        needsStartImage = false,
                        inspectionPolicy = InspectionPolicy(30, 20, 2, 1, 120),
                        targetIngredients = listOf("계란물"),
                        voicePrompt = "계란물을 준비한 뒤 팬으로 이동합니다.",
                        isAutoCheck = false
                    )
                )
            )
        )
    }
}
