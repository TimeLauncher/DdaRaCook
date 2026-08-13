package com.example.myapplication

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.camera.WearableCameraState
import com.example.myapplication.ui.theme.Ash
import com.example.myapplication.ui.theme.AshDark
import com.example.myapplication.ui.theme.Flame
import com.example.myapplication.ui.theme.Flour
import com.example.myapplication.ui.theme.Herb
import com.example.myapplication.ui.theme.Ink
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.example.myapplication.ui.theme.Pan
import com.example.myapplication.ui.theme.PanDark
import com.example.myapplication.ui.theme.Rim
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                TtaraCookApp()
            }
        }
    }
}

@Composable
private fun TtaraCookApp(
    sessionViewModel: CookingSessionViewModel = viewModel()
) {
    val uiState by sessionViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val speechController = rememberSpeechController(
        context = context,
        onTranscript = sessionViewModel::handleVoiceTranscript,
        onListeningChanged = sessionViewModel::setListening,
        onError = sessionViewModel::onSpeechError
    )
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = sessionViewModel::onAudioPermissionResult
    )

    LaunchedEffect(uiState.pendingAnnouncement?.id) {
        uiState.pendingAnnouncement?.message?.let { speechController.speak(it) }
    }

    DisposableEffect(Unit) {
        onDispose { speechController.release() }
    }

    Scaffold(containerColor = Ink) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = Ink
        ) {
            when (uiState.currentScreen) {
                AppScreen.S1_HOME -> HomeScreen(
                    uiState = uiState,
                    onRecipeClick = sessionViewModel::selectRecipe,
                    onAddRecipe = { sessionViewModel.openRecipeEditor() },
                    onResume = sessionViewModel::resumeSavedSession
                )

                AppScreen.S2_RECIPE_DETAIL -> RecipeDetailScreen(
                    recipe = uiState.selectedRecipe ?: return@Surface,
                    onBack = sessionViewModel::backToHome,
                    onStart = sessionViewModel::openDevicePreparation,
                    onEdit = { sessionViewModel.openRecipeEditor(uiState.selectedRecipeId) }
                )

                AppScreen.S3_RECIPE_EDITOR -> RecipeEditorScreen(
                    existing = uiState.selectedRecipe,
                    onCancel = sessionViewModel::cancelRecipeEditor,
                    onSave = sessionViewModel::saveRecipe
                )

                AppScreen.S4_DEVICE -> DeviceScreen(
                    uiState = uiState,
                    onAdvance = sessionViewModel::advanceFakeDeviceState,
                    onDisconnect = sessionViewModel::simulateDeviceDisconnect,
                    onError = sessionViewModel::simulateDeviceError,
                    onStartWithoutGlasses = sessionViewModel::startWithoutGlasses,
                    onStartCooking = sessionViewModel::startCooking
                )

                AppScreen.S5_COOKING -> CookingScreen(
                    uiState = uiState,
                    onStartInspection = sessionViewModel::triggerImmediateInspection,
                    onManualNext = sessionViewModel::continueToNextStep,
                    onNotYet = sessionViewModel::keepCurrentStepAndReschedule,
                    onRepeat = sessionViewModel::repeatCurrentStep,
                    onPrevious = sessionViewModel::moveToPreviousStep,
                    onDisableAuto = sessionViewModel::disableAutoMode,
                    onUseBusyCapture = sessionViewModel::setFakeCaptureBehaviorBusy,
                    onUseSuccessCapture = sessionViewModel::setFakeCaptureBehaviorSuccess,
                    onUseDisconnectCapture = sessionViewModel::setFakeCaptureBehaviorDisconnect,
                    onUseFailureCapture = sessionViewModel::setFakeCaptureBehaviorFailure,
                    onMockVerdict = sessionViewModel::setMockVerdict,
                    onSetMockEnabled = sessionViewModel::setMockJudgmentEnabled,
                    onServerBaseUrlChange = sessionViewModel::setServerBaseUrl,
                    onApplyServerBaseUrl = sessionViewModel::applyServerBaseUrl,
                    onVoice = {
                        if (uiState.audioPermissionGranted) {
                            speechController.startListening()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )

                AppScreen.S6_STEP_DONE -> StepDoneScreen(
                    uiState = uiState,
                    onContinue = sessionViewModel::continueToNextStep,
                    onUndo = sessionViewModel::moveToPreviousStep,
                    onVoice = {
                        if (uiState.audioPermissionGranted) speechController.startListening() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )

                AppScreen.S7_NEEDS_VIEW -> NeedsViewScreen(
                    uiState = uiState,
                    onRetry = sessionViewModel::triggerImmediateInspection,
                    onNext = sessionViewModel::continueToNextStep
                )

                AppScreen.S8_MANUAL -> ManualModeScreen(
                    uiState = uiState,
                    onResumeAuto = sessionViewModel::resumeAutoMode,
                    onNext = sessionViewModel::continueToNextStep,
                    onRepeat = sessionViewModel::repeatCurrentStep,
                    onPrevious = sessionViewModel::moveToPreviousStep,
                    onVoice = {
                        if (uiState.audioPermissionGranted) speechController.startListening() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )

                AppScreen.S9_SUMMARY -> SummaryScreen(
                    uiState = uiState,
                    onDone = sessionViewModel::backToHome,
                    onDeleteImages = sessionViewModel::deleteSessionImages,
                    onGroundTruth = sessionViewModel::recordGroundTruth
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    uiState: CookingSessionUiState,
    onRecipeClick: (String) -> Unit,
    onAddRecipe: () -> Unit,
    onResume: () -> Unit
) {
    ScreenContainer {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Text("따라쿡", color = Flame, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("내 레시피", color = Flour, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(8.dp))
            Text("대표 레시피 1종과 Fake Gateway로 자동 검사 흐름을 검증합니다.", color = Ash, fontSize = 14.sp, lineHeight = 22.sp)
            Spacer(modifier = Modifier.height(20.dp))
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally), color = Flame)
            } else if (uiState.loadError != null) {
                StatusBanner("레시피를 불러오지 못했습니다", uiState.loadError, BannerTone.Caution)
            } else if (uiState.recipes.isEmpty()) {
                StatusBanner("저장된 레시피가 없습니다", "레시피 추가를 눌러 첫 레시피를 만들어 주세요.", BannerTone.Neutral)
            }
            if (uiState.hasResumableSession && uiState.session != null) {
                StatusBanner(
                    title = "진행 중인 요리 이어하기",
                    detail = "${uiState.session.currentStepIndex + 1}단계부터 계속할 수 있습니다.",
                    tone = BannerTone.Progress
                )
                Spacer(modifier = Modifier.height(10.dp))
                PrimaryButton(label = "이어하기", onClick = onResume)
                Spacer(modifier = Modifier.height(16.dp))
            }
            GhostButton(label = "레시피 추가", onClick = onAddRecipe)
            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(uiState.recipes) { _, recipe ->
                    RecipeRow(recipe = recipe, onClick = { onRecipeClick(recipe.id) })
                }
            }
        }
    }
}

@Composable
private fun RecipeDetailScreen(
    recipe: Recipe,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onEdit: () -> Unit
) {
    ScreenContainer(scrollable = true) {
        AppBar(title = recipe.title, onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            InfoCard(title = "레시피 정보") {
                Text(recipe.heroNote, color = Ash, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("예상 총 시간 ${recipe.totalDurationLabel}", color = Flour, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.height(14.dp))
            InfoCard(title = "재료") {
                recipe.ingredients.forEach {
                    Text("${it.name} · ${it.amount}", color = Flour, fontSize = 13.sp)
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            recipe.steps.forEach { step ->
                StepCard(step)
                Spacer(modifier = Modifier.height(10.dp))
            }
            val errors = recipe.validationErrors()
            if (errors.isNotEmpty()) {
                Text(errors.joinToString("\n"), color = Color(0xFFE5C04A), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(10.dp))
            }
            GhostButton(label = "레시피 편집", onClick = onEdit)
            Spacer(modifier = Modifier.height(10.dp))
            PrimaryButton(label = "요리 시작", onClick = onStart, enabled = errors.isEmpty())
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RecipeEditorScreen(
    existing: Recipe?,
    onCancel: () -> Unit,
    onSave: (Recipe) -> Unit
) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var ingredientsText by remember(existing?.id) {
        mutableStateOf(existing?.ingredients?.joinToString("\n") { "${it.name}: ${it.amount}" }.orEmpty())
    }
    var steps by remember(existing?.id) { mutableStateOf(existing?.steps.orEmpty()) }
    var instruction by remember(existing?.id) { mutableStateOf("") }
    var checkType by remember(existing?.id) { mutableStateOf(CheckType.PRESENCE) }
    var condition by remember(existing?.id) { mutableStateOf("") }
    var earliest by remember(existing?.id) { mutableStateOf("10") }
    var interval by remember(existing?.id) { mutableStateOf("10") }
    var consecutive by remember(existing?.id) { mutableStateOf("1") }
    var maximum by remember(existing?.id) { mutableStateOf("120") }
    var editingIndex by remember(existing?.id) { mutableStateOf<Int?>(null) }
    var error by remember(existing?.id) { mutableStateOf<String?>(null) }
    var dirty by remember(existing?.id) { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }

    fun clearStepEditor() {
        instruction = ""
        condition = ""
        checkType = CheckType.PRESENCE
        earliest = "10"
        interval = "10"
        consecutive = "1"
        maximum = "120"
        editingIndex = null
    }

    fun saveStep() {
        val earliestValue = earliest.toIntOrNull()
        val intervalValue = interval.toIntOrNull()
        val consecutiveValue = consecutive.toIntOrNull()
        val maximumValue = maximum.toIntOrNull()
        if (instruction.isBlank()) {
            error = "단계 안내 문구를 입력하세요."
            return
        }
        if (checkType != CheckType.TIMER_ONLY && condition.isBlank()) {
            error = "자동 판정 단계에는 완료 조건이 필요합니다."
            return
        }
        if (listOf(earliestValue, intervalValue, consecutiveValue, maximumValue).any { it == null }) {
            error = "시간과 연속 DONE 기준은 숫자로 입력하세요."
            return
        }
        val step = RecipeStep(
            order = (editingIndex ?: steps.size) + 1,
            instruction = instruction.trim(),
            checkType = checkType,
            checkCondition = condition.trim().takeIf(String::isNotBlank),
            inspectionPolicy = if (checkType == CheckType.TIMER_ONLY) null else InspectionPolicy(
                earliestCheckSeconds = earliestValue!!,
                checkIntervalSeconds = intervalValue!!,
                burstSeconds = 3,
                requiredConsecutiveDone = consecutiveValue!!,
                maxExpectedSeconds = maximumValue!!
            ),
            targetIngredients = emptyList(),
            voicePrompt = instruction.trim(),
            helperText = condition.trim(),
            isAutoCheck = checkType != CheckType.TIMER_ONLY
        )
        val candidate = steps.toMutableList().apply {
            val index = editingIndex
            if (index == null) add(step) else set(index, step)
        }.mapIndexed { index, item -> item.copy(order = index + 1) }
        val draftRecipe = Recipe("draft", title, emptyList(), candidate, "사용자 레시피", false)
        val validation = draftRecipe.validationErrors().filterNot { it.contains("제목") }
        if (validation.isNotEmpty()) {
            error = validation.first()
            return
        }
        steps = candidate
        error = null
        dirty = true
        clearStepEditor()
    }

    ScreenContainer(scrollable = true) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(if (existing == null) "레시피 추가" else "레시피 편집", color = Flour, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; dirty = true },
                label = { Text("레시피 제목") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = ingredientsText,
                onValueChange = { ingredientsText = it; dirty = true },
                label = { Text("재료 (한 줄에 이름: 수량)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text(if (editingIndex == null) "단계 추가" else "${editingIndex!! + 1}단계 편집", color = Flame, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = instruction,
                onValueChange = { instruction = it; dirty = true },
                label = { Text("안내 문구") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            ControlRow("판정 유형") {
                CheckType.entries.forEach { type ->
                    SmallGhostButton(if (type == checkType) "✓ ${type.label}" else type.label) { checkType = type; dirty = true }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = condition,
                onValueChange = { condition = it; dirty = true },
                label = { Text("완료 조건") },
                modifier = Modifier.fillMaxWidth(),
                enabled = checkType != CheckType.TIMER_ONLY
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumericField("최초 검사(초)", earliest, { earliest = it; dirty = true }, Modifier.weight(1f))
                NumericField("재검사(초)", interval, { interval = it; dirty = true }, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumericField("연속 DONE", consecutive, { consecutive = it; dirty = true }, Modifier.weight(1f))
                NumericField("최대 시간(초)", maximum, { maximum = it; dirty = true }, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(10.dp))
            GhostButton(if (editingIndex == null) "단계 추가" else "단계 수정 완료", ::saveStep)
            Spacer(modifier = Modifier.height(16.dp))
            steps.forEachIndexed { index, step ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        editingIndex = index
                        instruction = step.instruction
                        checkType = step.checkType
                        condition = step.checkCondition.orEmpty()
                        earliest = (step.inspectionPolicy?.earliestCheckSeconds ?: 0).toString()
                        interval = (step.inspectionPolicy?.checkIntervalSeconds ?: 0).toString()
                        consecutive = (step.inspectionPolicy?.requiredConsecutiveDone ?: 1).toString()
                        maximum = (step.inspectionPolicy?.maxExpectedSeconds ?: 0).toString()
                    },
                    colors = CardDefaults.cardColors(containerColor = PanDark)
                ) {
                    Text("${step.order}. ${step.instruction}", modifier = Modifier.padding(12.dp), color = Flour)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            error?.let {
                Text(it, color = Color(0xFFE5C04A), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(10.dp))
            }
            PrimaryButton("레시피 저장", onClick = {
                val ingredients = ingredientsText.lines().mapNotNull { line ->
                    val parts = line.split(':', limit = 2).map(String::trim)
                    parts.firstOrNull()?.takeIf(String::isNotBlank)?.let { Ingredient(it, parts.getOrElse(1) { "적당량" }) }
                }
                val recipe = Recipe(
                    id = existing?.id.orEmpty(),
                    title = title.trim(),
                    ingredients = ingredients,
                    steps = steps,
                    heroNote = existing?.heroNote ?: "내가 만든 레시피",
                    isMvpReady = existing?.isMvpReady ?: false
                )
                val errors = recipe.validationErrors()
                if (errors.isEmpty()) onSave(recipe) else error = errors.first()
            })
            Spacer(modifier = Modifier.height(10.dp))
            GhostButton("취소", onClick = { if (dirty) confirmCancel = true else onCancel() })
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("변경사항을 버릴까요?") },
            text = { Text("저장하지 않은 입력 내용이 사라집니다.") },
            confirmButton = { SmallGhostButton("버리기") { confirmCancel = false; onCancel() } },
            dismissButton = { SmallGhostButton("계속 편집") { confirmCancel = false } }
        )
    }
}

@Composable
private fun NumericField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit)) },
        label = { Text(label) },
        modifier = modifier,
        singleLine = true
    )
}

@Composable
private fun DeviceScreen(
    uiState: CookingSessionUiState,
    onAdvance: () -> Unit,
    onDisconnect: () -> Unit,
    onError: () -> Unit,
    onStartWithoutGlasses: () -> Unit,
    onStartCooking: () -> Unit
) {
    ScreenContainer {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            StatusBanner(
                title = deviceTitle(uiState.cameraState),
                detail = uiState.deviceHint,
                tone = when (uiState.cameraState) {
                    WearableCameraState.Ready -> BannerTone.Success
                    WearableCameraState.Disconnected, is WearableCameraState.Error -> BannerTone.Caution
                    else -> BannerTone.Progress
                }
            )
            Spacer(modifier = Modifier.height(18.dp))
            if (uiState.cameraState == WearableCameraState.Ready) {
                PrimaryButton(label = "1단계 시작", onClick = onStartCooking)
                Spacer(modifier = Modifier.height(10.dp))
                GhostButton(label = "연결 끊김 시뮬레이션", onClick = onDisconnect)
            } else {
                PrimaryButton(label = "다음 연결 단계", onClick = onAdvance)
                Spacer(modifier = Modifier.height(10.dp))
                GhostButton(label = "오류 시뮬레이션", onClick = onError)
                Spacer(modifier = Modifier.height(10.dp))
                GhostButton(label = "안경 없이 시작", onClick = onStartWithoutGlasses)
            }
        }
    }
}

@Composable
private fun CookingScreen(
    uiState: CookingSessionUiState,
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
    onVoice: () -> Unit
) {
    val recipe = uiState.selectedRecipe ?: return
    val step = uiState.currentStep ?: return
    val session = uiState.session ?: return

    ScreenContainer(scrollable = true) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(recipe.title, color = AshDark, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(10.dp))
            Text("STEP ${step.order} / ${recipe.steps.size}", color = Flame, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(10.dp))
            Text(step.instruction, color = Flour, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(12.dp))
            Text(step.voicePrompt, color = Ash, fontSize = 14.sp, lineHeight = 22.sp)
            Spacer(modifier = Modifier.height(18.dp))
            StatusBanner(
                title = phaseTitle(session.phase),
                detail = cookingDetail(uiState),
                tone = BannerTone.Progress
            )
            val progress = (uiState.stepElapsedSeconds.toFloat() / (step.inspectionPolicy?.maxExpectedSeconds ?: 1).coerceAtLeast(1)).coerceIn(0f, 1f)
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("경과 ${formatDuration(uiState.stepElapsedSeconds * 1_000L)}", color = Ash, fontSize = 12.sp)
            if (uiState.maxExpectedExceeded) {
                Spacer(modifier = Modifier.height(8.dp))
                StatusBanner("예상 시간 초과", "계속 조리하거나 수동 모드로 전환할 수 있습니다.", BannerTone.Caution)
            }
            Spacer(modifier = Modifier.height(14.dp))
            InfoCard(title = "자동 검사") {
                Text("최초 검사 ${step.inspectionPolicy?.earliestCheckSeconds ?: 0}초", color = Flour, fontSize = 12.sp)
                Text("재검사 간격 ${step.inspectionPolicy?.checkIntervalSeconds ?: 0}초", color = Flour, fontSize = 12.sp)
                Text("연속 DONE ${step.inspectionPolicy?.requiredConsecutiveDone ?: 1}회", color = Flour, fontSize = 12.sp)
                Text("다음 검사까지 ${uiState.nextInspectionInSeconds ?: "-"}", color = Ash, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(14.dp))
            ControlRow("음성/수동") {
                VoiceButton(uiState = uiState, onClick = onVoice)
                SmallGhostButton("다음", onManualNext)
                SmallGhostButton("아직", onNotYet)
                SmallGhostButton("다시", onRepeat)
                SmallGhostButton("이전", onPrevious)
                SmallGhostButton("확인해줘", onStartInspection)
                SmallGhostButton("자동 확인 끄기", onDisableAuto)
            }
            Spacer(modifier = Modifier.height(14.dp))
            ControlRow("Fake Capture") {
                SmallGhostButton("Success", onUseSuccessCapture)
                SmallGhostButton("BUSY", onUseBusyCapture)
                SmallGhostButton("Disconnect", onUseDisconnectCapture)
                SmallGhostButton("Failure", onUseFailureCapture)
            }
            Spacer(modifier = Modifier.height(14.dp))
            ControlRow("Fake Verdict") {
                SmallGhostButton(if (uiState.useMockJudgment) "✓ Fake 판정" else "Fake 판정") { onSetMockEnabled(true) }
                SmallGhostButton(if (!uiState.useMockJudgment) "✓ 실제 서버" else "실제 서버") { onSetMockEnabled(false) }
                JudgmentVerdict.entries.forEach { verdict ->
                    SmallGhostButton(verdict.name, { onMockVerdict(verdict) })
                }
            }
            if (!uiState.useMockJudgment) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.serverBaseUrl,
                    onValueChange = onServerBaseUrlChange,
                    label = { Text("판정 서버 주소") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                GhostButton("서버 주소 적용 및 상태 확인", onApplyServerBaseUrl)
                Spacer(modifier = Modifier.height(8.dp))
                StatusBanner(
                    title = when (uiState.serverReady) {
                        true -> "서버 연결됨"
                        false -> "서버 연결 실패"
                        null -> "서버 확인 중"
                    },
                    detail = uiState.serverStatusMessage ?: "판정 서버 상태를 확인합니다.",
                    tone = if (uiState.serverReady == true) BannerTone.Success else BannerTone.Caution
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            PrimaryButton(label = "검사 시작", onClick = onStartInspection)
            Spacer(modifier = Modifier.height(14.dp))
            if (uiState.currentCaptureOutcome != null || uiState.judgeError != null) {
                InfoCard(title = "최근 결과") {
                    Text(uiState.currentCaptureOutcome.toString(), color = Flour, fontSize = 11.sp, lineHeight = 18.sp)
                    if (uiState.judgeError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(uiState.judgeError, color = Color(0xFFE5C04A), fontSize = 11.sp)
                    }
                    if (session.lastRoundTripMs != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("roundTrip ${session.lastRoundTripMs}ms / vlm ${session.lastVlmLatencyMs ?: "-"}ms", color = Ash, fontSize = 11.sp)
                    }
                }
            }
            uiState.speechError?.let {
                Spacer(modifier = Modifier.height(10.dp))
                Text(it, color = Color(0xFFE5C04A), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StepDoneScreen(
    uiState: CookingSessionUiState,
    onContinue: () -> Unit,
    onUndo: () -> Unit,
    onVoice: () -> Unit
) {
    val step = uiState.currentStep ?: return
    val session = uiState.session ?: return
    ScreenContainer {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center
        ) {
            StatusBanner(
                title = "${step.order}단계 완료",
                detail = "자동 DONE ${session.autoDoneCount}회 · 다음 단계로 이동할 수 있습니다.",
                tone = BannerTone.Success
            )
            Spacer(modifier = Modifier.height(18.dp))
            PrimaryButton(label = "다음 단계", onClick = onContinue)
            Spacer(modifier = Modifier.height(10.dp))
            GhostButton(label = "이전 단계로 복귀", onClick = onUndo)
            Spacer(modifier = Modifier.height(10.dp))
            VoiceButton(uiState, onVoice)
        }
    }
}

@Composable
private fun NeedsViewScreen(
    uiState: CookingSessionUiState,
    onRetry: () -> Unit,
    onNext: () -> Unit
) {
    val session = uiState.session ?: return
    ScreenContainer {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center
        ) {
            StatusBanner(
                title = "확인 필요",
                detail = buildString {
                    append(if (session.cannotTellStreak == 1) "대상이 잘 보이지 않습니다." else "두 번째 확인도 어려웠습니다.")
                    session.lastReasonCode?.let { append(" 사유: ${it.label}.") }
                    uiState.nextInspectionInSeconds?.let { append(" ${it}초 뒤 다시 확인합니다.") }
                },
                tone = BannerTone.Caution
            )
            Spacer(modifier = Modifier.height(18.dp))
            PrimaryButton(label = "즉시 재검사", onClick = onRetry)
            Spacer(modifier = Modifier.height(10.dp))
            GhostButton(label = "수동으로 다음", onClick = onNext)
        }
    }
}

@Composable
private fun ManualModeScreen(
    uiState: CookingSessionUiState,
    onResumeAuto: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    onPrevious: () -> Unit,
    onVoice: () -> Unit
) {
    val step = uiState.currentStep ?: return
    val session = uiState.session ?: return
    ScreenContainer {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center
        ) {
            StatusBanner(
                title = "수동 모드",
                detail = "자동 확인 실패 ${session.cannotTellCount}회 · 현재 ${step.order}단계",
                tone = BannerTone.Neutral
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(step.instruction, color = Flour, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(16.dp))
            PrimaryButton(label = "다음 단계", onClick = onNext)
            Spacer(modifier = Modifier.height(10.dp))
            GhostButton(label = "자동 확인 다시 켜기", onClick = onResumeAuto)
            uiState.serverStatusMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = if (uiState.serverReady == false) Color(0xFF9A6700) else Ash, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(10.dp))
            GhostButton(label = "안내 다시 듣기", onClick = onRepeat)
            Spacer(modifier = Modifier.height(10.dp))
            GhostButton(label = "이전 단계", onClick = onPrevious)
            Spacer(modifier = Modifier.height(10.dp))
            VoiceButton(uiState, onVoice)
        }
    }
}

@Composable
private fun SummaryScreen(
    uiState: CookingSessionUiState,
    onDone: () -> Unit,
    onDeleteImages: () -> Unit,
    onGroundTruth: (String, JudgmentVerdict) -> Unit
) {
    val recipe = uiState.selectedRecipe ?: return
    val session = uiState.session ?: return
    ScreenContainer(scrollable = true) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(recipe.title, color = Flour, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(8.dp))
            Text("세션 요약", color = Ash, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(18.dp))
            SummaryRow("자동 DONE", "${session.autoDoneCount}회")
            SummaryRow("NOT_DONE", "${session.notDoneCount}회")
            SummaryRow("CANNOT_TELL", "${session.cannotTellCount}회")
            SummaryRow("직접 넘김", "${session.manualNextCount}회")
            SummaryRow("DONE 후 이전", "${session.undoDoneCount}회")
            SummaryRow("카메라 활성 시간", "${session.cameraActiveMs / 1000.0}s")
            SummaryRow("총 조리 시간", formatDuration(session.totalDurationMs))
            SummaryRow("완료 단계", "${session.completedStepOrders.size}/${recipe.steps.size}")
            recipe.steps.forEach { step ->
                session.stepDurationMs(step.order)?.let { duration ->
                    SummaryRow("${step.order}단계 소요", formatDuration(duration))
                }
            }
            val longest = recipe.steps.mapNotNull { step -> session.stepDurationMs(step.order)?.let { step.order to it } }.maxByOrNull { it.second }
            longest?.let { SummaryRow("가장 오래 걸린 단계", "${it.first}단계 · ${formatDuration(it.second)}") }
            val metrics = session.evaluationMetrics()
            Spacer(modifier = Modifier.height(18.dp))
            InfoCard("테스트 정확도") {
                Text("라벨 ${metrics.labeledCount}건 · 정확도 ${metrics.accuracyPercent}%", color = Flour, fontSize = 12.sp)
                Text("잘못된 완료 ${metrics.falsePositiveCount}건 · 완료 놓침 ${metrics.missedDoneCount}건 · 판정 불가 ${metrics.cannotTellCount}건", color = Ash, fontSize = 12.sp)
            }
            if (session.lastCaptureUriByStep.isNotEmpty()) {
                Spacer(modifier = Modifier.height(18.dp))
                InfoCard("단계별 대표 캡처") {
                    session.lastCaptureUriByStep.toSortedMap().forEach { (stepOrder, uri) ->
                        Text("${stepOrder}단계", color = Flour, fontSize = 12.sp)
                        CaptureThumbnail(uri, "${stepOrder}단계 대표 캡처")
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                GhostButton("세션 이미지 삭제", onDeleteImages)
            }
            Spacer(modifier = Modifier.height(18.dp))
            InfoCard(title = "로그") {
                session.logs.takeLast(8).forEach { log ->
                    Text("${log.stepOrder}단계 · ${log.message}", color = Flour, fontSize = 12.sp)
                    if (log.verdict != null && log.requestId != null) {
                        log.imageUri?.let { CaptureThumbnail(it, "${log.stepOrder}단계 판정 이미지") }
                        Text(
                            "요청 ${log.requestedAtMs ?: "-"} · 응답 ${log.respondedAtMs ?: "-"} · 정답 ${log.groundTruth?.name ?: "미입력"}",
                            color = Ash,
                            fontSize = 10.sp
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            JudgmentVerdict.entries.forEach { truth ->
                                SmallGhostButton("정답 ${truth.name}") { onGroundTruth(log.requestId, truth) }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
            PrimaryButton(label = "완료", onClick = onDone)
        }
    }
}

@Composable
private fun RecipeRow(recipe: Recipe, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PanDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.recipe_hero),
                contentDescription = "${recipe.title} 대표 이미지",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Rim, RoundedCornerShape(10.dp))
            )
            Column {
                Text(recipe.title, color = Flour, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("${recipe.steps.size}단계 · ${recipe.totalDurationLabel}", color = Ash, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(recipe.heroNote, color = if (recipe.isMvpReady) Herb else AshDark, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun StepCard(step: RecipeStep) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Pan)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text("${step.order}단계", color = Flame, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(step.instruction, color = Flour, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text("${step.checkType.userLabel} · ${step.checkType.label}", color = Ash, fontSize = 12.sp)
            step.inspectionPolicy?.let { policy ->
                Text("예상 ${formatDuration(policy.maxExpectedSeconds * 1_000L)} · 최초 검사 ${policy.earliestCheckSeconds}초", color = AshDark, fontSize = 11.sp)
            }
            step.checkCondition?.let {
                Text("완료 조건 · $it", color = Ash, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = PanDark)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(title, color = Ash, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun CaptureThumbnail(uriValue: String, description: String) {
    val context = LocalContext.current
    val bitmap = remember(uriValue) {
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(uriValue))?.use(BitmapFactory::decodeStream)
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Rim, RoundedCornerShape(12.dp))
        )
    } else {
        Text("이미지를 표시할 수 없습니다.", color = Ash, fontSize = 11.sp)
    }
}

@Composable
private fun StatusBanner(title: String, detail: String, tone: BannerTone) {
    val palette = statusPalette(tone)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.background)
            .border(1.dp, palette.border, RoundedCornerShape(16.dp))
            .padding(16.dp)
            .semantics { contentDescription = "$title. $detail" }
    ) {
        Column {
            Text(title, color = Flour, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(detail, color = Ash, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun ControlRow(title: String, content: @Composable () -> Unit) {
    Text(title, color = Ash, fontSize = 11.sp)
    Spacer(modifier = Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

@Composable
private fun SmallGhostButton(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick),
        color = Color.Transparent,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, Rim)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            color = Flour,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun VoiceButton(uiState: CookingSessionUiState, onClick: () -> Unit) {
    SmallGhostButton(
        label = if (uiState.isListening) "듣는 중..." else if (uiState.audioPermissionGranted) "음성 입력" else "마이크 권한 요청",
        onClick = onClick
    )
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Ash, fontSize = 13.sp)
        Text(value, color = Flour, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
    HorizontalDivider(color = Rim)
}

@Composable
private fun AppBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("←", color = Ash, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onBack))
        Text(title, color = Flour, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ScreenContainer(scrollable: Boolean = false, content: @Composable () -> Unit) {
    val modifier = if (scrollable) {
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    } else {
        Modifier.fillMaxSize()
    }
    Box(modifier = modifier.background(Ink)) {
        content()
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Flame, contentColor = Ink)
    ) {
        Text(label, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun GhostButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Rim),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Flour)
    ) {
        Text(label)
    }
}

private fun phaseTitle(phase: CookingPhase): String = when (phase) {
    CookingPhase.IDLE -> "대기 중"
    CookingPhase.PREPARING_DEVICE -> "기기 준비 중"
    CookingPhase.READY -> "기기 준비 완료"
    CookingPhase.STEP_STARTING -> "단계 시작"
    CookingPhase.WAITING_FOR_CHECK -> "다음 검사 대기"
    CookingPhase.PROMPTING_USER -> "팬을 봐주세요"
    CookingPhase.CAPTURING -> "촬영 중"
    CookingPhase.JUDGING -> "판정 중"
    CookingPhase.NETWORK_RETRY -> "네트워크 재시도"
    CookingPhase.NEEDS_VIEW -> "확인 필요"
    CookingPhase.STEP_COMPLETED -> "단계 완료"
    CookingPhase.MANUAL_MODE -> "수동 모드"
    CookingPhase.SESSION_COMPLETED -> "세션 완료"
}

private fun cookingDetail(uiState: CookingSessionUiState): String {
    val session = uiState.session ?: return ""
    val captureState = uiState.cameraState
    val next = uiState.nextInspectionInSeconds?.let { " · 다음 검사 ${it}초" }.orEmpty()
    return "카메라 ${captureState::class.simpleName} · CANNOT_TELL ${session.cannotTellStreak}회${next}"
}

private fun deviceTitle(state: WearableCameraState): String = when (state) {
    WearableCameraState.NotStarted -> "연결 전"
    WearableCameraState.Registering -> "등록 중"
    WearableCameraState.PermissionRequired -> "권한 필요"
    WearableCameraState.Searching -> "안경 검색 중"
    WearableCameraState.Connecting -> "세션 연결 중"
    WearableCameraState.Ready -> "안경 카메라 준비 완료"
    WearableCameraState.Capturing -> "촬영 중"
    WearableCameraState.Busy -> "촬영 중복 요청"
    WearableCameraState.Disconnected -> "연결 끊김"
    is WearableCameraState.Error -> "오류"
    WearableCameraState.Released -> "해제됨"
}

internal enum class BannerTone {
    Progress,
    Success,
    Caution,
    Neutral
}

internal data class StatusPalette(val background: Color, val border: Color)

internal fun statusPalette(tone: BannerTone): StatusPalette = when (tone) {
    BannerTone.Progress -> StatusPalette(Pan, Flame)
    BannerTone.Success -> StatusPalette(Herb.copy(alpha = 0.12f), Herb)
    BannerTone.Caution -> StatusPalette(Color(0xFFFFF4CC), Color(0xFFE5A800))
    BannerTone.Neutral -> StatusPalette(PanDark, Rim)
}

private class SpeechController(
    private val textToSpeech: TextToSpeech?,
    private val speechRecognizer: SpeechRecognizer?,
    private val context: Context,
    private val onTranscript: (String) -> Unit,
    private val onListeningChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    fun speak(message: String) {
        textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "ttara-cook")
    }

    fun startListening() {
        if (speechRecognizer == null) {
            onError("이 기기에서는 음성 인식을 사용할 수 없습니다.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        onListeningChanged(true)
        speechRecognizer.startListening(intent)
    }

    fun release() {
        speechRecognizer?.destroy()
        textToSpeech?.shutdown()
    }
}

@Composable
private fun rememberSpeechController(
    context: Context,
    onTranscript: (String) -> Unit,
    onListeningChanged: (Boolean) -> Unit,
    onError: (String) -> Unit
): SpeechController {
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    DisposableEffect(context) {
        val createdTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
            }
        }
        tts = createdTts

        val createdRecognizer = if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        onListeningChanged(false)
                    }
                    override fun onError(error: Int) {
                        onListeningChanged(false)
                        onError("음성 인식에 실패했습니다. 화면 버튼을 사용하거나 다시 시도하세요.")
                    }
                    override fun onResults(results: Bundle?) {
                        onListeningChanged(false)
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!text.isNullOrBlank()) onTranscript(text)
                    }
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }
        } else {
            null
        }
        recognizer = createdRecognizer

        onDispose {
            createdRecognizer?.destroy()
            createdTts.shutdown()
        }
    }

    return remember(tts, recognizer) {
        SpeechController(tts, recognizer, context, onTranscript, onListeningChanged, onError)
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}분 ${seconds}초" else "${seconds}초"
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun PreviewApp() {
    MyApplicationTheme {
        TtaraCookApp()
    }
}
