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
                        inspectionPolicy = InspectionPolicy(60, 40, 2, 1, 300),
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
                    // 재료가 팬에 들어간 시점을 A등급 존재 판정으로 확정한다.
                    // 그 확정 시점이 다음 단계의 기준 사진이 된다 — 15초를 세지 않는다.
                    RecipeStep(
                        order = 3,
                        instruction = "팬에 기름을 두르고 야채와 소세지를 넣는다.",
                        checkType = CheckType.PRESENCE,
                        checkCondition = "팬 안에 소세지와 썬 야채가 들어있는가",
                        needsStartImage = false,
                        inspectionPolicy = InspectionPolicy(30, 30, 2, 1, 90),
                        targetIngredients = listOf("양파", "파프리카", "당근", "비엔나소세지", "식용유"),
                        voicePrompt = "3단계. 팬에 기름을 두르고 야채와 소세지를 넣으세요.",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 4,
                        instruction = "야채와 소세지를 중약불로 볶는다",
                        checkType = CheckType.STATE_TRANSITION,
                        checkCondition = "시작 시점 사진과 비교해 소세지 칼집이 벌어졌는가",
                        needsStartImage = true,
                        inspectionPolicy = InspectionPolicy(120, 20, 3, 1, 240),
                        targetIngredients = listOf("양파", "파프리카", "당근", "비엔나소세지"),
                        voicePrompt = "4단계. 야채와 소세지를 중약불로 볶으세요.",
                        isAutoCheck = true,
                        baselineOnStepStart = true
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
                heroNote = "유튜브 원문 5단계 · 자동 확인 4단계 · 기준점은 사용자 신호",
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
                        instruction = "끓는 물에 스파게티면을 넣는다",
                        checkType = CheckType.PRESENCE,
                        checkCondition = "끓는 물에 스파게티면이 들어가 있는가",
                        needsStartImage = false,
                        inspectionPolicy = InspectionPolicy(30, 30, 2, 1, 90),
                        targetIngredients = listOf("스파게티면"),
                        voicePrompt = "1단계. 끓는 물에 스파게티면을 넣으세요.",
                        isAutoCheck = true,
                        // "다음"이라고 한 순간 = 면이 물에 들어간 순간. 그때부터 8분을 센다.
                        // 냄비는 여기 두고 손질·볶기로 넘어가며, 8분은 그동안에도 계속 흐른다.
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
                        inspectionPolicy = InspectionPolicy(60, 40, 2, 1, 240),
                        targetIngredients = listOf("대파", "깻잎"),
                        voicePrompt = "2단계. 대파를 어슷 썰고 깻잎을 채 썰어 두세요.",
                        isAutoCheck = true
                    ),
                    // 재료를 넣는 순간은 사용자만 안다. 투입을 별도 단계로 두고 "다음"을 받는다.
                    // 그 "다음"이 곧 기준 사진 시점이므로 다음 단계는 baselineOnStepStart 다.
                    RecipeStep(
                        order = 3,
                        instruction = "팬에 우삼겹과 대파를 넣는다",
                        checkType = CheckType.PRESENCE,
                        checkCondition = "팬에 우삼겹과 대파가 들어가 있는가",
                        needsStartImage = false,
                        inspectionPolicy = InspectionPolicy(30, 30, 2, 1, 90),
                        targetIngredients = listOf("우삼겹", "대파"),
                        voicePrompt = "3단계. 팬에 우삼겹과 대파를 넣으세요.",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 4,
                        instruction = "중불로 볶는다",
                        checkType = CheckType.COLOR_CHANGE,
                        checkCondition = "시작 시점 사진과 비교해 팬 안의 우삼겹에서 붉은 부분이 줄었는가",
                        needsStartImage = true,
                        inspectionPolicy = InspectionPolicy(90, 20, 3, 1, 240),
                        targetIngredients = listOf("우삼겹", "대파"),
                        voicePrompt = "4단계. 중불에서 볶으세요.",
                        isAutoCheck = true,
                        baselineOnStepStart = true
                    ),
                    RecipeStep(
                        order = 5,
                        instruction = "다진마늘·설탕·진간장·굴소스를 넣고 볶는다",
                        checkType = CheckType.PRESENCE,
                        checkCondition = "팬 안에 갈색 간장 양념이 들어가 있는가",
                        needsStartImage = false,
                        inspectionPolicy = InspectionPolicy(30, 30, 2, 1, 150),
                        targetIngredients = listOf("다진마늘", "설탕", "진간장", "굴소스"),
                        voicePrompt = "5단계. 다진마늘, 설탕, 진간장, 굴소스를 넣고 볶으세요.",
                        isAutoCheck = true
                    ),
                    RecipeStep(
                        order = 6,
                        instruction = "삶아둔 스파게티면을 건져 팬에 넣는다",
                        checkType = CheckType.PRESENCE,
                        checkCondition = "면이 팬의 재료 위로 올라가 있는가",
                        needsStartImage = false,
                        inspectionPolicy = InspectionPolicy(30, 30, 2, 1, 90),
                        targetIngredients = listOf("스파게티면"),
                        voicePrompt = "6단계. 삶아둔 면을 건져 팬에 넣으세요.",
                        isAutoCheck = true,
                        // 면이 익기 전에는 넣을 수 없다. 1단계에서 건 타이머가 끝나야 들어선다.
                        waitsForParallelTimer = true
                    ),
                    RecipeStep(
                        order = 7,
                        instruction = "양념이 배도록 볶는다",
                        checkType = CheckType.COLOR_CHANGE,
                        checkCondition = "시작 시점 사진과 비교해 팬 안의 면이 흰색에서 갈색 양념색으로 물들었는가",
                        needsStartImage = true,
                        inspectionPolicy = InspectionPolicy(60, 20, 3, 1, 180),
                        targetIngredients = listOf("스파게티면"),
                        voicePrompt = "7단계. 양념이 배도록 볶으세요.",
                        isAutoCheck = true,
                        baselineOnStepStart = true
                    ),
                    RecipeStep(
                        order = 8,
                        instruction = "깻잎과 후추를 올려 마무리한다",
                        checkType = CheckType.PRESENCE,
                        checkCondition = "완성된 파스타 위에 깻잎이 올라가 있는가",
                        needsStartImage = false,
                        inspectionPolicy = InspectionPolicy(30, 30, 2, 1, 90),
                        targetIngredients = listOf("깻잎", "후추"),
                        voicePrompt = "8단계. 깻잎과 후추를 올려 마무리하세요.",
                        isAutoCheck = true
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
