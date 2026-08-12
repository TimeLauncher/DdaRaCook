package com.example.myapplication

import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
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
        onListeningChanged = sessionViewModel::setListening
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
                    onRecipeClick = sessionViewModel::selectRecipe
                )

                AppScreen.S2_RECIPE_DETAIL -> RecipeDetailScreen(
                    recipe = uiState.selectedRecipe ?: return@Surface,
                    onBack = sessionViewModel::backToHome,
                    onStart = sessionViewModel::openDevicePreparation
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
                    onUseBusyCapture = sessionViewModel::setFakeCaptureBehaviorBusy,
                    onUseSuccessCapture = sessionViewModel::setFakeCaptureBehaviorSuccess,
                    onUseDisconnectCapture = sessionViewModel::setFakeCaptureBehaviorDisconnect,
                    onUseFailureCapture = sessionViewModel::setFakeCaptureBehaviorFailure,
                    onMockVerdict = sessionViewModel::setMockVerdict,
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
                    onVoice = {
                        if (uiState.audioPermissionGranted) speechController.startListening() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )

                AppScreen.S9_SUMMARY -> SummaryScreen(
                    uiState = uiState,
                    onDone = sessionViewModel::backToHome
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    uiState: CookingSessionUiState,
    onRecipeClick: (String) -> Unit
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
    onStart: () -> Unit
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
            PrimaryButton(label = "요리 시작", onClick = onStart)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
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
    onUseBusyCapture: () -> Unit,
    onUseSuccessCapture: () -> Unit,
    onUseDisconnectCapture: () -> Unit,
    onUseFailureCapture: () -> Unit,
    onMockVerdict: (JudgmentVerdict) -> Unit,
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
                JudgmentVerdict.entries.forEach { verdict ->
                    SmallGhostButton(verdict.name, { onMockVerdict(verdict) })
                }
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
                detail = "CANNOT_TELL ${session.cannotTellCount}회 · 팬 쪽을 다시 봐주세요.",
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
            VoiceButton(uiState, onVoice)
        }
    }
}

@Composable
private fun SummaryScreen(
    uiState: CookingSessionUiState,
    onDone: () -> Unit
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
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
        onClick = onClick,
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
    private val context: Context,
    private val onTranscript: (String) -> Unit,
    private val onListeningChanged: (Boolean) -> Unit
) {
    fun speak(message: String) {
        textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "ttara-cook")
    }

    fun startListening() {
        if (speechRecognizer == null) return
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
    onListeningChanged: (Boolean) -> Unit
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
        SpeechController(tts, recognizer, context, onTranscript, onListeningChanged)
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun PreviewApp() {
    MyApplicationTheme {
        TtaraCookApp()
    }
}
