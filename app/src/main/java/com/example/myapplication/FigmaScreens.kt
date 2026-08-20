package com.example.myapplication

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.camera.CaptureOutcome
import com.example.myapplication.camera.WearableCameraState
import com.example.myapplication.judgment.ImageNormalizer
import com.example.myapplication.voice.WakeWordStatus
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val FigmaOrange = Color(0xFFF0872D)
private val FigmaInk = Color(0xFF242424)
private val FigmaMuted = Color(0xFF626262)
private val FigmaSurface = Color(0xFFF8F8F8)
private val FigmaWarm = Color(0xFFFFF8F0)
private val FigmaWarmIcon = Color(0xFFFFEBD4)
private val FigmaDivider = Color(0xFFEBEBEB)
private val FigmaGreen = Color(0xFF3F7D32)
private const val SERVER_IMAGE_ASPECT_RATIO = 1024f / 819f
private val FigmaGreenSurface = Color(0xFFE8F7E4)
private val FigmaYellow = Color(0xFFF2B81A)
private val FigmaYellowSurface = Color(0xFFFAF5E5)

private fun figmaRecipeImageResource(recipe: Recipe?, fallbackResource: Int): Int {
    return figmaHomeRecipeImageResource(recipe, fallbackResource)
}

private fun figmaHomeRecipeImageResource(recipe: Recipe?, fallbackResource: Int): Int {
    val normalizedTitle = recipe?.title?.replace(" ", "").orEmpty()
    return when {
        recipe?.id == "sausage-vegetable-stir-fry" || normalizedTitle.contains("소세지야채볶음") ->
            R.drawable.sausage_vegetable_stir_fry
        recipe?.id == "beef-brisket-pasta" || normalizedTitle.contains("우삼겹파스타") ->
            R.drawable.beef_brisket_pasta
        normalizedTitle.contains("김치볶음밥") -> R.drawable.kimchi_fried_rice
        recipe?.id == "doenjang" || normalizedTitle.contains("된장찌개") || normalizedTitle.contains("된장찌게") ->
            R.drawable.doenjang_stew
        recipe?.id == "eggroll" || normalizedTitle.contains("계란말이") -> R.drawable.rolled_omelette
        else -> fallbackResource
    }
}

private fun figmaSummaryStepImageResource(recipe: Recipe, stepOrder: Int): Int? {
    val normalizedTitle = recipe.title.replace(" ", "")
    if (recipe.id != "sausage-vegetable-stir-fry" && !normalizedTitle.contains("소세지야채볶음")) {
        return null
    }
    return when (stepOrder) {
        1 -> R.drawable.sausage_step_1_replacement
        2 -> R.drawable.sausage_step_2_replacement
        3 -> R.drawable.sausage_step_5
        4 -> R.drawable.sausage_step_6
        5 -> R.drawable.sausage_step_7
        else -> null
    }
}

@Composable
internal fun FigmaServiceHomeScreen(
    uiState: CookingSessionUiState,
    onRecipeClick: (String) -> Unit,
    onRecipes: () -> Unit,
    onAddRecipe: () -> Unit,
    onMy: () -> Unit,
    onResume: () -> Unit
) {
    val heroRecipe = uiState.recipes.firstOrNull {
        it.id == "sausage-vegetable-stir-fry" || it.title.replace(" ", "").contains("소세지야채볶음")
    } ?: uiState.recipes.firstOrNull()
    val connectionLabel = figmaGlassesConnectionLabel(uiState.cameraState)
    val connected = connectionLabel == "안경 연결됨"
    val resumeSession = uiState.session
    val resumeRecipe = uiState.selectedRecipe

    Box(Modifier.fillMaxSize().background(Color(0xFFFFFCF7))) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 82.dp)
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("따라쿡", color = FigmaOrange, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "● $connectionLabel",
                        color = if (connected) FigmaGreen else FigmaMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (connected) FigmaGreenSurface else FigmaSurface)
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
                Spacer(Modifier.height(9.dp))
                Text("오늘 저녁, 준비됐나요?", color = FigmaInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(174.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFFFFE7C3))
                        .clickable(enabled = heroRecipe != null) { heroRecipe?.let { onRecipeClick(it.id) } }
                        .padding(start = 18.dp, top = 17.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("TODAY · 오늘의 요리", color = Color(0xFFC4470E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text("소시지 야채볶음", color = FigmaInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text("약 20분 · 재료 5/7 준비", color = Color(0xFF765B45), fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "안경으로 요리 시작  →",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(15.dp))
                                .background(FigmaOrange)
                                .padding(horizontal = 13.dp, vertical = 9.dp)
                        )
                    }
                    Image(
                        painter = painterResource(R.drawable.figma_service_home_mascot),
                        contentDescription = "따라쿡 요리 마스코트",
                        modifier = Modifier.width(118.dp).height(138.dp),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(Modifier.height(22.dp))
                FigmaHomeSectionHeader("이번 주 요리 계획", "식단 편집  ›")
                Spacer(Modifier.height(9.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FigmaWeeklyPlanCard("오늘", "김치볶음밥", true, Modifier.weight(1f))
                    FigmaWeeklyPlanCard("금요일", "계란말이", false, Modifier.weight(1f))
                    FigmaWeeklyPlanCard("토요일", "비빔국수", false, Modifier.weight(1f))
                }

                Spacer(Modifier.height(22.dp))
                FigmaHomeSectionHeader("바로 실행")
                Spacer(Modifier.height(9.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FigmaQuickAction("✦", "재료로", "요리 추천", Color(0xFFEAF7E6), FigmaGreen, Modifier.weight(1f), onRecipes)
                    FigmaQuickAction("▦", "이번 주", "식단 보기", Color(0xFFEAF2FF), Color(0xFF3869A9), Modifier.weight(1f), {})
                    FigmaQuickAction("✓", "장보기", "2개 남음", Color(0xFFFFEEE0), Color(0xFFCB6921), Modifier.weight(1f), {})
                }

                Spacer(Modifier.height(22.dp))
                FigmaHomeSectionHeader("우리 집 재료로 뭐 해먹지?")
                Spacer(Modifier.height(9.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .border(1.dp, FigmaDivider, RoundedCornerShape(20.dp))
                        .padding(15.dp)
                ) {
                    Text("등록된 재료 6개", color = FigmaInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Text("있는 재료를 조합해 오늘 만들 요리를 골라보세요", color = FigmaMuted, fontSize = 10.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FigmaIngredientChip("김치")
                        FigmaIngredientChip("달걀")
                        FigmaIngredientChip("대파")
                    }
                    Spacer(Modifier.height(11.dp))
                    Text(
                        "재료로 추천받기  →",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(15.dp))
                            .background(FigmaOrange)
                            .clickable(onClick = onRecipes)
                            .padding(horizontal = 13.dp, vertical = 9.dp)
                    )
                }

                if (uiState.hasResumableSession && resumeSession != null && resumeRecipe != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(76.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(FigmaInk)
                            .clickable(onClick = onResume)
                            .padding(horizontal = 15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "진행 중 · ${(resumeSession.currentStepIndex + 1).coerceAtMost(resumeRecipe.steps.size)}/${resumeRecipe.steps.size} 단계",
                                color = Color(0xFFFFB16C),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(resumeRecipe.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("이어하기  ›", color = FigmaOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        FigmaBottomNavigation(
            modifier = Modifier.align(Alignment.BottomCenter),
            selected = FigmaNavDestination.HOME,
            onHome = {},
            onRecipes = onRecipes,
            onAddRecipe = onAddRecipe,
            onMy = onMy
        )
    }
}

@Composable
private fun FigmaHomeSectionHeader(title: String, action: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = FigmaInk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        action?.let { Text(it, color = FigmaOrange, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun FigmaWeeklyPlanCard(day: String, dish: String, selected: Boolean, modifier: Modifier) {
    Column(
        modifier
            .height(78.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color(0xFFFFE7CF) else Color.White)
            .border(1.dp, if (selected) Color(0xFFF4B27C) else FigmaDivider, RoundedCornerShape(16.dp))
            .padding(horizontal = 11.dp, vertical = 12.dp)
    ) {
        Text(day, color = if (selected) FigmaOrange else FigmaMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(dish, color = FigmaInk, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun FigmaQuickAction(
    symbol: String,
    title: String,
    detail: String,
    background: Color,
    accent: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .height(92.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Text(symbol, color = accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(title, color = FigmaInk, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(detail, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FigmaIngredientChip(label: String) {
    Text(
        label,
        color = Color(0xFF6A5545),
        fontSize = 10.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFFF3E7))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
internal fun FigmaRecipeScreen(
    uiState: CookingSessionUiState,
    onRecipeClick: (String) -> Unit,
    onToggleScrap: (String) -> Unit,
    onHome: () -> Unit,
    onAddRecipe: () -> Unit,
    onMy: () -> Unit,
    onPresentationSimulation: () -> Unit,
    onResume: () -> Unit
) {
    val presentationCard = PresentationSimulation.homeCard(uiState.recipes)
    val homeRecipes = buildList {
        uiState.recipes.forEach { recipe ->
            add(recipe)
            if (recipe.id == "eggroll" && presentationCard != null) add(presentationCard)
        }
        if (presentationCard != null && none { it.id == presentationCard.id }) add(presentationCard)
    }
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 94.dp)
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("따라쿡", color = FigmaOrange, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    FigmaResourceIcon(R.drawable.figma_icon_search, "검색", 24.dp)
                }
                Spacer(Modifier.height(10.dp))
                Text("오늘은 무엇을 만들까요?", color = FigmaInk, fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("안경과 함께, 필요한 순간만 확인해요", color = FigmaMuted, fontSize = 13.sp)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FigmaChip("전체", selected = true)
                    FigmaChip("한식")
                    FigmaChip("20분 이하")
                    FigmaChip("초보 추천")
                }
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(32.dp), color = FigmaOrange)
            } else if (uiState.loadError != null) {
                FigmaMessageCard("레시피를 불러오지 못했습니다", uiState.loadError, Modifier.padding(20.dp))
            }

            if (uiState.hasResumableSession && uiState.session != null && uiState.selectedRecipe != null) {
                val recipe = uiState.selectedRecipe ?: return@Column
                val step = uiState.currentStep
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
                    Text("이어서 요리할까요?", color = FigmaInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(20.dp)).background(FigmaWarm).clickable(onClick = onResume)
                    ) {
                        FigmaResourceImage(
                            figmaHomeRecipeImageResource(recipe, R.drawable.figma_home_resume),
                            "${recipe.title} 진행 중인 요리",
                            Modifier.width(148.dp).height(170.dp),
                            0.dp
                        )
                        Column(Modifier.padding(14.dp).weight(1f)) {
                            Text("진행 중 · ${uiState.session.currentStepIndex + 1} / ${recipe.steps.size}단계", color = FigmaOrange, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(7.dp))
                            Text(recipe.title, color = FigmaInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(step?.instruction.orEmpty(), color = FigmaInk, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                            Spacer(Modifier.weight(1f))
                            FigmaSmallButton("이어하기", onResume)
                        }
                    }
                }
            }

            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
                Text("내 레시피", color = FigmaInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                if (uiState.recipes.isEmpty() && !uiState.isLoading) {
                    FigmaMessageCard("저장된 레시피가 없어요", "아래 추가 버튼으로 첫 레시피를 만들어 보세요.")
                }
                homeRecipes.chunked(2).forEachIndexed { rowIndex, recipes ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        recipes.forEachIndexed { index, recipe ->
                            val isPresentationCard = recipe.id == PresentationSimulation.CARD_ID
                            val fallbackImage = if ((rowIndex * 2 + index) % 2 == 0) R.drawable.figma_home_resume else R.drawable.figma_home_onion
                            val image = figmaHomeRecipeImageResource(recipe, fallbackImage)
                            FigmaRecipeCard(
                                recipe = recipe,
                                imageRes = image,
                                isScrapped = recipe.id in uiState.scrappedRecipeIds,
                                badge = if (isPresentationCard) "발표용" else null,
                                modifier = Modifier.weight(1f),
                                onRecipeClick = if (isPresentationCard) {
                                    { _: String -> onPresentationSimulation() }
                                } else {
                                    onRecipeClick
                                },
                                onToggleScrap = if (isPresentationCard) null else onToggleScrap
                            )
                        }
                        if (recipes.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        FigmaBottomNavigation(
            modifier = Modifier.align(Alignment.BottomCenter),
            selected = FigmaNavDestination.RECIPES,
            onHome = onHome,
            onRecipes = {},
            onAddRecipe = onAddRecipe,
            onMy = onMy
        )
    }
}

@Composable
private fun FigmaRecipeCard(
    recipe: Recipe,
    imageRes: Int,
    isScrapped: Boolean,
    badge: String? = null,
    modifier: Modifier,
    onRecipeClick: (String) -> Unit,
    onToggleScrap: ((String) -> Unit)?
) {
    Column(
        modifier
            .height(264.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(FigmaSurface)
            .semantics {
                contentDescription = if (badge != null) {
                    "${recipe.title} 발표용 레시피 카드"
                } else {
                    "${recipe.title} 레시피 카드"
                }
            }
            .clickable { onRecipeClick(recipe.id) }
    ) {
        Box(Modifier.fillMaxWidth().height(166.dp)) {
            FigmaResourceImage(imageRes, "${recipe.title} 대표 이미지", Modifier.fillMaxSize(), 0.dp)
            if (badge != null) {
                Text(
                    badge,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(FigmaOrange)
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                )
            }
            if (onToggleScrap != null) {
                FigmaScrapButton(
                    isScrapped = isScrapped,
                    recipeTitle = recipe.title,
                    onClick = { onToggleScrap(recipe.id) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                )
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(recipe.title, color = FigmaInk, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Text("${recipe.totalDurationLabel} · ${recipe.steps.size}단계", color = FigmaMuted, fontSize = 11.sp)
        }
    }
}

private enum class FigmaNavDestination { HOME, RECIPES, MY }

@Composable
private fun FigmaBottomNavigation(
    selected: FigmaNavDestination,
    onHome: () -> Unit,
    onRecipes: () -> Unit,
    onAddRecipe: () -> Unit,
    onMy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(62.dp)
            .background(Color.White)
            .border(BorderStroke(0.5.dp, FigmaDivider))
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        FigmaNavItem(R.drawable.figma_icon_home, "홈", selected == FigmaNavDestination.HOME, onHome)
        FigmaNavItem(R.drawable.figma_icon_recipes, "레시피", selected == FigmaNavDestination.RECIPES, onRecipes)
        FigmaNavItem(R.drawable.figma_icon_add, "추가", false, onAddRecipe)
        FigmaNavItem(R.drawable.figma_icon_profile, "마이", selected == FigmaNavDestination.MY, onMy)
    }
}

@Composable
private fun FigmaNavItem(icon: Int, label: String, selected: Boolean, onClick: (() -> Unit)? = null) {
    Column(
        Modifier.width(64.dp).height(50.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        FigmaResourceIcon(icon, label, 24.dp)
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (selected) FigmaOrange else FigmaMuted, fontSize = 10.sp)
    }
}

@Composable
private fun FigmaScrapButton(
    isScrapped: Boolean,
    recipeTitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val description = if (isScrapped) "스크랩 해제: $recipeTitle" else "스크랩 추가: $recipeTitle"
    Box(
        modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.94f))
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(20.dp)) {
            val heart = Path().apply {
                moveTo(size.width * 0.5f, size.height * 0.9f)
                cubicTo(
                    size.width * 0.44f,
                    size.height * 0.82f,
                    size.width * 0.12f,
                    size.height * 0.6f,
                    size.width * 0.1f,
                    size.height * 0.34f
                )
                cubicTo(
                    size.width * 0.08f,
                    size.height * 0.14f,
                    size.width * 0.23f,
                    size.height * 0.05f,
                    size.width * 0.37f,
                    size.height * 0.08f
                )
                cubicTo(
                    size.width * 0.44f,
                    size.height * 0.09f,
                    size.width * 0.49f,
                    size.height * 0.14f,
                    size.width * 0.5f,
                    size.height * 0.2f
                )
                cubicTo(
                    size.width * 0.51f,
                    size.height * 0.14f,
                    size.width * 0.56f,
                    size.height * 0.09f,
                    size.width * 0.63f,
                    size.height * 0.08f
                )
                cubicTo(
                    size.width * 0.77f,
                    size.height * 0.05f,
                    size.width * 0.92f,
                    size.height * 0.14f,
                    size.width * 0.9f,
                    size.height * 0.34f
                )
                cubicTo(
                    size.width * 0.88f,
                    size.height * 0.6f,
                    size.width * 0.56f,
                    size.height * 0.82f,
                    size.width * 0.5f,
                    size.height * 0.9f
                )
                close()
            }
            if (isScrapped) {
                drawPath(heart, color = FigmaInk)
            } else {
                drawPath(
                    path = heart,
                    color = FigmaInk,
                    style = Stroke(
                        width = 2.6.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
    }
}

private enum class FigmaMySection { OVERVIEW, SCRAPS, CREATED, VIEWED, SETTINGS }

@Composable
internal fun FigmaMyScreen(
    uiState: CookingSessionUiState,
    onHome: () -> Unit,
    onRecipes: () -> Unit,
    onRecipeClick: (String) -> Unit,
    onAddRecipe: () -> Unit,
    onToggleScrap: (String) -> Unit,
    onVoiceGuidanceChange: (Boolean) -> Unit
) {
    var section by remember { mutableStateOf(FigmaMySection.OVERVIEW) }
    val scrappedRecipes = uiState.recipes.filter { it.id in uiState.scrappedRecipeIds }
    val createdRecipes = uiState.recipes.filter { it.id.startsWith("recipe-") }
    val viewedRecipes = uiState.viewedRecipeIds.mapNotNull { id -> uiState.recipes.firstOrNull { it.id == id } }
    val cookingRecordCount = uiState.session?.logs
        ?.filter { it.verdict == JudgmentVerdict.DONE }
        ?.map { it.stepOrder }
        ?.distinct()
        ?.size ?: 0
    val connectionLabel = figmaGlassesConnectionLabel(uiState.cameraState)

    BackHandler {
        if (section == FigmaMySection.OVERVIEW) onHome() else section = FigmaMySection.OVERVIEW
    }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 76.dp)
        ) {
            when (section) {
                FigmaMySection.OVERVIEW -> {
                    Column {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 24.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("마이", color = FigmaInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                FigmaIconButton(R.drawable.figma_icon_settings, "설정", { section = FigmaMySection.SETTINGS })
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("내 레시피와 요리 기록을 한곳에서 관리해요", color = FigmaMuted, fontSize = 11.sp)
                        }

                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFFFF8F0))
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(118.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(Color(0xFFFFF0DB))
                                    .padding(horizontal = 15.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.figma_my_profile_mascot),
                                    contentDescription = "따라쿡 프로필 마스코트",
                                    modifier = Modifier.size(78.dp),
                                    contentScale = ContentScale.Fit
                                )
                                Spacer(Modifier.width(13.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("건호님의 주방", color = FigmaInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(4.dp))
                                    Text("함께 완성한 요리 ${cookingRecordCount}개", color = FigmaMuted, fontSize = 10.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "● $connectionLabel",
                                        color = if (connectionLabel == "안경 연결됨") FigmaGreen else FigmaMuted,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (connectionLabel == "안경 연결됨") FigmaGreenSurface else Color.White)
                                            .padding(horizontal = 9.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FigmaMyStatCard(scrappedRecipes.size, "찜한 레시피", Color(0xFFFFEEE0), Color(0xFFCB6921), Modifier.weight(1f))
                            FigmaMyStatCard(createdRecipes.size, "내 레시피", Color(0xFFEAF2FF), Color(0xFF3869A9), Modifier.weight(1f))
                            FigmaMyStatCard(cookingRecordCount, "요리 기록", Color(0xFFEAF7E6), FigmaGreen, Modifier.weight(1f))
                        }

                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Text("요리 보관함", color = FigmaInk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(9.dp))
                            FigmaMyLibraryRow("♡", "찜한 레시피", "${scrappedRecipes.size}개", { section = FigmaMySection.SCRAPS })
                            Spacer(Modifier.height(7.dp))
                            FigmaMyLibraryRow("＋", "내가 만든 레시피", "${createdRecipes.size}개", { section = FigmaMySection.CREATED })
                            Spacer(Modifier.height(7.dp))
                            FigmaMyLibraryRow("◷", "최근 본 레시피", "${viewedRecipes.size}개", { section = FigmaMySection.VIEWED })

                            Spacer(Modifier.height(22.dp))
                            Text("서비스 설정", color = FigmaInk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(9.dp))
                            FigmaMySettingsRow("안경 연결 및 권한", connectionLabel, {})
                            HorizontalDivider(color = FigmaDivider, thickness = 0.5.dp)
                            FigmaMySettingsRow("음성 안내 설정", null, { section = FigmaMySection.SETTINGS })
                            HorizontalDivider(color = FigmaDivider, thickness = 0.5.dp)
                            FigmaMySettingsRow("사진 · 데이터 관리", null, {})
                            Spacer(Modifier.height(20.dp))
                        }
                    }
                }

                FigmaMySection.SCRAPS -> {
                    FigmaTopBar("찜한 레시피", { section = FigmaMySection.OVERVIEW })
                    FigmaMyRecipeList(
                        recipes = scrappedRecipes,
                        emptyTitle = "찜한 레시피가 없어요",
                        emptyDetail = "레시피 화면에서 하트 버튼을 눌러 저장해 보세요.",
                        onRecipeClick = onRecipeClick,
                        actionLabel = "찜 해제",
                        onAction = onToggleScrap
                    )
                }

                FigmaMySection.CREATED -> {
                    FigmaTopBar("내가 만든 레시피", { section = FigmaMySection.OVERVIEW }, actionLabel = "추가", onAction = onAddRecipe)
                    FigmaMyRecipeList(
                        recipes = createdRecipes,
                        emptyTitle = "직접 만든 레시피가 없어요",
                        emptyDetail = "추가 버튼을 눌러 나만의 레시피를 만들어 보세요.",
                        onRecipeClick = onRecipeClick
                    )
                }

                FigmaMySection.VIEWED -> {
                    FigmaTopBar("최근 본 레시피", { section = FigmaMySection.OVERVIEW })
                    FigmaMyRecipeList(
                        recipes = viewedRecipes,
                        emptyTitle = "아직 본 레시피가 없어요",
                        emptyDetail = "레시피 화면에서 레시피를 열면 최근 본 순서로 표시됩니다.",
                        onRecipeClick = onRecipeClick
                    )
                }

                FigmaMySection.SETTINGS -> {
                    FigmaTopBar("설정", { section = FigmaMySection.OVERVIEW })
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text("조리 안내", color = FigmaInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(10.dp))
                        FigmaMyMenuRow(
                            icon = R.drawable.figma_icon_mic,
                            title = "음성 안내",
                            detail = if (uiState.voiceGuidanceEnabled) "단계 안내를 음성으로 들려줘요" else "음성 안내가 꺼져 있어요",
                            trailingLabel = if (uiState.voiceGuidanceEnabled) "켜짐" else "꺼짐",
                            onClick = { onVoiceGuidanceChange(!uiState.voiceGuidanceEnabled) }
                        )
                        Spacer(Modifier.height(28.dp))
                        Text("앱 정보", color = FigmaInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(10.dp))
                        FigmaMyMenuRow(
                            icon = R.drawable.figma_icon_profile,
                            title = "따라쿡",
                            detail = "버전 ${BuildConfig.VERSION_NAME}",
                            trailingLabel = null,
                            onClick = {}
                        )
                    }
                }
            }
        }

        FigmaBottomNavigation(
            selected = FigmaNavDestination.MY,
            onHome = onHome,
            onRecipes = onRecipes,
            onAddRecipe = onAddRecipe,
            onMy = { section = FigmaMySection.OVERVIEW },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun FigmaMyStatCard(
    count: Int,
    label: String,
    background: Color,
    accent: Color,
    modifier: Modifier
) {
    Column(
        modifier
            .height(74.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(count.toString(), color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(label, color = FigmaMuted, fontSize = 9.sp)
    }
}

@Composable
private fun FigmaMyLibraryRow(symbol: String, title: String, count: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(FigmaSurface)
            .semantics { contentDescription = "$title 메뉴" }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(FigmaWarmIcon), contentAlignment = Alignment.Center) {
            Text(symbol, color = FigmaOrange, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(11.dp))
        Text(title, color = FigmaInk, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(count, color = FigmaMuted, fontSize = 10.sp)
        Spacer(Modifier.width(7.dp))
        Text("›", color = FigmaMuted, fontSize = 17.sp)
    }
}

@Composable
private fun FigmaMySettingsRow(title: String, status: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(53.dp).clickable(onClick = onClick).padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = FigmaInk, fontSize = 12.sp, modifier = Modifier.weight(1f))
        status?.let {
            Text(it, color = if (it == "안경 연결됨") FigmaGreen else FigmaMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(7.dp))
        }
        Text("›", color = FigmaMuted, fontSize = 17.sp)
    }
}

@Composable
private fun FigmaMyMenuRow(
    icon: Int,
    title: String,
    detail: String,
    onClick: () -> Unit,
    trailingLabel: String? = ">"
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 82.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(FigmaSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(FigmaWarmIcon), contentAlignment = Alignment.Center) {
            FigmaResourceIcon(icon, null, 22.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = FigmaInk, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(detail, color = FigmaMuted, fontSize = 11.sp)
        }
        trailingLabel?.let {
            Text(it, color = if (it == "켜짐") FigmaGreen else FigmaMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun FigmaMyRecipeList(
    recipes: List<Recipe>,
    emptyTitle: String,
    emptyDetail: String,
    onRecipeClick: (String) -> Unit,
    actionLabel: String? = null,
    onAction: ((String) -> Unit)? = null
) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        if (recipes.isEmpty()) {
            FigmaMessageCard(emptyTitle, emptyDetail)
        } else {
            recipes.forEach { recipe ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(116.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(FigmaSurface)
                        .clickable { onRecipeClick(recipe.id) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FigmaResourceImage(
                        figmaRecipeImageResource(recipe, R.drawable.figma_recipe_cover),
                        "${recipe.title} 대표 이미지",
                        Modifier.width(116.dp).height(116.dp),
                        0.dp
                    )
                    Column(Modifier.weight(1f).padding(14.dp)) {
                        Text(recipe.title, color = FigmaInk, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                        Spacer(Modifier.height(5.dp))
                        Text("${recipe.totalDurationLabel} · ${recipe.steps.size}단계", color = FigmaMuted, fontSize = 11.sp)
                        if (actionLabel != null && onAction != null) {
                            TextButton(onClick = { onAction(recipe.id) }) {
                                Text(actionLabel, color = FigmaOrange, fontSize = 10.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
internal fun FigmaRecipeDetailScreen(
    recipe: Recipe,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onEdit: () -> Unit
) {
    val errors = recipe.validationErrors()
    var ingredientsExpanded by remember(recipe.id) { mutableStateOf(false) }
    var stepsExpanded by remember(recipe.id) { mutableStateOf(false) }
    Scaffold(
        containerColor = Color.White,
        bottomBar = {
            FigmaBottomBar {
                FigmaPrimaryButton("요리 시작", onStart, enabled = errors.isEmpty())
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            FigmaTopBar("레시피 상세", onBack, actionLabel = "편집", onAction = onEdit)
            FigmaResourceImage(
                figmaRecipeImageResource(recipe, R.drawable.figma_recipe_hero),
                recipe.title,
                Modifier.fillMaxWidth().height(228.dp).padding(horizontal = 20.dp),
                22.dp
            )
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text(recipe.title, color = FigmaInk, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(recipe.heroNote, color = FigmaMuted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FigmaChip(recipe.totalDurationLabel)
                    FigmaChip("${recipe.ingredients.size}개 재료")
                    FigmaChip("${recipe.steps.size}단계")
                }
                Spacer(Modifier.height(12.dp))
                FigmaListInfoBlock(
                    title = "재료",
                    rows = recipe.ingredients.map { "${it.name}  ${it.amount}" },
                    expanded = ingredientsExpanded,
                    onToggle = { ingredientsExpanded = !ingredientsExpanded }
                )
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(thickness = 0.5.dp, color = FigmaDivider)
                Spacer(Modifier.height(10.dp))
                FigmaListInfoBlock(
                    title = "요리 순서",
                    rows = recipe.steps.map { it.instruction },
                    leadingLabels = recipe.steps.map { "${it.order}단계" },
                    expanded = stepsExpanded,
                    onToggle = { stepsExpanded = !stepsExpanded }
                )
                if (errors.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(errors.joinToString("\n"), color = Color(0xFFC78500), fontSize = 11.sp)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
internal fun FigmaDeviceScreen(
    uiState: CookingSessionUiState,
    onBack: () -> Unit,
    onAdvance: () -> Unit,
    onDisconnect: () -> Unit,
    onError: () -> Unit,
    onStartWithoutGlasses: () -> Unit,
    onStartCooking: () -> Unit
) {
    val ready = uiState.cameraState == WearableCameraState.Ready
    Scaffold(
        containerColor = Color.White,
        bottomBar = {
            FigmaBottomBar {
                Text("연결 실패 시에도 음성 안내와 수동 진행은 사용할 수 있어요", color = FigmaMuted, fontSize = 10.sp)
                Spacer(Modifier.height(8.dp))
                FigmaPrimaryButton(if (ready) "1단계 시작" else "연결 다시 확인", if (ready) onStartCooking else onAdvance)
                Spacer(Modifier.height(8.dp))
                FigmaSecondaryButton("안경 없이 시작", onStartWithoutGlasses)
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            FigmaTopBar("", onBack = onBack)
            Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FigmaResourceIcon(R.drawable.figma_glasses, "AI 글래스", 128.dp, height = 64.dp)
                Spacer(Modifier.height(12.dp))
                Text("AI 글래스를 준비할게요", color = FigmaInk, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("권한과 카메라 상태를 확인한 뒤\n1단계 촬영을 시작합니다", color = FigmaMuted, fontSize = 12.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 84.dp)) {
                Text("준비 상태", color = FigmaInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                FigmaReadyRow("Meta AI 앱 등록", uiState.cameraState != WearableCameraState.NotStarted)
                Spacer(Modifier.height(10.dp))
                FigmaReadyRow("카메라 권한", uiState.cameraState !is WearableCameraState.PermissionRequired)
                Spacer(Modifier.height(10.dp))
                FigmaReadyRow("안경 찾는 중", ready)
                Spacer(Modifier.height(10.dp))
                FigmaReadyRow("카메라 세션 준비", ready)
                Spacer(Modifier.height(8.dp))
                Text(uiState.deviceHint, color = FigmaMuted, fontSize = 10.sp)
                if (uiState.useFakeCamera) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDisconnect) { Text("연결 끊김 시험", color = FigmaMuted, fontSize = 10.sp) }
                        TextButton(onClick = onError) { Text("오류 시험", color = FigmaMuted, fontSize = 10.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FigmaReadyRow(label: String, complete: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(16.dp)).background(if (complete) Color(0xFFF3FAF1) else FigmaWarm).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FigmaResourceIcon(if (complete) R.drawable.figma_icon_ready else R.drawable.figma_icon_pending, null, 24.dp)
        Text("$label${if (complete) " · 완료" else ""}", color = FigmaInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun FigmaCookingScreen(
    uiState: CookingSessionUiState,
    wakeWordStatus: WakeWordStatus,
    onStartInspection: () -> Unit,
    onManualNext: () -> Unit,
    onNotYet: () -> Unit,
    onRepeat: () -> Unit,
    onPrevious: () -> Unit,
    onDisableAuto: () -> Unit,
    onUseBusyCapture: () -> Unit,
    onUseSuccessCapture: () -> Unit,
    onUseDisconnectCapture: () -> Unit,
    onUseFailureCapture: () -> Unit,
    onMockVerdict: (JudgmentVerdict) -> Unit,
    onSetMockEnabled: (Boolean) -> Unit,
    onServerBaseUrlChange: (String) -> Unit,
    onApplyServerBaseUrl: () -> Unit,
    onFinishParallelTimer: () -> Unit,
    onVoice: () -> Unit
) {
    val recipe = uiState.selectedRecipe ?: return
    val step = uiState.currentStep ?: return
    val session = uiState.session ?: return
    val simulationImage = if (uiState.isPresentationSimulation && uiState.presentationCaptureVisible) {
        PresentationSimulation.captureImageResource(step.order)
    } else {
        null
    }
    var showDiagnostics by remember { mutableStateOf(false) }
    val progress = step.order.toFloat() / recipe.steps.size.coerceAtLeast(1)
    Scaffold(
        containerColor = Color.White,
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(Color.White).border(BorderStroke(0.5.dp, FigmaDivider)).padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text("말로 “확인해줘”, “다음”을 해도 같은 동작을 해요", color = FigmaMuted, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FigmaSecondaryButton("확인해줘", onStartInspection, Modifier.weight(1f), outlined = true)
                    FigmaPrimaryButton("다음", onManualNext, Modifier.weight(1f))
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    FigmaIconButton(
                        R.drawable.figma_icon_back,
                        if (step.order == 1) "이전 페이지" else "이전 단계",
                        onPrevious
                    )
                    Text(recipe.title, color = FigmaInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (uiState.isPresentationSimulation) "안경 연결됨" else figmaGlassesConnectionLabel(uiState.cameraState),
                        color = FigmaGreen,
                        fontSize = 10.sp,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(FigmaGreenSurface).padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
                Spacer(Modifier.height(9.dp))
                Text(
                    "${step.order}단계 · ${fullStepTitle(step.instruction)}",
                    color = FigmaInk,
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = FigmaOrange, trackColor = FigmaDivider)
            }

            Column(Modifier.padding(horizontal = 20.dp)) {
                val baselineUri = session.baselineUriByStep[step.order]
                val currentPhotoUri = session.lastCaptureUriByStep[step.order]
                    ?: (uiState.currentCaptureOutcome as? CaptureOutcome.Success)?.artifact?.imageUri
                val exampleImage = figmaSummaryStepImageResource(recipe, step.order)
                val showCompletionComparison = step.needsStartImage && (exampleImage != null || baselineUri != null)
                val showCompletionCriteria = shouldShowFigmaCompletionCriteria(
                    recipeId = recipe.id,
                    stepOrder = step.order,
                    hasComparisonMedia = showCompletionComparison
                )
                if (showCompletionCriteria) {
                    Text("눈으로 비교해보세요", color = FigmaInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                }
                when {
                    simulationImage != null && exampleImage != null -> FigmaReferenceAndResourceCurrentStage(
                        exampleImageResource = exampleImage,
                        currentImageResource = simulationImage,
                        currentDescription = "${step.order}단계 최근 촬영",
                        modifier = Modifier.fillMaxWidth()
                    )
                    simulationImage != null -> FigmaResourceImage(
                        simulationImage,
                        "${step.order}단계 최근 촬영",
                        Modifier
                            .fillMaxWidth(0.6f)
                            .align(Alignment.CenterHorizontally)
                            .aspectRatio(SERVER_IMAGE_ASPECT_RATIO),
                        22.dp
                    )
                    exampleImage != null && currentPhotoUri != null -> FigmaReferenceAndCurrentStage(
                        exampleImageResource = exampleImage,
                        currentUri = currentPhotoUri,
                        modifier = Modifier.fillMaxWidth()
                    )
                    exampleImage != null -> FigmaResourceImage(
                        exampleImage,
                        "${step.order}단계 예시 사진",
                        Modifier.fillMaxWidth().height(290.dp),
                        22.dp
                    )
                    baselineUri != null -> FigmaCompareStage(
                        baselineUri = baselineUri,
                        currentUri = currentPhotoUri,
                        modifier = Modifier.fillMaxWidth().height(290.dp)
                    )
                    else -> FigmaSessionImage(
                        uiState,
                        R.drawable.figma_latest_capture,
                        "최근 안경 촬영",
                        Modifier.fillMaxWidth().height(282.dp),
                        22.dp
                    )
                }
                if (showCompletionCriteria) {
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FigmaYellowSurface).padding(16.dp)) {
                        Text("완료 기준", color = Color(0xFF8C610A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(step.checkCondition.orEmpty(), color = Color(0xFF1F1C14), fontSize = 18.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(8.dp).background(if (uiState.judgingInFlight) FigmaOrange else FigmaGreen, CircleShape))
                    Text(
                        when {
                            simulationImage != null && exampleImage != null -> "단계 예시와 최근 촬영 · ${formatUiDuration(uiState.stepElapsedSeconds)}  |  평소에는 카메라 OFF"
                            simulationImage != null -> "최근 촬영 · ${formatUiDuration(uiState.stepElapsedSeconds)}  |  평소에는 카메라 OFF"
                            exampleImage != null && currentPhotoUri != null -> "단계 예시와 최근 촬영 · ${formatUiDuration(uiState.stepElapsedSeconds)}  |  평소에는 카메라 OFF"
                            exampleImage != null -> "단계 예시 · 촬영 전  |  평소에는 카메라 OFF"
                            currentPhotoUri != null -> "최근 촬영 · ${formatUiDuration(uiState.stepElapsedSeconds)}  |  평소에는 카메라 OFF"
                            else -> "촬영 전  |  평소에는 카메라 OFF"
                        },
                        color = FigmaMuted,
                        fontSize = 11.sp
                    )
                }
                if (uiState.judgingInFlight || session.currentVerdict != null) {
                    Spacer(Modifier.height(12.dp))
                    FigmaJudgmentResultCard(uiState, session)
                }
            }

            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(inspectionMessage(uiState, step), color = FigmaMuted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(16.dp)).background(FigmaWarm).clickable(onClick = onVoice).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    FigmaResourceIcon(R.drawable.figma_icon_mic, null, 20.dp)
                    Text(if (wakeWordStatus.listening) "듣고 있어요 · “따라쿡”이라고 불러보세요" else wakeWordStatus.message, color = Color(0xFF4D3F31), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }

                uiState.parallelTimerRemainingSeconds?.let { remaining ->
                    Spacer(Modifier.height(10.dp))
                    FigmaMessageCard(session.parallelTimerLabel ?: "타이머", if (remaining > 0) "${formatUiDuration(remaining)} 남음" else session.parallelTimerMessage ?: "타이머가 끝났습니다.")
                    if (remaining > 0 && uiState.useFakeCamera) TextButton(onClick = onFinishParallelTimer) { Text("타이머 완료 시험", fontSize = 10.sp) }
                }
                if (uiState.maxExpectedExceeded) {
                    Spacer(Modifier.height(8.dp))
                    FigmaMessageCard("예상 시간 초과", "계속 조리하거나 수동 모드로 전환할 수 있어요.")
                }

                TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                    Text(if (showDiagnostics) "판정 설정 접기" else "판정 설정", color = FigmaMuted, fontSize = 11.sp)
                }
                if (showDiagnostics) {
                    FigmaDiagnosticsPanel(
                        uiState, onNotYet, onRepeat, onDisableAuto, onUseBusyCapture, onUseSuccessCapture,
                        onUseDisconnectCapture, onUseFailureCapture, onMockVerdict, onSetMockEnabled,
                        onServerBaseUrlChange, onApplyServerBaseUrl
                    )
                }
            }
        }
    }
}

internal fun shouldShowFigmaCompletionCriteria(
    recipeId: String,
    stepOrder: Int,
    hasComparisonMedia: Boolean
): Boolean = hasComparisonMedia && !(
    recipeId == PresentationSimulation.RECIPE_ID && stepOrder == 4
)

@Composable
private fun FigmaJudgmentResultCard(
    uiState: CookingSessionUiState,
    session: CookingSession,
    showVerdictCode: Boolean = false
) {
    val verdict = session.currentVerdict
    val presentation = when {
        uiState.judgingInFlight -> FigmaJudgmentPresentation(
            title = "사진을 판정하고 있어요",
            detail = "잠시만 기다려주세요",
            background = FigmaWarm,
            accent = FigmaOrange,
            icon = R.drawable.figma_icon_pending
        )
        verdict == JudgmentVerdict.DONE -> FigmaJudgmentPresentation(
            title = "완료로 판정했어요",
            detail = session.lastReasonCode?.label ?: "완료 조건을 확인했습니다",
            background = FigmaGreenSurface,
            accent = FigmaGreen,
            icon = R.drawable.figma_icon_success
        )
        verdict == JudgmentVerdict.CANNOT_TELL -> FigmaJudgmentPresentation(
            title = "사진에서 판단하기 어려워요",
            detail = session.lastReasonCode?.label ?: "대상이 잘 보이도록 다시 확인해주세요",
            background = FigmaYellowSurface,
            accent = Color(0xFFC78500),
            icon = R.drawable.figma_icon_warning
        )
        else -> FigmaJudgmentPresentation(
            title = "아직 완료되지 않았어요",
            detail = session.lastReasonCode?.label ?: "현재 단계를 계속해주세요",
            background = FigmaWarm,
            accent = FigmaOrange,
            icon = R.drawable.figma_icon_pending
        )
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(presentation.background).padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FigmaResourceIcon(presentation.icon, null, 28.dp)
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(presentation.title, color = presentation.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (showVerdictCode) {
                    val code = when {
                        uiState.judgingInFlight -> "판정 중"
                        verdict == JudgmentVerdict.DONE -> "DONE"
                        verdict == JudgmentVerdict.NOT_DONE -> "NOT DONE"
                        verdict == JudgmentVerdict.CANNOT_TELL -> "CANNOT TELL"
                        else -> null
                    }
                    code?.let {
                        Text(
                            it,
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(presentation.accent).padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            val timing = session.lastRoundTripMs?.let { " · 응답 ${formatJudgmentLatency(it)}" }.orEmpty()
            Text("${presentation.detail}$timing", color = FigmaMuted, fontSize = 11.sp)
        }
    }
}

private data class FigmaJudgmentPresentation(
    val title: String,
    val detail: String,
    val background: Color,
    val accent: Color,
    val icon: Int
)

@Composable
private fun FigmaManualJudgmentStateCard(
    code: String,
    title: String,
    detail: String,
    background: Color,
    accent: Color,
    icon: Int
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(background).padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FigmaResourceIcon(icon, null, 28.dp)
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    code,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(accent).padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(detail, color = FigmaMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun FigmaDiagnosticsPanel(
    uiState: CookingSessionUiState,
    onNotYet: () -> Unit,
    onRepeat: () -> Unit,
    onDisableAuto: () -> Unit,
    onUseBusyCapture: () -> Unit,
    onUseSuccessCapture: () -> Unit,
    onUseDisconnectCapture: () -> Unit,
    onUseFailureCapture: () -> Unit,
    onMockVerdict: (JudgmentVerdict) -> Unit,
    onSetMockEnabled: (Boolean) -> Unit,
    onServerBaseUrlChange: (String) -> Unit,
    onApplyServerBaseUrl: () -> Unit
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(FigmaSurface).padding(14.dp)) {
        Text("수동·검증 도구", color = FigmaInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FigmaTinyButton("아직", onNotYet); FigmaTinyButton("다시", onRepeat); FigmaTinyButton("자동 확인 끄기", onDisableAuto)
        }
        if (uiState.useFakeCamera) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FigmaTinyButton("Capture 성공", onUseSuccessCapture); FigmaTinyButton("BUSY", onUseBusyCapture)
                FigmaTinyButton("연결 끊김", onUseDisconnectCapture); FigmaTinyButton("실패", onUseFailureCapture)
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FigmaTinyButton(if (uiState.useMockJudgment) "✓ Fake 판정" else "Fake 판정") { onSetMockEnabled(true) }
            FigmaTinyButton(if (!uiState.useMockJudgment) "✓ 실제 서버" else "실제 서버") { onSetMockEnabled(false) }
            if (uiState.useMockJudgment) JudgmentVerdict.entries.forEach { verdict -> FigmaTinyButton(verdict.name) { onMockVerdict(verdict) } }
        }
        if (!uiState.useMockJudgment) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = uiState.serverBaseUrl, onValueChange = onServerBaseUrlChange, modifier = Modifier.fillMaxWidth(), label = { Text("판정 서버 주소") }, singleLine = true)
            TextButton(onClick = onApplyServerBaseUrl) { Text("서버 주소 적용 및 상태 확인", color = FigmaOrange) }
            Text(uiState.serverStatusMessage ?: "서버 상태를 확인합니다.", color = FigmaMuted, fontSize = 10.sp)
        }
        uiState.currentCaptureOutcome?.let { outcome ->
            Spacer(Modifier.height(8.dp))
            Text(
                when (outcome) {
                    is CaptureOutcome.Success -> "최근 촬영 성공 · 판정 입력 이미지 저장 완료"
                    is CaptureOutcome.Failure -> "${outcome.kind} · ${outcome.userMessage}"
                },
                color = FigmaMuted,
                fontSize = 10.sp
            )
        }
        uiState.judgeError?.let { Text("판정 오류 · $it", color = Color(0xFFC78500), fontSize = 10.sp) }
        uiState.speechError?.let { Text("음성 오류 · $it", color = Color(0xFFC78500), fontSize = 10.sp) }
        uiState.lastVoiceTranscript?.let { Text("최근 음성 인식 · $it", color = FigmaMuted, fontSize = 10.sp) }
        uiState.session?.let { session ->
            if (session.lastRoundTripMs != null) Text("roundTrip ${session.lastRoundTripMs}ms / vlm ${session.lastVlmLatencyMs ?: "-"}ms", color = FigmaMuted, fontSize = 10.sp)
            session.logs.takeLast(3).forEach { log -> Text("${log.stepOrder}단계 · ${log.message}", color = FigmaMuted, fontSize = 10.sp) }
        }
    }
}

@Composable
internal fun FigmaStepDoneScreen(
    uiState: CookingSessionUiState,
    wakeWordStatus: WakeWordStatus,
    onContinue: () -> Unit,
    onUndo: () -> Unit,
    onFinishParallelTimer: () -> Unit,
    onVoice: () -> Unit
) {
    val recipe = uiState.selectedRecipe ?: return
    val step = uiState.currentStep ?: return
    val session = uiState.session ?: return
    Scaffold(containerColor = Color.White, bottomBar = {
        FigmaRowBottomBar {
            FigmaSecondaryButton("이전", onUndo, Modifier.width(110.dp))
            FigmaPrimaryButton("다음 단계", onContinue, Modifier.weight(1f))
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Text("${recipe.title} · ${step.order} / ${recipe.steps.size}단계", color = FigmaMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                FigmaResourceIcon(R.drawable.figma_icon_success, "완료", 56.dp)
                Spacer(Modifier.height(8.dp))
                Text("${shortStepTitle(step.instruction)} 완료!", color = FigmaInk, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("현재 사진을 기준으로 다음 단계를 준비했어요", color = FigmaMuted, fontSize = 11.sp)
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
                Text("완료 순간", color = FigmaInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(7.dp))
                FigmaSessionImage(uiState, R.drawable.figma_step_completion, "단계 완료 사진", Modifier.fillMaxWidth().height(244.dp), 20.dp)
                Spacer(Modifier.height(7.dp))
                Text("자동 판정 DONE · ${session.consecutiveDoneCount.coerceAtLeast(1)}회 연속 확인", color = FigmaGreen, fontSize = 10.sp)
                val next = recipe.steps.getOrNull(step.order)
                if (next != null) {
                    Spacer(Modifier.height(24.dp))
                    Column(Modifier.fillMaxWidth().heightIn(min = 140.dp).clip(RoundedCornerShape(20.dp)).background(FigmaWarm).padding(16.dp)) {
                        Text("다음 · ${next.order}단계", color = FigmaOrange, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(7.dp))
                        Text(shortStepTitle(next.instruction), color = FigmaInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(7.dp))
                        Text(next.instruction, color = FigmaMuted, fontSize = 12.sp, lineHeight = 20.sp)
                    }
                }
                if (session.advanceBlockedByTimer) {
                    Spacer(Modifier.height(10.dp))
                    FigmaMessageCard(session.parallelTimerLabel ?: "타이머", "${formatUiDuration(uiState.parallelTimerRemainingSeconds ?: 0)} 뒤 자동으로 넘어가요.")
                    if (uiState.useFakeCamera) TextButton(onClick = onFinishParallelTimer) { Text("타이머 완료 시험", fontSize = 10.sp) }
                }
                TextButton(onClick = onVoice) { Text(wakeWordStatus.message, color = FigmaMuted, fontSize = 10.sp) }
            }
        }
    }
}

@Composable
internal fun FigmaNeedsViewScreen(uiState: CookingSessionUiState, wakeWordStatus: WakeWordStatus, onRetry: () -> Unit, onNext: () -> Unit) {
    val recipe = uiState.selectedRecipe ?: return
    val step = uiState.currentStep ?: return
    val session = uiState.session ?: return
    Scaffold(containerColor = Color.White, bottomBar = {
        FigmaRowBottomBar {
            FigmaSecondaryButton("수동으로 다음", onNext, Modifier.weight(1f))
            FigmaPrimaryButton("즉시 재검사", onRetry, Modifier.weight(1f))
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Text("${recipe.title} · ${step.order}단계", color = FigmaMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                FigmaResourceIcon(R.drawable.figma_icon_warning, "확인 필요", 56.dp)
                Spacer(Modifier.height(8.dp))
                Text("조금 더 잘 보여주세요", color = FigmaInk, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("대상이 작거나 화면 아래쪽에 있어 확인이 어려워요", color = FigmaMuted, fontSize = 11.sp)
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
                Text("방금 본 화면", color = FigmaInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(7.dp))
                FigmaSessionImage(uiState, R.drawable.figma_unclear_capture, "확인이 어려운 화면", Modifier.fillMaxWidth().height(244.dp), 20.dp)
                Spacer(Modifier.height(7.dp))
                Text("CANNOT_TELL · ${session.lastReasonCode?.label ?: "대상 위치 확인 필요"}", color = Color(0xFFC78500), fontSize = 10.sp)
                Spacer(Modifier.height(22.dp))
                Text("팬을 시야 아래쪽에 맞춰주세요", color = FigmaInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("고개를 조금 내리고 1초간 그대로 바라보면 됩니다", color = FigmaMuted, fontSize = 11.sp)
                Spacer(Modifier.height(9.dp))
                FigmaVoiceStrip(if (wakeWordStatus.listening) "준비되면 “확인해줘”라고 말하세요" else wakeWordStatus.message)
            }
        }
    }
}

@Composable
internal fun FigmaManualModeScreen(
    uiState: CookingSessionUiState,
    wakeWordStatus: WakeWordStatus,
    onResumeAuto: () -> Unit,
    onPickGalleryBaseline: () -> Unit,
    onPickGalleryCurrent: () -> Unit,
    onRetryJudgment: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    onPrevious: () -> Unit,
    onVoice: () -> Unit
) {
    val recipe = uiState.selectedRecipe ?: return
    val step = uiState.currentStep ?: return
    val session = uiState.session ?: return
    val exampleImage = figmaSummaryStepImageResource(recipe, step.order)
    val currentPhotoUri = session.lastCaptureUriByStep[step.order]
    val supportsGalleryJudgment = step.isAutoCheck &&
        step.checkType != CheckType.TIMER_ONLY &&
        !step.checkCondition.isNullOrBlank()
    val hasRequiredBaseline = !step.needsStartImage || session.baselineUriByStep[step.order] != null
    Scaffold(containerColor = Color.White, bottomBar = {
        FigmaBottomBar {
            if (supportsGalleryJudgment) {
                FigmaSecondaryButton(
                    if (uiState.judgingInFlight) "갤러리 사진 판정 중..." else "갤러리 사진으로 판정",
                    onPickGalleryCurrent,
                    enabled = !uiState.judgingInFlight && hasRequiredBaseline,
                    outlined = true
                )
                Spacer(Modifier.height(8.dp))
            }
            FigmaPrimaryButton("수동으로 다음 단계", onNext)
            Spacer(Modifier.height(8.dp))
            FigmaSecondaryButton("자동 확인 다시 켜기", onResumeAuto)
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            FigmaTopBar("${recipe.title} · ${step.order}단계", onPrevious)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                FigmaResourceIcon(R.drawable.figma_icon_manual, "수동 모드", 56.dp)
                Spacer(Modifier.height(8.dp))
                Text("수동 모드로 계속할게요", color = FigmaInk, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("자동 확인 없이도 음성 안내와 단계 이동은 계속됩니다", color = FigmaMuted, fontSize = 11.sp)
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
                Text(
                    when {
                        currentPhotoUri != null -> "단계 예시와 판정할 현재 사진"
                        exampleImage != null -> "${step.order}단계 예시 사진"
                        else -> "마지막으로 확인한 화면"
                    },
                    color = FigmaInk,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(7.dp))
                if (exampleImage != null && currentPhotoUri != null) {
                    FigmaReferenceAndCurrentStage(
                        exampleImageResource = exampleImage,
                        currentUri = currentPhotoUri,
                        showOriginalCurrent = isExternalGalleryImageUri(currentPhotoUri),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (exampleImage != null) {
                    FigmaResourceImage(exampleImage, "${step.order}단계 예시 사진", Modifier.fillMaxWidth().height(204.dp), 20.dp)
                } else if (currentPhotoUri != null) {
                    if (isExternalGalleryImageUri(currentPhotoUri)) {
                        FigmaOriginalGalleryImageCard(currentPhotoUri)
                    } else {
                        FigmaServerCurrentImageCard(currentPhotoUri)
                    }
                } else {
                    FigmaSessionImage(uiState, R.drawable.figma_manual_capture, "마지막 확인 사진", Modifier.fillMaxWidth().height(204.dp), 20.dp)
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    if (currentPhotoUri != null && isExternalGalleryImageUri(currentPhotoUri)) "갤러리 사진은 원본 규격으로 표시됩니다 · 서버에는 판정 규격으로 전송됩니다" else if (currentPhotoUri != null) "촬영 사진은 서버 전송 규격으로 표시됩니다" else if (exampleImage != null) "자동모드와 동일한 단계 예시입니다" else "자동 카메라 확인은 꺼져 있어요",
                    color = FigmaMuted,
                    fontSize = 10.sp
                )
                if (supportsGalleryJudgment && step.needsStartImage) {
                    Spacer(Modifier.height(12.dp))
                    FigmaSecondaryButton(
                        if (hasRequiredBaseline) "비교 시작 사진 다시 불러오기" else "비교 시작 사진 불러오기",
                        onPickGalleryBaseline,
                        enabled = !uiState.judgingInFlight,
                        outlined = true
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (hasRequiredBaseline) "비교 시작 사진이 준비됐습니다" else "이 단계는 시작 사진과 현재 사진을 함께 서버로 보냅니다",
                        color = if (hasRequiredBaseline) FigmaGreen else FigmaMuted,
                        fontSize = 10.sp
                    )
                } else if (!supportsGalleryJudgment) {
                    Spacer(Modifier.height(10.dp))
                    Text("이 단계는 사진 판정 없이 수동으로 진행합니다", color = FigmaMuted, fontSize = 10.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text("판정 결과", color = FigmaInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                when {
                    uiState.judgingInFlight || session.currentVerdict != null ->
                        FigmaJudgmentResultCard(uiState, session, showVerdictCode = true)
                    uiState.judgeError != null -> Column {
                        FigmaManualJudgmentStateCard(
                            code = "판정 실패",
                            title = "결과를 받지 못했어요",
                            detail = uiState.judgeError,
                            background = FigmaYellowSurface,
                            accent = Color(0xFFC78500),
                            icon = R.drawable.figma_icon_warning
                        )
                        if (currentPhotoUri != null && hasRequiredBaseline) {
                            Spacer(Modifier.height(8.dp))
                            FigmaSecondaryButton(
                                label = "같은 사진 다시 판정",
                                onClick = onRetryJudgment,
                                enabled = !uiState.judgingInFlight,
                                outlined = true
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "다른 사진을 사용하려면 아래의 갤러리 사진으로 판정 버튼을 눌러주세요",
                                color = FigmaMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                    supportsGalleryJudgment -> FigmaManualJudgmentStateCard(
                        code = "판정 전",
                        title = "아직 판정하지 않았어요",
                        detail = "갤러리 사진으로 판정 버튼을 눌러 확인할 수 있습니다",
                        background = FigmaSurface,
                        accent = FigmaMuted,
                        icon = R.drawable.figma_icon_pending
                    )
                    else -> FigmaManualJudgmentStateCard(
                        code = "판정 없음",
                        title = "사진 판정을 사용하지 않는 단계예요",
                        detail = "수동으로 다음 단계 버튼을 눌러 진행해주세요",
                        background = FigmaSurface,
                        accent = FigmaMuted,
                        icon = R.drawable.figma_icon_manual
                    )
                }
                Spacer(Modifier.height(22.dp))
                Text("현재 할 일", color = FigmaOrange, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Text(step.instruction, color = FigmaInk, fontSize = 21.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("“다음”, “다시”, “이전” 명령은 계속 사용할 수 있어요", color = FigmaMuted, fontSize = 11.sp)
                Row {
                    TextButton(onClick = onRepeat) { Text("안내 다시", color = FigmaMuted, fontSize = 10.sp) }
                    TextButton(onClick = onPrevious) { Text("이전 단계", color = FigmaMuted, fontSize = 10.sp) }
                    TextButton(onClick = onVoice) { Text(wakeWordStatus.message, color = FigmaMuted, fontSize = 10.sp) }
                }
            }
        }
    }
}

@Composable
internal fun FigmaSummaryScreen(
    uiState: CookingSessionUiState,
    onDone: () -> Unit,
    onDeleteImages: () -> Unit,
    onGroundTruth: (String, JudgmentVerdict) -> Unit
) {
    val recipe = uiState.selectedRecipe ?: return
    val session = uiState.session ?: return
    var showEvaluation by remember { mutableStateOf(false) }
    Scaffold(containerColor = Color.White, bottomBar = { FigmaBottomBar { FigmaPrimaryButton("홈으로", onDone) } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("요리 기록", color = FigmaMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("공유", color = FigmaOrange, fontSize = 12.sp)
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${recipe.title} 완성!", color = FigmaInk, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("총 ${formatUiDuration((session.totalDurationMs / 1000L).toInt())} · 자동 확인 ${session.autoDoneCount + session.notDoneCount + session.cannotTellCount}회 · 수동 진행 ${session.manualNextCount}회", color = FigmaMuted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                FigmaChip("${session.completedStepOrders.size}단계 모두 완료", selected = false, green = true)
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 38.dp)) {
                Text("완성 사진", color = FigmaInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                FigmaResourceImage(
                    figmaRecipeImageResource(recipe, R.drawable.figma_recipe_hero),
                    "${recipe.title} 완성 사진",
                    Modifier.fillMaxWidth().height(228.dp),
                    18.dp
                )
                Spacer(Modifier.height(28.dp))
                Text("단계별 사진", color = FigmaInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                recipe.steps.chunked(2).forEach { steps ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        steps.forEach { step ->
                            val photoUri = session.lastCaptureUriByStep[step.order]
                                ?: session.baselineUriByStep[step.order]
                            val exampleImage = if (uiState.isPresentationSimulation) {
                                PresentationSimulation.captureImageResource(step.order)
                            } else {
                                figmaSummaryStepImageResource(recipe, step.order)
                            }
                            FigmaSummaryPhoto(
                                step = step,
                                uri = photoUri,
                                exampleImageResource = exampleImage,
                                modifier = Modifier.weight(1f),
                                resourceIsCapturedPhoto = uiState.isPresentationSimulation
                            )
                        }
                        if (steps.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(FigmaSurface).padding(16.dp)) {
                    Text("이번 요리 기록", color = FigmaInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    Text("자동 완료 ${session.autoDoneCount}회", color = FigmaMuted, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("수동 진행 ${session.manualNextCount}회", color = FigmaMuted, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("재촬영 ${session.cannotTellCount}회", color = FigmaMuted, fontSize = 11.sp)
                }
                TextButton(onClick = { showEvaluation = !showEvaluation }) { Text(if (showEvaluation) "평가 기록 접기" else "평가 기록 보기", color = FigmaMuted, fontSize = 11.sp) }
                if (showEvaluation) {
                    val metrics = session.evaluationMetrics()
                    FigmaMessageCard("테스트 정확도", "라벨 ${metrics.labeledCount}건 · 정확도 ${metrics.accuracyPercent}% · 잘못된 완료 ${metrics.falsePositiveCount}건 · 완료 놓침 ${metrics.missedDoneCount}건")
                    session.logs.takeLast(8).forEach { log ->
                        Text("${log.stepOrder}단계 · ${log.message}", color = FigmaMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
                        if (log.verdict != null && log.requestId != null) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                JudgmentVerdict.entries.forEach { truth -> FigmaTinyButton("정답 ${truth.name}") { onGroundTruth(log.requestId, truth) } }
                            }
                        }
                    }
                    if (session.lastCaptureUriByStep.isNotEmpty() || session.baselineUriByStep.isNotEmpty()) {
                        TextButton(onClick = onDeleteImages) { Text("세션 이미지 삭제", color = FigmaOrange, fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FigmaSummaryPhoto(
    step: RecipeStep,
    uri: String?,
    exampleImageResource: Int?,
    modifier: Modifier,
    resourceIsCapturedPhoto: Boolean = false
) {
    Column(modifier.height(150.dp).clip(RoundedCornerShape(16.dp)).background(FigmaSurface)) {
        if (uri != null) FigmaUriImage(uri, "${step.order}단계 사진", Modifier.fillMaxWidth().height(112.dp), 0.dp)
        else if (exampleImageResource != null) FigmaResourceImage(
            exampleImageResource,
            if (resourceIsCapturedPhoto) "${step.order}단계 사진" else "${step.order}단계 예시 사진",
            Modifier.fillMaxWidth().height(112.dp),
            0.dp
        )
        else Box(
            Modifier.fillMaxWidth().height(112.dp).background(FigmaWarm),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FigmaResourceIcon(R.drawable.figma_icon_pending, null, 28.dp)
                Spacer(Modifier.height(5.dp))
                Text("촬영 기록 없음", color = FigmaMuted, fontSize = 10.sp)
            }
        }
        val prefix = if (uri == null && exampleImageResource != null && !resourceIsCapturedPhoto) "예시 · " else ""
        Text("$prefix${step.order} · ${shortStepTitle(step.instruction)}", color = FigmaInk, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), maxLines = 1)
    }
}

@Composable
private fun FigmaReferenceAndCurrentStage(
    exampleImageResource: Int,
    currentUri: String,
    showOriginalCurrent: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FigmaSurface)) {
            FigmaResourceImage(
                exampleImageResource,
                "단계 예시 사진",
                Modifier.fillMaxWidth().aspectRatio(SERVER_IMAGE_ASPECT_RATIO),
                0.dp
            )
            Box(Modifier.fillMaxWidth().height(38.dp).background(FigmaWarmIcon), contentAlignment = Alignment.Center) {
                Text("단계 예시", color = Color(0xFF71420F), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (showOriginalCurrent) FigmaOriginalGalleryImageCard(currentUri)
        else FigmaServerCurrentImageCard(currentUri)
    }
}

@Composable
private fun FigmaReferenceAndResourceCurrentStage(
    exampleImageResource: Int,
    currentImageResource: Int,
    currentDescription: String,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier
                .fillMaxWidth(0.6f)
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(16.dp))
                .background(FigmaSurface)
        ) {
            FigmaResourceImage(
                exampleImageResource,
                "단계 예시 사진",
                Modifier.fillMaxWidth().aspectRatio(SERVER_IMAGE_ASPECT_RATIO),
                0.dp
            )
            Box(Modifier.fillMaxWidth().height(38.dp).background(FigmaWarmIcon), contentAlignment = Alignment.Center) {
                Text("단계 예시", color = Color(0xFF71420F), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(
            Modifier
                .fillMaxWidth(0.6f)
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(16.dp))
                .background(FigmaSurface)
        ) {
            FigmaResourceImage(
                currentImageResource,
                currentDescription,
                Modifier.fillMaxWidth().aspectRatio(SERVER_IMAGE_ASPECT_RATIO),
                0.dp
            )
            Box(Modifier.fillMaxWidth().height(38.dp).background(FigmaInk), contentAlignment = Alignment.Center) {
                Text("현재", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private data class FigmaServerImageLoad(
    val image: ImageBitmap? = null,
    val width: Int? = null,
    val height: Int? = null,
    val complete: Boolean = false
)

@Composable
private fun FigmaOriginalGalleryImageCard(currentUri: String) {
    val context = LocalContext.current
    val loaded by produceState(initialValue = FigmaImageLoad(), key1 = currentUri) {
        val image = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(currentUri))?.use { input ->
                    BitmapFactory.decodeStream(input)?.asImageBitmap()
                }
            }.getOrNull()
        }
        value = FigmaImageLoad(image = image, complete = true)
    }
    val image = loaded.image
    val originalAspectRatio = image?.let { it.width.toFloat() / it.height.coerceAtLeast(1).toFloat() } ?: SERVER_IMAGE_ASPECT_RATIO
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FigmaSurface)) {
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = "원본 규격의 갤러리 현재 사진",
                modifier = Modifier.fillMaxWidth().aspectRatio(originalAspectRatio),
                contentScale = ContentScale.Fit
            )
            loaded.complete -> Box(
                Modifier.fillMaxWidth().aspectRatio(SERVER_IMAGE_ASPECT_RATIO),
                contentAlignment = Alignment.Center
            ) { Text("갤러리 사진을 불러올 수 없어요", color = FigmaMuted, fontSize = 11.sp) }
            else -> Box(
                Modifier.fillMaxWidth().aspectRatio(SERVER_IMAGE_ASPECT_RATIO),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = FigmaOrange) }
        }
        Box(Modifier.fillMaxWidth().height(38.dp).background(FigmaInk), contentAlignment = Alignment.Center) {
            val dimensions = image?.let { " · 원본 ${it.width}×${it.height}" }.orEmpty()
            Text("현재$dimensions", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FigmaServerCurrentImageCard(currentUri: String) {
    val context = LocalContext.current
    val loaded by produceState(initialValue = FigmaServerImageLoad(), key1 = currentUri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val normalized = ImageNormalizer(context).normalize(currentUri)
                val bitmap = requireNotNull(
                    BitmapFactory.decodeByteArray(normalized.jpegBytes, 0, normalized.jpegBytes.size)
                ) { "서버 전송 이미지를 디코딩할 수 없습니다." }
                FigmaServerImageLoad(
                    image = bitmap.asImageBitmap(),
                    width = normalized.width,
                    height = normalized.height,
                    complete = true
                )
            }.getOrElse { FigmaServerImageLoad(complete = true) }
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FigmaSurface)) {
        when {
            loaded.image != null -> Image(
                bitmap = checkNotNull(loaded.image),
                contentDescription = "서버 전송 규격으로 정규화한 현재 촬영 사진",
                modifier = Modifier.fillMaxWidth().aspectRatio(SERVER_IMAGE_ASPECT_RATIO),
                contentScale = ContentScale.Fit
            )
            loaded.complete -> Box(
                Modifier.fillMaxWidth().aspectRatio(SERVER_IMAGE_ASPECT_RATIO),
                contentAlignment = Alignment.Center
            ) { Text("현재 사진을 변환할 수 없어요", color = FigmaMuted, fontSize = 11.sp) }
            else -> Box(
                Modifier.fillMaxWidth().aspectRatio(SERVER_IMAGE_ASPECT_RATIO),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = FigmaOrange) }
        }
        Box(Modifier.fillMaxWidth().height(38.dp).background(FigmaInk), contentAlignment = Alignment.Center) {
            val dimensions = if (loaded.width != null && loaded.height != null) " · ${loaded.width}×${loaded.height}" else ""
            Text("현재$dimensions", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FigmaCompareStage(baselineUri: String, currentUri: String?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xFFF2F0E5))) {
            FigmaUriImage(baselineUri, "예시 사진", Modifier.fillMaxWidth().weight(1f), 0.dp)
            Box(Modifier.fillMaxWidth().height(38.dp).background(Color(0xFFF5D138)), contentAlignment = Alignment.Center) {
                Text("예시", color = Color(0xFF291F05), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xFFF2F0E5))) {
            if (currentUri != null) FigmaUriImage(currentUri, "현재 사진", Modifier.fillMaxWidth().weight(1f), 0.dp)
            else FigmaResourceImage(R.drawable.figma_compare_current, "현재 사진", Modifier.fillMaxWidth().weight(1f), 0.dp)
            Box(Modifier.fillMaxWidth().height(38.dp).background(FigmaInk), contentAlignment = Alignment.Center) {
                Text("현재", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun FigmaRecipeEditorScreen(existing: Recipe?, onCancel: () -> Unit, onSave: (Recipe) -> Unit) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var ingredientsText by remember(existing?.id) { mutableStateOf(existing?.ingredients?.joinToString("\n") { "${it.name}: ${it.amount}" }.orEmpty()) }
    var steps by remember(existing?.id) { mutableStateOf(existing?.steps.orEmpty()) }
    var instruction by remember(existing?.id) { mutableStateOf("") }
    var checkType by remember(existing?.id) { mutableStateOf(CheckType.PRESENCE) }
    var condition by remember(existing?.id) { mutableStateOf("") }
    var earliest by remember(existing?.id) { mutableStateOf(AUTOMATIC_INSPECTION_INTERVAL_SECONDS.toString()) }
    var interval by remember(existing?.id) { mutableStateOf(AUTOMATIC_INSPECTION_INTERVAL_SECONDS.toString()) }
    var consecutive by remember(existing?.id) { mutableStateOf("1") }
    var maximum by remember(existing?.id) { mutableStateOf("120") }
    var editingIndex by remember(existing?.id) { mutableStateOf<Int?>(null) }
    var editingStep by remember(existing?.id) { mutableStateOf(false) }
    var error by remember(existing?.id) { mutableStateOf<String?>(null) }
    var dirty by remember(existing?.id) { mutableStateOf(false) }
    var confirmCancel by remember(existing?.id) { mutableStateOf(false) }

    BackHandler(enabled = !confirmCancel) {
        when {
            editingStep -> editingStep = false
            dirty -> confirmCancel = true
            else -> onCancel()
        }
    }

    fun loadStep(index: Int?) {
        editingIndex = index
        val step = index?.let(steps::getOrNull)
        instruction = step?.instruction.orEmpty()
        checkType = step?.checkType ?: CheckType.PRESENCE
        condition = step?.checkCondition.orEmpty()
        earliest = (step?.inspectionPolicy?.earliestCheckSeconds ?: AUTOMATIC_INSPECTION_INTERVAL_SECONDS).toString()
        interval = (step?.inspectionPolicy?.checkIntervalSeconds ?: AUTOMATIC_INSPECTION_INTERVAL_SECONDS).toString()
        consecutive = (step?.inspectionPolicy?.requiredConsecutiveDone ?: 1).toString()
        maximum = (step?.inspectionPolicy?.maxExpectedSeconds ?: 120).toString()
        editingStep = true
    }

    fun saveStep(): Boolean {
        val values = listOf(earliest.toIntOrNull(), interval.toIntOrNull(), consecutive.toIntOrNull(), maximum.toIntOrNull())
        if (instruction.isBlank()) { error = "단계 안내 문구를 입력하세요."; return false }
        if (checkType != CheckType.TIMER_ONLY && condition.isBlank()) { error = "자동 판정 단계에는 완료 조건이 필요합니다."; return false }
        if (values.any { it == null }) { error = "시간과 연속 DONE 기준은 숫자로 입력하세요."; return false }
        val old = editingIndex?.let(steps::getOrNull)
        val step = RecipeStep(
            order = (editingIndex ?: steps.size) + 1,
            instruction = instruction.trim(),
            checkType = checkType,
            checkCondition = condition.trim().takeIf(String::isNotBlank),
            needsStartImage = old?.needsStartImage ?: (checkType == CheckType.COLOR_CHANGE),
            inspectionPolicy = if (checkType == CheckType.TIMER_ONLY) null else InspectionPolicy(values[0]!!, values[1]!!, 3, values[2]!!, values[3]!!),
            targetIngredients = old?.targetIngredients.orEmpty(),
            voicePrompt = instruction.trim(),
            isAutoCheck = checkType != CheckType.TIMER_ONLY,
            parallelTimer = old?.parallelTimer,
            waitsForParallelTimer = old?.waitsForParallelTimer ?: false,
            baselineOnStepStart = old?.baselineOnStepStart ?: false
        )
        steps = steps.toMutableList().apply { if (editingIndex == null) add(step) else set(editingIndex!!, step) }.mapIndexed { i, item -> item.copy(order = i + 1) }
        error = null
        dirty = true
        editingStep = false
        return true
    }

    fun buildRecipe(): Recipe {
        val ingredients = ingredientsText.lines().mapNotNull { line ->
            val parts = line.split(':', limit = 2).map(String::trim)
            parts.firstOrNull()?.takeIf(String::isNotBlank)?.let { Ingredient(it, parts.getOrElse(1) { "적당량" }) }
        }
        return Recipe(existing?.id.orEmpty(), title.trim(), ingredients, steps, existing?.heroNote ?: "내가 만든 레시피", existing?.isMvpReady ?: false)
    }

    if (editingStep) {
        Scaffold(containerColor = Color.White, bottomBar = {
            FigmaRowBottomBar {
                FigmaSecondaryButton("이전 단계", { editingStep = false }, Modifier.width(120.dp))
                FigmaPrimaryButton("저장 후 다음", { saveStep() }, Modifier.weight(1f))
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                FigmaTopBar(title, { editingStep = false })
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("${(editingIndex ?: steps.size) + 1}단계 예시 등록", color = FigmaInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(20.dp))
                    FigmaSectionLabel("이 단계에서 할 일")
                FigmaEditorField(instruction, { instruction = it; dirty = true }, "안내 문구", minLines = 2)
                    Text("이 문장은 조리 중 음성으로도 안내됩니다", color = FigmaMuted, fontSize = 10.sp)
                    Spacer(Modifier.height(22.dp))
                    FigmaSectionLabel("완료 예시 사진")
                    FigmaResourceImage(R.drawable.figma_step_reference, "완료 예시", Modifier.fillMaxWidth().height(232.dp), 20.dp)
                    Text("실제 기준 사진은 조리 중 단계 시작 시 자동으로 촬영해요", color = FigmaMuted, fontSize = 10.sp, modifier = Modifier.padding(vertical = 8.dp))
                    Spacer(Modifier.height(14.dp))
                    FigmaSectionLabel("완료로 판단할 모습")
                    FigmaEditorField(condition, { condition = it; dirty = true }, "완료 조건", minLines = 2, enabled = checkType != CheckType.TIMER_ONLY)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CheckType.entries.forEach { type -> FigmaTinyButton(if (type == checkType) "✓ ${type.label}" else type.label) { checkType = type; dirty = true } }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FigmaEditorField(earliest, { earliest = it.filter(Char::isDigit); dirty = true }, "최초 검사(초)", Modifier.weight(1f))
                        FigmaEditorField(interval, { interval = it.filter(Char::isDigit); dirty = true }, "재검사(초)", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FigmaEditorField(consecutive, { consecutive = it.filter(Char::isDigit); dirty = true }, "연속 DONE", Modifier.weight(1f))
                        FigmaEditorField(maximum, { maximum = it.filter(Char::isDigit); dirty = true }, "최대 시간(초)", Modifier.weight(1f))
                    }
                    error?.let { Text(it, color = Color(0xFFC78500), fontSize = 11.sp) }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        return
    }

    Scaffold(containerColor = Color.White, bottomBar = {
        FigmaBottomBar {
            Text("단계별 사진 기준은 저장 후에도 수정할 수 있어요", color = FigmaMuted, fontSize = 10.sp)
            Spacer(Modifier.height(8.dp))
            FigmaPrimaryButton("레시피 저장", {
                val recipe = buildRecipe()
                val errors = recipe.validationErrors()
                if (errors.isEmpty()) onSave(recipe) else error = errors.first()
            })
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            FigmaTopBar("", { if (dirty) confirmCancel = true else onCancel() }, actionLabel = "취소", onAction = { if (dirty) confirmCancel = true else onCancel() })
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(if (existing == null) "새 레시피 만들기" else "레시피 편집", color = FigmaInk, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                FigmaSectionLabel("대표 사진")
                FigmaResourceImage(
                    figmaRecipeImageResource(existing, R.drawable.figma_recipe_cover),
                    "대표 사진",
                    Modifier.fillMaxWidth().height(178.dp),
                    20.dp
                )
                Text("완성 사진이나 대표 재료 사진은 추후 사진 기능에서 변경할 수 있어요", color = FigmaMuted, fontSize = 10.sp, modifier = Modifier.padding(vertical = 8.dp))
                Spacer(Modifier.height(12.dp))
                FigmaSectionLabel("기본 정보")
                FigmaEditorField(title, { title = it; dirty = true }, "레시피 제목")
                Spacer(Modifier.height(10.dp))
                FigmaEditorField(ingredientsText, { ingredientsText = it; dirty = true }, "재료 (한 줄에 이름: 수량)", minLines = 3)
                Spacer(Modifier.height(22.dp))
                FigmaSectionLabel("레시피 구성")
                FigmaBuilderRow("재료", "${ingredientsText.lines().count(String::isNotBlank)}개 재료")
                Spacer(Modifier.height(10.dp))
                FigmaBuilderRow("요리 단계", "${steps.size}단계 · 자동 확인 기준 설정", onClick = { loadStep(null) })
                steps.forEachIndexed { index, step ->
                    Spacer(Modifier.height(8.dp))
                    FigmaBuilderRow("${step.order}단계", step.instruction, onClick = { loadStep(index) })
                }
                error?.let { Text(it, color = Color(0xFFC78500), fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp)) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("변경사항을 버릴까요?") },
            text = { Text("저장하지 않은 입력 내용이 사라집니다.") },
            confirmButton = { TextButton(onClick = { confirmCancel = false; onCancel() }) { Text("버리기", color = FigmaOrange) } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("계속 편집", color = FigmaMuted) } }
        )
    }
}

@Composable
private fun FigmaBuilderRow(title: String, detail: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(16.dp)).background(FigmaSurface).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(FigmaWarmIcon))
        Column(Modifier.weight(1f)) {
            Text(title, color = FigmaInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = FigmaInk, fontSize = 13.sp, maxLines = 2)
        }
    }
}

@Composable
private fun FigmaEditorField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth(), minLines: Int = 1, enabled: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) },
        modifier = modifier,
        minLines = minLines,
        enabled = enabled,
        shape = RoundedCornerShape(15.dp),
        colors = TextFieldDefaults.colors(focusedContainerColor = FigmaSurface, unfocusedContainerColor = FigmaSurface, disabledContainerColor = FigmaSurface, focusedIndicatorColor = FigmaOrange, unfocusedIndicatorColor = Color.Transparent)
    )
}

@Composable
private fun FigmaInfoBlock(title: String, body: String) {
    Column(Modifier.fillMaxWidth().heightIn(min = 124.dp).clip(RoundedCornerShape(18.dp)).background(FigmaDivider).padding(16.dp)) {
        Text(title, color = FigmaInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(body, color = FigmaMuted, fontSize = 12.sp, lineHeight = 21.sp)
    }
}

@Composable
private fun FigmaListInfoBlock(
    title: String,
    rows: List<String>,
    leadingLabels: List<String>? = null,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "[$title]",
                color = FigmaInk,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (expanded) "접기  ▲" else "펼치기  ▼",
                color = FigmaOrange,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            rows.forEachIndexed { index, row ->
                val leadingLabel = leadingLabels?.getOrNull(index)
                if (leadingLabel == null) {
                    Text(row, color = FigmaMuted, fontSize = 14.sp, lineHeight = 25.sp)
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            leadingLabel,
                            color = FigmaInk,
                            fontSize = 14.sp,
                            lineHeight = 25.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(54.dp)
                        )
                        Text(
                            row,
                            color = FigmaMuted,
                            fontSize = 14.sp,
                            lineHeight = 25.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (index < rows.lastIndex) {
                    Spacer(Modifier.height(9.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = FigmaDivider)
                    Spacer(Modifier.height(9.dp))
                }
            }
        }
    }
}

@Composable
private fun FigmaTopBar(title: String, onBack: () -> Unit, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(82.dp).padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        FigmaIconButton(R.drawable.figma_icon_back, "뒤로", onBack)
        Text(title, color = FigmaInk, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) Text(actionLabel, color = FigmaOrange, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onAction)) else Spacer(Modifier.width(24.dp))
    }
}

@Composable
private fun FigmaBottomBar(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White).border(BorderStroke(0.5.dp, FigmaDivider)).padding(horizontal = 20.dp, vertical = 18.dp)) { content() }
}

@Composable
private fun FigmaRowBottomBar(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color.White).border(BorderStroke(0.5.dp, FigmaDivider)).padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun FigmaPrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(58.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = FigmaOrange, contentColor = Color.White, disabledContainerColor = FigmaDivider)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FigmaSecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), outlined: Boolean = false, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.height(58.dp), shape = RoundedCornerShape(18.dp), border = if (outlined) BorderStroke(1.dp, FigmaOrange) else BorderStroke(0.dp, Color.Transparent), colors = ButtonDefaults.outlinedButtonColors(containerColor = FigmaWarm, contentColor = FigmaOrange, disabledContainerColor = FigmaDivider, disabledContentColor = FigmaMuted)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FigmaSmallButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, contentPadding = ButtonDefaults.ContentPadding, colors = ButtonDefaults.buttonColors(containerColor = FigmaOrange), shape = RoundedCornerShape(14.dp), modifier = Modifier.height(36.dp)) { Text(label, fontSize = 12.sp) }
}

@Composable
private fun FigmaTinyButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, color = FigmaOrange, fontSize = 10.sp) }
}

@Composable
private fun FigmaChip(label: String, selected: Boolean = false, green: Boolean = false) {
    val background = when { green -> FigmaGreenSurface; selected -> FigmaOrange; else -> FigmaWarm }
    val color = when { green -> FigmaGreen; selected -> Color.White; else -> Color(0xFF4D3F31) }
    Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(background).padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun FigmaMessageCard(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FigmaWarm).padding(14.dp)) {
        Text(title, color = FigmaInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(detail, color = FigmaMuted, fontSize = 11.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun FigmaVoiceStrip(message: String) {
    Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).background(FigmaWarm).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FigmaResourceIcon(R.drawable.figma_icon_mic, null, 20.dp)
        Text(message, color = Color(0xFF4D3F31), fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FigmaSectionLabel(label: String) {
    Text(label, color = FigmaInk, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun FigmaResourceIcon(res: Int, description: String?, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp = width) {
    Image(painterResource(res), contentDescription = description, modifier = Modifier.width(width).height(height), contentScale = ContentScale.Fit)
}

@Composable
private fun FigmaIconButton(res: Int, description: String, onClick: () -> Unit) {
    Image(painterResource(res), contentDescription = description, modifier = Modifier.size(24.dp).clickable(onClick = onClick), contentScale = ContentScale.Fit)
}

@Composable
private fun FigmaResourceImage(res: Int, description: String, modifier: Modifier, radius: androidx.compose.ui.unit.Dp) {
    Image(painterResource(res), contentDescription = description, modifier = modifier.clip(RoundedCornerShape(radius)), contentScale = ContentScale.Crop)
}

@Composable
private fun FigmaSessionImage(uiState: CookingSessionUiState, fallback: Int, description: String, modifier: Modifier, radius: androidx.compose.ui.unit.Dp) {
    val uri = uiState.session?.lastCaptureUriByStep?.get(uiState.currentStep?.order)
        ?: (uiState.currentCaptureOutcome as? CaptureOutcome.Success)?.artifact?.imageUri
    if (uri != null) FigmaUriImage(uri, description, modifier, radius) else FigmaResourceImage(fallback, description, modifier, radius)
}

private data class FigmaImageLoad(val image: ImageBitmap? = null, val complete: Boolean = false)

@Composable
private fun FigmaUriImage(uri: String, description: String, modifier: Modifier, radius: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val loaded by produceState(initialValue = FigmaImageLoad(), key1 = uri) {
        val image = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
        }
        value = FigmaImageLoad(image, true)
    }
    when {
        loaded.image != null -> Image(checkNotNull(loaded.image), description, modifier.clip(RoundedCornerShape(radius)), contentScale = ContentScale.Crop)
        loaded.complete -> Box(modifier.clip(RoundedCornerShape(radius)).background(FigmaSurface), contentAlignment = Alignment.Center) { Text("사진을 불러올 수 없어요", color = FigmaMuted, fontSize = 11.sp) }
        else -> Box(modifier.clip(RoundedCornerShape(radius)).background(FigmaSurface), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = FigmaOrange) }
    }
}

private fun fullStepTitle(instruction: String): String = instruction
    .replace("해주세요", "")
    .replace("한다", "")
    .trim()

private fun shortStepTitle(instruction: String): String = fullStepTitle(instruction)
    .take(18)

private fun figmaGlassesConnectionLabel(state: WearableCameraState): String = when (state) {
    WearableCameraState.Ready,
    WearableCameraState.Capturing,
    WearableCameraState.Busy -> "안경 연결됨"
    WearableCameraState.Registering,
    WearableCameraState.Searching,
    WearableCameraState.Connecting -> "연결 확인 중"
    WearableCameraState.PermissionRequired -> "권한 필요"
    WearableCameraState.NotStarted,
    WearableCameraState.Disconnected,
    WearableCameraState.Released,
    is WearableCameraState.Error -> "연결 끊김"
}

private fun inspectionMessage(uiState: CookingSessionUiState, step: RecipeStep): String = when {
    !step.isAutoCheck || step.checkType == CheckType.TIMER_ONLY -> "수동으로 다음 단계로 이동해 주세요"
    uiState.judgingInFlight -> "현재 상태를 확인하고 있어요"
    uiState.nextInspectionInSeconds != null -> "${uiState.nextInspectionInSeconds}초 뒤 자동으로 상태를 확인할게요"
    else -> "준비되면 자동으로 상태를 확인할게요"
}

private fun formatUiDuration(seconds: Int): String = if (seconds < 60) "${seconds}초" else "${seconds / 60}분 ${seconds % 60}초"

private fun formatJudgmentLatency(milliseconds: Long): String =
    if (milliseconds < 1_000L) "${milliseconds}ms" else String.format(Locale.US, "%.1f초", milliseconds / 1_000.0)

internal fun isExternalGalleryImageUri(uriValue: String): Boolean {
    if (!uriValue.startsWith("content://", ignoreCase = true)) return false
    val authority = uriValue.substringAfter("content://").substringBefore('/').lowercase(Locale.US)
    return authority.isNotBlank() && !authority.endsWith(".fileprovider")
}
