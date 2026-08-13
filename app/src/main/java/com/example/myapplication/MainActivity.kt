package com.example.myapplication

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.camera.CaptureArtifact
import com.example.myapplication.camera.CaptureOutcome
import com.example.myapplication.camera.WearableCameraState
import com.example.myapplication.camera.DatCameraPermissionContract
import com.example.myapplication.camera.datAndroidPermissions
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
import com.example.myapplication.voice.WakeWordController
import com.example.myapplication.voice.WakeWordStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val lifecycleOwner = LocalLifecycleOwner.current
    var appInForeground by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            appInForeground = lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        appInForeground = lifecycleOwner.lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val wakeControllerHolder = remember { arrayOfNulls<WakeWordController>(1) }
    val speechControllerHolder = remember { arrayOfNulls<SpeechController>(1) }
    var wakeWordStatus by remember { mutableStateOf(WakeWordStatus("음성 호출 준비 전")) }
    val speechController = rememberSpeechController(
        context = context,
        onTranscript = sessionViewModel::handleVoiceTranscript,
        onListeningChanged = sessionViewModel::setListening,
        onError = sessionViewModel::onSpeechError,
        onRecognitionFinished = {
            speechControllerHolder[0]?.recognitionFinished()
        },
        onCommandRecognitionUseChanged = { active ->
            if (active) wakeControllerHolder[0]?.pause() else wakeControllerHolder[0]?.resume()
        }
    )
    speechControllerHolder[0] = speechController
    val wakeWordController = remember(context, speechController) {
        WakeWordController(
            context = context,
            onWakeWord = {
                speechController.speak(
                    message = "네.",
                    onStart = speechController::startListening
                )
            },
            onStatus = { wakeWordStatus = it }
        )
    }
    DisposableEffect(wakeWordController) {
        wakeControllerHolder[0] = wakeWordController
        onDispose {
            if (wakeControllerHolder[0] === wakeWordController) {
                wakeControllerHolder[0] = null
            }
            wakeWordController.release()
        }
    }
    val voiceScreenActive = uiState.currentScreen in setOf(
        AppScreen.S5_COOKING,
        AppScreen.S6_STEP_DONE,
        AppScreen.S7_NEEDS_VIEW,
        AppScreen.S8_MANUAL
    )
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = sessionViewModel::onAudioPermissionResult
    )
    var wakePermissionRequested by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        sessionViewModel.onAudioPermissionResult(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val datAndroidPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { result ->
            sessionViewModel.initializeDatCamera(result.values.all { it })
        }
    )
    val wearableCameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = DatCameraPermissionContract(),
        onResult = sessionViewModel::onWearableCameraPermissionResult
    )

    LaunchedEffect(uiState.currentScreen, uiState.useFakeCamera) {
        if (!uiState.useFakeCamera && uiState.currentScreen == AppScreen.S4_DEVICE) {
            datAndroidPermissionLauncher.launch(datAndroidPermissions)
        }
    }

    LaunchedEffect(voiceScreenActive, uiState.audioPermissionGranted, appInForeground) {
        if (
            appInForeground &&
            voiceScreenActive &&
            !uiState.audioPermissionGranted &&
            !wakePermissionRequested
        ) {
            wakePermissionRequested = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(
        voiceScreenActive,
        uiState.audioPermissionGranted,
        appInForeground,
        wakeWordController
    ) {
        if (appInForeground && voiceScreenActive && uiState.audioPermissionGranted) {
            wakeWordController.activate()
        } else {
            wakeWordController.deactivate()
        }
    }

    LaunchedEffect(uiState.pendingAnnouncement?.id) {
        uiState.pendingAnnouncement?.message?.let { speechController.speak(it) }
    }

    DisposableEffect(speechController) {
        onDispose {
            if (speechControllerHolder[0] === speechController) {
                speechControllerHolder[0] = null
            }
            speechController.release()
        }
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
                    onAdvance = {
                        if (
                            !uiState.useFakeCamera &&
                            uiState.cameraState == WearableCameraState.PermissionRequired
                        ) {
                            wearableCameraPermissionLauncher.launch(Unit)
                        } else {
                            (context as? Activity)?.let(sessionViewModel::advanceDeviceSetup)
                        }
                    },
                    onDisconnect = sessionViewModel::simulateDeviceDisconnect,
                    onError = sessionViewModel::simulateDeviceError,
                    onStartWithoutGlasses = sessionViewModel::startWithoutGlasses,
                    onStartCooking = sessionViewModel::startCooking
                )

                AppScreen.S5_COOKING -> CookingScreen(
                    uiState = uiState,
                    wakeWordStatus = wakeWordStatus,
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
                    wakeWordStatus = wakeWordStatus,
                    onContinue = sessionViewModel::continueToNextStep,
                    onUndo = sessionViewModel::moveToPreviousStep,
                    onVoice = {
                        if (uiState.audioPermissionGranted) speechController.startListening() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )

                AppScreen.S7_NEEDS_VIEW -> NeedsViewScreen(
                    uiState = uiState,
                    wakeWordStatus = wakeWordStatus,
                    onRetry = sessionViewModel::triggerImmediateInspection,
                    onNext = sessionViewModel::continueToNextStep
                )

                AppScreen.S8_MANUAL -> ManualModeScreen(
                    uiState = uiState,
                    wakeWordStatus = wakeWordStatus,
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
                    onDeleteImages = sessionViewModel::deleteSessionImages
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
            Text(
                if (uiState.useFakeCamera) {
                    "대표 레시피 1종과 Fake Gateway로 자동 검사 흐름을 검증합니다."
                } else {
                    "Meta 안경으로 필요한 순간에만 촬영해 조리 단계를 확인합니다."
                },
                color = Ash,
                fontSize = 14.sp,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(20.dp))
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
                PrimaryButton(
                    label = if (uiState.resumeAutoAfterDeviceSetup) "자동 확인 재개" else "1단계 시작",
                    onClick = onStartCooking
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (uiState.useFakeCamera) {
                    GhostButton(label = "연결 끊김 시뮬레이션", onClick = onDisconnect)
                }
            } else {
                PrimaryButton(
                    label = when {
                        uiState.useFakeCamera -> "다음 연결 단계"
                        uiState.cameraState == WearableCameraState.PermissionRequired -> "카메라 권한 허용"
                        uiState.cameraState == WearableCameraState.NotStarted -> "안경 등록"
                        else -> "연결 다시 준비"
                    },
                    onClick = onAdvance,
                    enabled = uiState.cameraState != WearableCameraState.Registering
                )
                if (uiState.useFakeCamera) {
                    Spacer(modifier = Modifier.height(10.dp))
                    GhostButton(label = "오류 시뮬레이션", onClick = onError)
                }
                Spacer(modifier = Modifier.height(10.dp))
                GhostButton(label = "안경 없이 시작", onClick = onStartWithoutGlasses)
            }
        }
    }
}

@Composable
private fun CookingScreen(
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
            WakeWordStatusText(wakeWordStatus)
            if (uiState.useFakeCamera) {
                Spacer(modifier = Modifier.height(14.dp))
                ControlRow("Fake Capture") {
                    SmallGhostButton("Success", onUseSuccessCapture)
                    SmallGhostButton("BUSY", onUseBusyCapture)
                    SmallGhostButton("Disconnect", onUseDisconnectCapture)
                    SmallGhostButton("Failure", onUseFailureCapture)
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            ControlRow("Fake Verdict") {
                SmallGhostButton(if (uiState.useMockJudgment) "✓ Fake 판정" else "Fake 판정") { onSetMockEnabled(true) }
                SmallGhostButton(if (!uiState.useMockJudgment) "✓ 실제 서버" else "실제 서버") { onSetMockEnabled(false) }
                JudgmentVerdict.entries.forEach { verdict ->
                    SmallGhostButton(verdict.name, { onMockVerdict(verdict) })
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
            PrimaryButton(label = "검사 시작", onClick = onStartInspection)
            Spacer(modifier = Modifier.height(14.dp))
            (uiState.currentCaptureOutcome as? CaptureOutcome.Success)?.let {
                CapturedPhotoCard(it.artifact, isFakeCamera = uiState.useFakeCamera)
                Spacer(modifier = Modifier.height(14.dp))
            }
            if (uiState.currentCaptureOutcome != null || uiState.judgeError != null) {
                InfoCard(title = "최근 결과") {
                    val captureMessage = when (val outcome = uiState.currentCaptureOutcome) {
                        is CaptureOutcome.Success -> "촬영 성공 · 판정 입력 이미지 저장 완료"
                        is CaptureOutcome.Failure -> "${outcome.kind} · ${outcome.userMessage}"
                        null -> null
                    }
                    captureMessage?.let {
                        Text(it, color = Flour, fontSize = 11.sp, lineHeight = 18.sp)
                    }
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
            uiState.lastVoiceTranscript?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text("최근 음성 인식: $it", color = Ash, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StepDoneScreen(
    uiState: CookingSessionUiState,
    wakeWordStatus: WakeWordStatus,
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
            (uiState.currentCaptureOutcome as? CaptureOutcome.Success)?.let {
                Spacer(modifier = Modifier.height(14.dp))
                CapturedPhotoCard(it.artifact, isFakeCamera = uiState.useFakeCamera, compact = true)
            }
            Spacer(modifier = Modifier.height(18.dp))
            PrimaryButton(label = "다음 단계", onClick = onContinue)
            Spacer(modifier = Modifier.height(10.dp))
            GhostButton(label = "이전 단계로 복귀", onClick = onUndo)
            Spacer(modifier = Modifier.height(10.dp))
            VoiceButton(uiState, onVoice)
            WakeWordStatusText(wakeWordStatus)
        }
    }
}

@Composable
private fun NeedsViewScreen(
    uiState: CookingSessionUiState,
    wakeWordStatus: WakeWordStatus,
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
            WakeWordStatusText(wakeWordStatus)
        }
    }
}

@Composable
private fun ManualModeScreen(
    uiState: CookingSessionUiState,
    wakeWordStatus: WakeWordStatus,
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
            Spacer(modifier = Modifier.height(10.dp))
            GhostButton(label = "안내 다시 듣기", onClick = onRepeat)
            Spacer(modifier = Modifier.height(10.dp))
            GhostButton(label = "이전 단계", onClick = onPrevious)
            Spacer(modifier = Modifier.height(10.dp))
            VoiceButton(uiState, onVoice)
            WakeWordStatusText(wakeWordStatus)
        }
    }
}

@Composable
private fun SummaryScreen(
    uiState: CookingSessionUiState,
    onDone: () -> Unit,
    onDeleteImages: () -> Unit
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
            if (
                session.baselineUriByStep.isNotEmpty() ||
                session.lastCaptureUriByStep.isNotEmpty()
            ) {
                SessionPhotoGallery(recipe = recipe, session = session)
                Spacer(modifier = Modifier.height(18.dp))
                GhostButton("세션 이미지 삭제", onDeleteImages)
            }
            Spacer(modifier = Modifier.height(18.dp))
            InfoCard(title = "로그") {
                session.logs.takeLast(8).forEach { log ->
                    Text("${log.stepOrder}단계 · ${log.message}", color = Flour, fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
            PrimaryButton(label = "완료", onClick = onDone)
        }
    }
}

@Composable
private fun SessionPhotoGallery(recipe: Recipe, session: CookingSession) {
    Text("단계별 촬영 기록", color = Flour, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(10.dp))
    recipe.steps.forEach { step ->
        StepPhotoGallery(
            step = step,
            baselineUri = session.baselineUriByStep[step.order],
            latestUri = session.lastCaptureUriByStep[step.order]
        )
        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun StepPhotoGallery(
    step: RecipeStep,
    baselineUri: String?,
    latestUri: String?
) {
    InfoCard(title = "${step.order}단계 · ${step.instruction}") {
        when {
            baselineUri == null && latestUri == null ->
                Text("촬영 기록 없음", color = Ash, fontSize = 12.sp)

            baselineUri != null && baselineUri == latestUri ->
                UriPhoto(uri = baselineUri, label = "기준 사진")

            else -> {
                baselineUri?.let {
                    UriPhoto(uri = it, label = "단계 시작 기준 사진")
                }
                if (baselineUri != null && latestUri != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                }
                latestUri?.let {
                    UriPhoto(uri = it, label = "마지막 확인 사진")
                }
            }
        }
    }
}

@Composable
private fun UriPhoto(uri: String, label: String) {
    val context = LocalContext.current
    val loaded by produceState(
        initialValue = CaptureImageLoad(),
        key1 = uri
    ) {
        val image = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                    BitmapFactory.decodeStream(input)?.asImageBitmap()
                }
            }.getOrNull()
        }
        value = CaptureImageLoad(image = image, complete = true)
    }

    Text(label, color = Herb, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(6.dp))
    when {
        loaded.image != null -> Image(
            bitmap = checkNotNull(loaded.image),
            contentDescription = label,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop
        )
        loaded.complete ->
            Text("사진 파일을 불러올 수 없습니다.", color = Color(0xFFE5C04A), fontSize = 12.sp)
        else ->
            Text("사진을 불러오는 중...", color = Ash, fontSize = 12.sp)
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
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF4A3A28), Color(0xFF2E241A))))
                    .border(1.dp, Rim, RoundedCornerShape(10.dp))
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

private data class CaptureImageLoad(
    val image: ImageBitmap? = null,
    val complete: Boolean = false
)

@Composable
private fun CapturedPhotoCard(
    artifact: CaptureArtifact,
    isFakeCamera: Boolean,
    compact: Boolean = false
) {
    val context = LocalContext.current
    val loaded by produceState(
        initialValue = CaptureImageLoad(),
        key1 = artifact.imageUri
    ) {
        val image = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(artifact.imageUri))?.use { input ->
                    BitmapFactory.decodeStream(input)?.asImageBitmap()
                }
            }.getOrNull()
        }
        value = CaptureImageLoad(image = image, complete = true)
    }

    InfoCard(title = "최근 촬영 사진") {
        when {
            loaded.image != null -> Image(
                bitmap = checkNotNull(loaded.image),
                contentDescription = "안경으로 최근 촬영한 조리 상태",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 160.dp else 240.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
            loaded.complete -> Text("촬영 파일을 화면에서 열 수 없습니다.", color = Color(0xFFE5C04A), fontSize = 12.sp)
            else -> Text("촬영 사진을 불러오는 중...", color = Ash, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            if (isFakeCamera) "Fake 카메라 이미지" else "실제 Meta 안경 촬영",
            color = if (isFakeCamera) Color(0xFFE5C04A) else Herb,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "${artifact.width ?: "-"}×${artifact.height ?: "-"} · ${formatFileSize(artifact.byteSize)} · " +
                SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(artifact.capturedAtEpochMs)),
            color = Ash,
            fontSize = 11.sp
        )
        Text(
            "첫 프레임 ${artifact.streamStartupLatencyMs ?: "-"}ms · 전체 촬영 ${artifact.totalCaptureLatencyMs}ms",
            color = Ash,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun StatusBanner(title: String, detail: String, tone: BannerTone) {
    val background = when (tone) {
        BannerTone.Progress -> Pan
        BannerTone.Success -> Herb.copy(alpha = 0.14f)
        BannerTone.Caution -> Color(0x33E5C04A)
        BannerTone.Neutral -> PanDark
    }
    val border = when (tone) {
        BannerTone.Progress -> Flame.copy(alpha = 0.35f)
        BannerTone.Success -> Herb
        BannerTone.Caution -> Color(0xFFE5C04A)
        BannerTone.Neutral -> Rim
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .padding(16.dp)
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
        modifier = Modifier.clickable(onClick = onClick),
        color = Color.Transparent,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, Rim)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
private fun WakeWordStatusText(status: WakeWordStatus) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "음성 호출: ${status.message}",
        color = if (status.error) Color(0xFFE5C04A) else if (status.listening) Herb else Ash,
        fontSize = 12.sp,
        lineHeight = 18.sp
    )
    status.downloadPercent?.let { percent ->
        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth()
        )
    }
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

private enum class BannerTone {
    Progress,
    Success,
    Caution,
    Neutral
}

private class SpeechController(
    private val textToSpeech: TextToSpeech?,
    private val speechRecognizer: SpeechRecognizer?,
    private val onTranscript: (String) -> Unit,
    private val onListeningChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val onCommandRecognitionUseChanged: (Boolean) -> Unit
) {
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val startCallbacks = mutableMapOf<String, () -> Unit>()
    private val completionCallbacks = mutableMapOf<String, () -> Unit>()
    private var utteranceSequence = 0L
    private var activeUtteranceId: String? = null
    private var recognitionActive = false
    private var reportedRecognitionActive = false

    init {
        textToSpeech?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId == null) return
                    mainHandler.post {
                        startCallbacks.remove(utteranceId)?.invoke()
                    }
                }

                override fun onDone(utteranceId: String?) {
                    completeUtterance(utteranceId, runCompletion = true)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    completeUtterance(utteranceId, runCompletion = true)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    completeUtterance(utteranceId, runCompletion = true)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    completeUtterance(utteranceId, runCompletion = false)
                }
            }
        )
    }

    fun speak(
        message: String,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        val tts = textToSpeech
        if (tts == null) {
            onStart?.invoke()
            onDone?.invoke()
            return
        }
        utteranceSequence += 1
        val utteranceId = "ttara-cook-$utteranceSequence"
        activeUtteranceId = utteranceId
        if (onStart != null) startCallbacks[utteranceId] = onStart
        if (onDone != null) completionCallbacks[utteranceId] = onDone
        // Keep the wake-word detector running during TTS so the user can interrupt
        // an announcement by saying "따라쿡". QUEUE_FLUSH below replaces that
        // announcement with the short acknowledgement when the wake word fires.
        val result = tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            startCallbacks.remove(utteranceId)?.invoke()
            completeUtterance(utteranceId, runCompletion = true)
        }
    }

    fun startListening() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            onError("이 기기에서는 음성 인식을 사용할 수 없습니다.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_000L)
        }
        recognitionActive = true
        reportRecognitionUse()
        onListeningChanged(true)
        runCatching { recognizer.startListening(intent) }
            .onFailure { error ->
                onListeningChanged(false)
                recognitionActive = false
                reportRecognitionUse()
                onError("음성 인식을 시작하지 못했습니다: ${error.message ?: error.javaClass.simpleName}")
            }
    }

    fun release() {
        startCallbacks.clear()
        completionCallbacks.clear()
        activeUtteranceId = null
        recognitionActive = false
        runCatching { speechRecognizer?.cancel() }
        speechRecognizer?.destroy()
        textToSpeech?.shutdown()
    }

    private fun completeUtterance(utteranceId: String?, runCompletion: Boolean) {
        if (utteranceId == null) return
        mainHandler.post {
            startCallbacks.remove(utteranceId)
            val callback = completionCallbacks.remove(utteranceId)
            if (activeUtteranceId != utteranceId) return@post
            activeUtteranceId = null
            if (runCompletion && callback != null) {
                callback()
            }
        }
    }

    fun recognitionFinished() {
        recognitionActive = false
        reportRecognitionUse()
    }

    private fun reportRecognitionUse() {
        if (reportedRecognitionActive == recognitionActive) return
        reportedRecognitionActive = recognitionActive
        onCommandRecognitionUseChanged(recognitionActive)
    }
}

@Composable
private fun rememberSpeechController(
    context: Context,
    onTranscript: (String) -> Unit,
    onListeningChanged: (Boolean) -> Unit,
    onError: (String) -> Unit,
    onRecognitionFinished: () -> Unit,
    onCommandRecognitionUseChanged: (Boolean) -> Unit
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
                        onRecognitionFinished()
                        onError("음성 인식에 실패했습니다(코드 $error). 화면 버튼을 사용하거나 다시 시도하세요.")
                    }
                    override fun onResults(results: Bundle?) {
                        onListeningChanged(false)
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!text.isNullOrBlank()) {
                            onTranscript(text)
                        } else {
                            onError("음성을 인식하지 못했습니다. 다시 말해주세요.")
                        }
                        onRecognitionFinished()
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
        SpeechController(
            textToSpeech = tts,
            speechRecognizer = recognizer,
            onTranscript = onTranscript,
            onListeningChanged = onListeningChanged,
            onError = onError,
            onCommandRecognitionUseChanged = onCommandRecognitionUseChanged
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}분 ${seconds}초" else "${seconds}초"
}

private fun formatFileSize(byteSize: Long): String = when {
    byteSize >= 1_048_576L -> String.format(Locale.KOREA, "%.2fMB", byteSize / 1_048_576.0)
    byteSize >= 1_024L -> String.format(Locale.KOREA, "%.0fKB", byteSize / 1_024.0)
    else -> "${byteSize}B"
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun PreviewApp() {
    MyApplicationTheme {
        TtaraCookApp()
    }
}
