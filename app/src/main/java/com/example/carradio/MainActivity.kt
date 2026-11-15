package com.example.carradio

import android.content.Context.MODE_PRIVATE
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.core.content.edit
import java.util.UUID
import com.example.carradio.ui.theme.CarRadioTheme
import com.example.carradio.ui.theme.RadioActionButton
import com.example.carradio.ui.theme.radioSwitchColors

class MainActivity : ComponentActivity() {
    private var locationState by mutableStateOf(LocationUiState())
    private lateinit var locationController: PleosLocationController
    private lateinit var relayController: LocalRelayController
    private var relayState by mutableStateOf(LocalRelayState())
    private var locationRequested = false
    private lateinit var speechController: PleosSpeechController
    private var speechState by mutableStateOf(SpeechUiState())
    private lateinit var recognitionController: PleosRecognitionController
    private var recognitionState by mutableStateOf(RecognitionUiState())
    private var recognitionRequested = false
    private var foreground = false
    private lateinit var voiceController: VoiceMessageController
    private var voiceState by mutableStateOf(VoiceUiState())
    private lateinit var walkieController: WalkieTalkieController
    private var walkieState by mutableStateOf(WalkieUiState())
    private var walkiePermissionRequested = false
    private lateinit var drivingController: CarUxController
    private var platformDriving by mutableStateOf(DrivingUiState())
    private var drivingTest by mutableStateOf(DrivingTestMode.AUTOMATIC)
    private val driving: DrivingUiState get() = platformDriving.withTestMode(
        if (BuildConfig.DEBUG) drivingTest else DrivingTestMode.AUTOMATIC)

    private fun applyDrivingRestrictions() {
        if (!driving.canConfigure) {
            recognitionRequested = false
            walkiePermissionRequested = false
            recognitionController.cancel()
            voiceController.cancel()
        }
        if (!driving.canTalk) walkieController.stopTalking()
        updateAudioInterlock()
    }

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (walkiePermissionRequested) {
            walkiePermissionRequested = false
            if (foreground) walkieController.refreshMicrophonePermission()
        } else if (foreground && driving.canConfigure && voiceState.visible) {
            if (granted) voiceController.begin() else voiceController.permissionDenied()
        }
    }

    private fun recordVoice() {
        if (!foreground || !driving.canConfigure) return
        walkieController.leave()
        recognitionRequested = false
        recognitionController.cancel()
        if (voiceController.hasPermission()) voiceController.begin()
        else microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    private fun updateAudioInterlock() {
        speechController.setInputActive(!driving.canConfigure || recognitionState.active || voiceState.usingAudio || walkieState.usingAudio, resume = foreground)
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && locationRequested && foreground && driving.canConfigure) {
            locationController.start()
        } else if (granted) {
            locationRequested = false
            locationController.disconnect()
        } else {
            locationRequested = false
            locationController.permissionDenied()
        }
    }

    private val speechPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) setSpeechEnabled(true)
        else {
            getSharedPreferences("radio_settings", MODE_PRIVATE).edit { putBoolean("tts_enabled", false) }
            speechController.permissionDenied()
        }
    }

    private fun setSpeechEnabled(enabled: Boolean) {
        if (!foreground || !driving.canConfigure) return
        val preferences = getSharedPreferences("radio_settings", MODE_PRIVATE)
        if (!enabled) {
            preferences.edit { putBoolean("tts_enabled", false) }
            speechController.disable()
        } else if (speechController.hasPermission()) {
            preferences.edit { putBoolean("tts_enabled", true) }
            speechController.start()
        } else {
            speechController.permissionPending()
            speechPermission.launch(PleosSpeechController.PERMISSION)
        }
    }

    private val recognitionPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (recognitionRequested && foreground && driving.canConfigure) {
            if (granted) recognitionController.begin() else recognitionController.permissionDenied()
        }
    }

    private fun startRecognition() {
        if (!foreground || !driving.canConfigure) return
        walkieController.leave()
        voiceController.cancel()
        recognitionRequested = true
        if (recognitionController.hasPermission()) {
            recognitionController.begin()
        } else {
            recognitionController.permissionPending()
            recognitionPermission.launch(PleosRecognitionController.PERMISSION)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationRequested = savedInstanceState?.getBoolean("location_requested") ?: false
        speechController = PleosSpeechController(this) { speechState = it }
        recognitionController = PleosRecognitionController(this) {
            recognitionState = it
            updateAudioInterlock()
            if (!it.active) recognitionRequested = false
        }
        voiceController = VoiceMessageController(this, lifecycleScope) {
            voiceState = it
            updateAudioInterlock()
        }
        relayController = LocalRelayController(this, lifecycleScope) {
            relayState = it
            if (::walkieController.isInitialized) walkieController.updateRelay(it.connected, it.walkieSupported)
            speechController.observeInbox(it)
        }
        walkieController = WalkieTalkieController(this, lifecycleScope, relayController::walkieRequest) {
            walkieState = it
            updateAudioInterlock()
        }
        locationController = PleosLocationController(this) {
            locationState = it
            relayController.updateFix(it.fix)
        }
        drivingController = CarUxController(this) {
            platformDriving = it
            applyDrivingRestrictions()
        }
        enableEdgeToEdge()
        setContent {
            CarRadioTheme {
                CarRadioScreen(
                    locationState = locationState,
                    relayState = relayState,
                    relayController = relayController,
                    speechState = speechState,
                    recognitionState = recognitionState,
                    voiceState = voiceState,
                    walkieState = walkieState,
                    drivingState = driving,
                    currentDrivingState = { if (foreground) driving else DrivingUiState() },
                    canRunDrivingTest = BuildConfig.DEBUG && platformDriving.canConfigure,
                    drivingTest = drivingTest,
                    onDrivingTest = {
                        if (BuildConfig.DEBUG && platformDriving.canConfigure) {
                            drivingTest = it
                            applyDrivingRestrictions()
                        }
                    },
                    onRefreshDriving = { if (foreground) drivingController.start() },
                    onWalkieJoin = {
                        if (foreground && driving.canConfigure) {
                            recognitionRequested = false
                            recognitionController.cancel()
                            voiceController.cancel()
                            walkieController.join()
                        }
                    },
                    onWalkieLeave = { walkieController.leave() },
                    onWalkieStart = { if (foreground && driving.canTalk) walkieController.startTalking() },
                    onWalkieStop = { walkieController.stopTalking() },
                    onWalkieMuteSpeaker = { walkieController.muteSpeaker() },
                    onWalkieUnmuteAll = { walkieController.unmuteAll() },
                    onWalkieMicrophone = {
                        if (foreground && driving.canConfigure) {
                            walkiePermissionRequested = true
                            microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onWalkieTestTone = { if (foreground && driving.canConfigure) walkieController.sendTestTone() },
                    onOpenVoice = { channel, range ->
                        if (foreground && driving.canConfigure) {
                            walkieController.leave()
                            recognitionRequested = false
                            recognitionController.cancel()
                            voiceController.open(channel, range)
                        }
                    },
                    onRecordVoice = ::recordVoice,
                    onFinishVoice = { if (foreground && driving.canConfigure) voiceController.finish() },
                    onCancelVoice = { voiceController.cancel() },
                    onPreviewVoice = {
                        if (foreground && driving.canConfigure) voiceState.draft?.let { voiceController.play("draft", it.file) }
                    },
                    onPlayVoice = { message ->
                        if (foreground && driving.canConfigure) {
                            walkieController.leave()
                            recognitionRequested = false
                            recognitionController.cancel()
                            voiceController.play(message.id, message.voiceFile)
                        }
                    },
                    onStopVoicePlayback = { voiceController.stopPlayback() },
                    onSendVoice = {
                        if (foreground && driving.canConfigure) voiceState.draft?.let { draft ->
                            relayController.sendVoice(draft) { voiceController.cancel() }
                        }
                    },
                    onStartRecognition = ::startRecognition,
                    onFinishRecognition = { if (foreground && driving.canConfigure) recognitionController.finish() },
                    onEditRecognitionText = { if (foreground && driving.canConfigure) recognitionController.editText(it) },
                    onCancelRecognition = {
                        recognitionRequested = false
                        recognitionController.cancel()
                    },
                    onConfirmRecognition = { if (foreground && driving.canConfigure) recognitionController.confirmText() else null },
                    onSpeechEnabledChange = ::setSpeechEnabled,
                    onSpeechTest = {
                        if (foreground && driving.canConfigure) {
                            if (speechState.ready) speechController.test() else setSpeechEnabled(true)
                        }
                    },
                    onLocationAction = {
                        if (foreground && driving.canConfigure) {
                            if (locationState.active) {
                                locationRequested = false
                                locationController.disconnect()
                            } else {
                                locationRequested = true
                                if (locationController.hasPermission()) {
                                    locationController.start()
                                } else {
                                    locationPermission.launch(PleosLocationController.PERMISSION)
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        drivingController.start()
        if (getSharedPreferences("radio_settings", MODE_PRIVATE).getBoolean("tts_enabled", false)) {
            if (speechController.hasPermission()) speechController.start()
            else speechController.permissionDenied()
        }
        if (locationRequested) {
            if (locationController.hasPermission()) locationController.start()
            else locationController.permissionDenied()
        }
    }

    override fun onStop() {
        foreground = false
        drivingTest = DrivingTestMode.AUTOMATIC
        drivingController.stop()
        recognitionRequested = false
        walkiePermissionRequested = false
        walkieController.leave("화면을 벗어나 워키토키를 종료했습니다.")
        recognitionController.cancel()
        voiceController.cancel()
        speechController.pause()
        relayController.disconnect()
        locationController.pause()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("location_requested", locationRequested)
        super.onSaveInstanceState(outState)
    }
}
@Composable
private fun CarRadioScreen(
    locationState: LocationUiState = LocationUiState(),
    onLocationAction: () -> Unit = {},
    relayState: LocalRelayState = LocalRelayState(),
    relayController: LocalRelayController? = null,
    speechState: SpeechUiState = SpeechUiState(),
    onSpeechEnabledChange: (Boolean) -> Unit = {},
    onSpeechTest: () -> Unit = {},
    recognitionState: RecognitionUiState = RecognitionUiState(),
    onStartRecognition: () -> Unit = {},
    onFinishRecognition: () -> Unit = {},
    onEditRecognitionText: (String) -> Unit = {},
    onCancelRecognition: () -> Unit = {},
    onConfirmRecognition: () -> String? = { null },
    voiceState: VoiceUiState = VoiceUiState(),
    onOpenVoice: (String, Int) -> Unit = { _, _ -> },
    onRecordVoice: () -> Unit = {},
    onFinishVoice: () -> Unit = {},
    onCancelVoice: () -> Unit = {},
    onPreviewVoice: () -> Unit = {},
    onPlayVoice: (IncomingMessage) -> Unit = {},
    onStopVoicePlayback: () -> Unit = {},
    onSendVoice: () -> Unit = {},
    walkieState: WalkieUiState = WalkieUiState(),
    onWalkieJoin: () -> Unit = {},
    onWalkieLeave: () -> Unit = {},
    onWalkieStart: () -> Unit = {},
    onWalkieStop: () -> Unit = {},
    onWalkieMicrophone: () -> Unit = {},
    onWalkieTestTone: () -> Unit = {},
    onWalkieMuteSpeaker: () -> Unit = {},
    onWalkieUnmuteAll: () -> Unit = {},
    drivingState: DrivingUiState = DrivingUiState(),
    currentDrivingState: () -> DrivingUiState = { drivingState },
    canRunDrivingTest: Boolean = false,
    drivingTest: DrivingTestMode = DrivingTestMode.AUTOMATIC,
    onDrivingTest: (DrivingTestMode) -> Unit = {},
    onRefreshDriving: () -> Unit = {},
) {
    val preferences = LocalContext.current.getSharedPreferences("radio_settings", MODE_PRIVATE)
    var channel by rememberSaveable {
        mutableStateOf(preferences.getString("channel", "일반 대화") ?: "일반 대화")
    }
    var range by rememberSaveable { mutableIntStateOf(preferences.getInt("range_km", 1)) }
    var receiving by rememberSaveable {
        mutableStateOf(preferences.getBoolean("receiving", true))
    }
    var plate4 by rememberSaveable {
        mutableStateOf(preferences.getString("vehicle_plate4", "") ?: "")
    }
    var savedDraft by rememberSaveable {
        mutableStateOf(preferences.getString("draft", "") ?: "")
    }
    var savedDraftId by rememberSaveable {
        val value = preferences.getString("draft_id", null) ?: UUID.randomUUID().toString()
        if (savedDraft.isNotBlank()) preferences.edit { putString("draft_id", value) }
        mutableStateOf(value)
    }
    var savedDraftChannel by rememberSaveable {
        mutableStateOf(preferences.getString("draft_channel", "") ?: "")
    }
    var savedDraftRange by rememberSaveable {
        mutableIntStateOf(preferences.getInt("draft_range_km", 1))
    }
    var spokenDraftChannel by rememberSaveable { mutableStateOf(channel) }
    var spokenDraftRange by rememberSaveable { mutableIntStateOf(range) }
    var editorKind by rememberSaveable { mutableStateOf<String?>(null) }
    var reason by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var replySender by rememberSaveable { mutableStateOf<String?>(null) }
    var replyOriginal by rememberSaveable { mutableStateOf("") }
    var replyText by rememberSaveable { mutableStateOf("") }
    var replyChannel by rememberSaveable { mutableStateOf(channel) }
    var replyRange by rememberSaveable { mutableIntStateOf(range) }
    var developmentSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var tapToTalk by rememberSaveable {
        mutableStateOf(preferences.getBoolean("walkie_tap_to_talk", true))
    }
    var walkieConsentOpen by rememberSaveable { mutableStateOf(false) }
    var drivingTestOpen by remember { mutableStateOf(false) }
    var editingPaused by rememberSaveable { mutableStateOf(false) }
    var quickPreset by remember { mutableStateOf<SignalPreset?>(null) }
    var quickPresetId by remember { mutableStateOf("") }

    LaunchedEffect(drivingState, canRunDrivingTest) {
        if (!drivingState.canConfigure) {
            settingsOpen = false
            developmentSettingsOpen = false
            walkieConsentOpen = false
            if (editorKind != null || replySender != null) editingPaused = true
        }
        if (!canRunDrivingTest) drivingTestOpen = false
        if (!drivingState.compact || quickPreset?.let { !drivingState.canSendPreset(it) } == true) quickPreset = null
    }

    LaunchedEffect(channel, range, receiving, plate4, relayController) {
        relayController?.updateOptions(RadioOptions(channel, range, receiving, plate4))
    }

    val settings: @Composable (Modifier) -> Unit = { modifier ->
        RadioChannelControls(
            channel, range, receiving,
            onChannelChange = {
                if (currentDrivingState().canConfigure) {
                    if (it != channel) onWalkieLeave()
                    channel = it
                    preferences.edit { putString("channel", it) }
                }
            },
            onRangeChange = {
                if (currentDrivingState().canConfigure) {
                    if (it != range) onWalkieLeave()
                    range = it
                    preferences.edit { putInt("range_km", it) }
                }
            },
            onReceivingChange = {
                if (currentDrivingState().canConfigure) {
                    if (it != receiving) onWalkieLeave()
                    receiving = it
                    preferences.edit { putBoolean("receiving", it) }
                }
            },
            modifier = modifier,
        )
    }
    val conversation: @Composable (Modifier) -> Unit = { modifier ->
        ConversationPanel(
            channel, range, receiving, savedDraft, savedDraftChannel, savedDraftRange, relayState,
            voiceState = voiceState,
            onPlayVoice = onPlayVoice,
            onStopVoicePlayback = onStopVoicePlayback,
            walkieState = walkieState.copy(available = walkieState.available && receiving),
            onWalkieJoin = { if (receiving && currentDrivingState().canConfigure) walkieConsentOpen = true },
            onWalkieLeave = onWalkieLeave,
            onWalkieMicrophone = onWalkieMicrophone,
            onWalkieStart = onWalkieStart,
            onWalkieStop = onWalkieStop,
            tapToTalk = tapToTalk,
            onWalkieMuteSpeaker = onWalkieMuteSpeaker,
            onSendDraft = {
                if (currentDrivingState().canConfigure) {
                    val pendingId = savedDraftId
                    relayController?.sendDraft(pendingId, savedDraft, savedDraftChannel, savedDraftRange) {
                        if (savedDraftId == pendingId) {
                            savedDraft = ""
                            preferences.edit {
                                remove("draft")
                                remove("draft_id")
                            }
                        }
                    }
                }
            },
            onQuickReply = { message, text ->
                if (currentDrivingState().canConfigure) {
                    editingPaused = false
                    editorKind = null
                    replySender = message.sender
                    replyOriginal = message.text
                    replyText = text
                    replyChannel = message.channel
                    replyRange = range
                }
            },
            onWriteRequest = { kind ->
                if (currentDrivingState().canConfigure) {
                    editingPaused = false
                    replySender = null
                    editorKind = kind
                    reason = if (kind == "도움 요청") "차량 고장" else "도로 위 낙하물"
                    note = ""
                }
            },
            hasPausedEditor = editingPaused && (editorKind != null || replySender != null),
            onResumeEditor = { if (currentDrivingState().canConfigure) editingPaused = false },
            modifier = modifier,
        )
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
                .verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("CarRadio", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    if (BuildConfig.LOCAL_DEMO) Text(
                        "로컬 테스트", fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(userConnectionLabel(relayState), fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    RadioActionButton(
                        onClick = { if (currentDrivingState().canConfigure) { onWalkieStop(); settingsOpen = true } },
                        enabled = drivingState.canConfigure,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("설정", fontSize = 18.sp) }
                    if (BuildConfig.DEBUG && canRunDrivingTest) RadioActionButton(
                        onClick = { drivingTestOpen = true }, modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("주행 테스트", fontSize = 18.sp) }
                }
            }
            Spacer(Modifier.fillMaxWidth().height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant))
            DrivingStatus(drivingState, onRefreshDriving)
            if (drivingState.compact) {
                CompactRadioPanel(drivingState, channel, range, relayState, walkieState, tapToTalk,
                    onLeave = onWalkieLeave, onStart = onWalkieStart, onStop = onWalkieStop,
                    onMute = onWalkieMuteSpeaker,
                    onPreset = { preset ->
                        if (currentDrivingState().canSendPreset(preset)) {
                            quickPresetId = UUID.randomUUID().toString()
                            quickPreset = preset
                        }
                    })
            } else BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 720.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        settings(Modifier.width(264.dp))
                        conversation(Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
                        settings(Modifier.fillMaxWidth())
                        conversation(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    if (settingsOpen && drivingState.canConfigure) {
        RadioPreferencesDialog(
            plate4 = plate4, profileEditable = !relayState.active,
            onPlateChange = {
                if (currentDrivingState().canConfigure) {
                    plate4 = VehicleIdentity.editPlate4(it)
                    preferences.edit { putString("vehicle_plate4", plate4) }
                }
            },
            locationState = locationState, onLocationAction = onLocationAction,
            tapToTalk = tapToTalk,
            onTapToTalkChange = {
                if (currentDrivingState().canConfigure) {
                    onWalkieStop()
                    tapToTalk = it
                    preferences.edit { putBoolean("walkie_tap_to_talk", it) }
                }
            },
            walkieState = walkieState, onUnmuteAll = onWalkieUnmuteAll,
            speechState = speechState, onSpeechEnabledChange = onSpeechEnabledChange,
            onSpeechTest = onSpeechTest, voiceState = voiceState,
            canSendVoice = relayState.connected && !relayState.busy && BuildConfig.LOCAL_DEMO,
            onOpenVoice = { settingsOpen = false; onOpenVoice(channel, range) },
            onSpeakDraft = {
                settingsOpen = false
                spokenDraftChannel = channel
                spokenDraftRange = range
                onStartRecognition()
            },
            onDevelopment = { if (currentDrivingState().canConfigure) { settingsOpen = false; developmentSettingsOpen = true } },
            onDismiss = { settingsOpen = false },
        )
    }

    if (BuildConfig.LOCAL_DEMO && developmentSettingsOpen && drivingState.canConfigure) {
        AlertDialog(
            onDismissRequest = { developmentSettingsOpen = false },
            title = { Text("개발 테스트", fontSize = 26.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("이 PC의 로컬 중계 서버로 연결합니다. 실제 주변 차량을 검색하거나 차량을 인증하는 기능은 아직 연결되지 않았습니다.")
                    Text(relayState.status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LocalTestPanel(
                        state = relayState,
                        canConnect = locationState.fix != null && VehicleIdentity.validPlate4(plate4),
                        onToggle = {
                            if (currentDrivingState().canConfigure) {
                                if (relayState.active) relayController?.disconnect() else relayController?.connect()
                            }
                        },
                        onAddTestVehicles = { if (currentDrivingState().canConfigure) relayController?.addTestVehicles() },
                    )
                    RadioActionButton(
                        onClick = { developmentSettingsOpen = false; onWalkieTestTone() },
                        enabled = walkieState.joined && !walkieState.transmitting &&
                            !walkieState.requesting && walkieState.speaker == null && walkieState.cooldownSeconds == 0,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text("워키토키 1초 테스트음 보내기", fontSize = 18.sp) }
                    Text("마이크 없이 상대의 음성 수신을 확인합니다. 양쪽에서 워키토키에 참여한 뒤 사용하세요.")
                }
            },
            confirmButton = {
                TextButton(onClick = { developmentSettingsOpen = false }) {
                    Text("닫기", fontSize = 19.sp)
                }
            },
        )
    }

    if (walkieConsentOpen && drivingState.canConfigure) {
        AlertDialog(
            onDismissRequest = { walkieConsentOpen = false },
            title = { Text("워키토키 참여", fontSize = 26.sp) },
            text = { Text("$channel · 반경 ${range}km 안에서 참여한 기기의 목소리가 바로 재생됩니다. 말하기를 누르면 내 목소리가 전달됩니다. 화면을 벗어나면 참여가 종료됩니다.") },
            confirmButton = {
                RadioActionButton(onClick = { walkieConsentOpen = false; onWalkieJoin() }) { Text("동의하고 참여") }
            },
            dismissButton = { TextButton(onClick = { walkieConsentOpen = false }) { Text("취소") } },
        )
    }

    if (voiceState.visible && drivingState.canConfigure) {
        VoiceMessageDialog(
            state = voiceState, busy = relayState.busy, connected = relayState.connected,
            onRecord = onRecordVoice, onFinish = onFinishVoice, onCancel = onCancelVoice,
            onPreview = onPreviewVoice, onStopPlayback = onStopVoicePlayback, onSend = onSendVoice,
        )
    }

    if (recognitionState.visible && drivingState.canConfigure) {
        SpeechDraftDialog(
            state = recognitionState,
            channel = spokenDraftChannel,
            rangeKm = spokenDraftRange,
            replacesDraft = savedDraft.isNotBlank(),
            onEdit = onEditRecognitionText,
            onFinish = onFinishRecognition,
            onRetry = onStartRecognition,
            onCancel = onCancelRecognition,
            onSave = {
                val text = onConfirmRecognition()
                if (text != null) {
                    savedDraft = "메시지\n$text"
                    savedDraftId = UUID.randomUUID().toString()
                    savedDraftChannel = spokenDraftChannel
                    savedDraftRange = spokenDraftRange
                    preferences.edit {
                        putString("draft", savedDraft)
                        putString("draft_id", savedDraftId)
                        putString("draft_channel", savedDraftChannel)
                        putInt("draft_range_km", savedDraftRange)
                    }
                }
            },
        )
    }

    replySender?.takeIf { drivingState.canConfigure && !editingPaused }?.let { sender ->
        QuickReplyDialog(
            sender = sender,
            originalText = replyOriginal,
            text = replyText,
            channel = replyChannel,
            rangeKm = replyRange,
            replacesDraft = savedDraft.isNotBlank(),
            onEdit = { replyText = it.take(200) },
            onCancel = { replySender = null },
            onSave = {
                if (replyText.isNotBlank() && currentDrivingState().canConfigure) {
                    val original = replyOriginal.replace('\n', ' ').replace('\r', ' ').trim()
                    val excerpt = original.take(80) + if (original.length > 80) "…" else ""
                    savedDraft = "답장 · ${replyText.trim()}\n원문: $excerpt"
                    savedDraftId = UUID.randomUUID().toString()
                    savedDraftChannel = replyChannel
                    savedDraftRange = replyRange
                    preferences.edit {
                        putString("draft", savedDraft)
                        putString("draft_id", savedDraftId)
                        putString("draft_channel", savedDraftChannel)
                        putInt("draft_range_km", savedDraftRange)
                    }
                    replySender = null
                }
            },
        )
    }

    editorKind?.takeIf { drivingState.canConfigure && !editingPaused }?.let { kind ->
        val reasons = if (kind == "도움 요청") {
            listOf("차량 고장", "타이어 문제", "기타 도움 요청")
        } else {
            listOf("도로 위 낙하물", "도로 위 정차 차량", "시야 불량")
        }
        AlertDialog(
            onDismissRequest = { editorKind = null },
            title = { Text(kind, fontSize = 26.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("$channel · 반경 ${range}km", fontSize = 18.sp)
                    reasons.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clickable { reason = item },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = reason == item, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(item, fontSize = 19.sp)
                        }
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(200) },
                        label = { Text("추가 설명 (선택)") },
                        minLines = 2,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "작성 완료 후 보낼 내용을 확인할 수 있습니다.",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                RadioActionButton(
                    onClick = {
                        if (currentDrivingState().canConfigure) {
                            savedDraft = "$kind · $reason" +
                                if (note.isBlank()) "" else "\n${note.trim()}"
                            savedDraftId = UUID.randomUUID().toString()
                            savedDraftChannel = channel
                            savedDraftRange = range
                            preferences.edit {
                                putString("draft", savedDraft)
                                putString("draft_id", savedDraftId)
                                putString("draft_channel", channel)
                                putInt("draft_range_km", range)
                            }
                            editorKind = null
                        }
                    },
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text("작성 완료", fontSize = 19.sp) }
            },
            dismissButton = {
                TextButton(
                    onClick = { editorKind = null },
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text("취소", fontSize = 19.sp) }
            },
        )
    }

    if (BuildConfig.DEBUG && drivingTestOpen && canRunDrivingTest) {
        DrivingTestDialog(drivingTest,
            onSelect = { onDrivingTest(it); drivingTestOpen = false },
            onDismiss = { drivingTestOpen = false })
    }
    quickPreset?.takeIf { drivingState.compact && drivingState.canSendPreset(it) }?.let { preset ->
        PresetSignalDialog(preset, relayState,
            onSend = {
                if (currentDrivingState().compact && currentDrivingState().canSendPreset(preset)) {
                    val requestId = quickPresetId
                    relayController?.sendDraft(requestId, preset.text, channel, range) {
                        if (quickPresetId == requestId) quickPreset = null
                    }
                }
            }, onDismiss = { quickPreset = null })
    }
}

@Composable
private fun RadioChannelControls(
    channel: String,
    range: Int,
    receiving: Boolean,
    onChannelChange: (String) -> Unit,
    onRangeChange: (Int) -> Unit,
    onReceivingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("채널", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        listOf("일반 대화", "도로 위험", "도움 요청").forEach { item ->
            val isSelected = item == channel
            RadioActionButton(
                onClick = { onChannelChange(item) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .semantics { selected = isSelected },
                shape = RoundedCornerShape(8.dp),
                selected = isSelected,
            ) {
                if (isSelected) Text(
                    "✓",
                    modifier = Modifier.padding(end = 8.dp),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(item, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("신호 범위", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(1, 3, 5).forEach { value ->
                RadioActionButton(
                    onClick = { onRangeChange(value) },
                    selected = range == value,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                        .semantics { selected = range == value },
                ) {
                    Text(if (range == value) "✓ ${value}km" else "${value}km", fontSize = 18.sp, maxLines = 1)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("수신 허용", fontSize = 20.sp)
            Switch(checked = receiving, onCheckedChange = onReceivingChange, colors = radioSwitchColors())
        }
    }
}

@Composable
private fun ConversationPanel(
    channel: String,
    rangeKm: Int,
    receiving: Boolean,
    savedDraft: String,
    savedDraftChannel: String,
    savedDraftRange: Int,
    relayState: LocalRelayState,
    onSendDraft: () -> Unit,
    onWriteRequest: (String) -> Unit,
    onQuickReply: (IncomingMessage, String) -> Unit,
    voiceState: VoiceUiState,
    onPlayVoice: (IncomingMessage) -> Unit,
    onStopVoicePlayback: () -> Unit,
    walkieState: WalkieUiState = WalkieUiState(),
    onWalkieJoin: () -> Unit = {},
    onWalkieLeave: () -> Unit = {},
    onWalkieMicrophone: () -> Unit = {},
    onWalkieStart: () -> Unit = {},
    onWalkieStop: () -> Unit = {},
    tapToTalk: Boolean = true,
    onWalkieMuteSpeaker: () -> Unit = {},
    hasPausedEditor: Boolean = false,
    onResumeEditor: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(channel, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("신호 범위 · 반경 ${rangeKm}km", fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (relayState.connected) "같은 채널·반경 안 연결 기기 ${relayState.peers.size}대"
            else if (BuildConfig.LOCAL_DEMO) "설정 → 개발 테스트에서 연결하세요."
            else "통신 연결 후 신호를 주고받을 수 있습니다.",
            fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (relayState.status.contains("전송 실패")) {
            Text("신호를 보내지 못했습니다. 연결 상태를 확인한 뒤 다시 보내세요.",
                fontSize = 18.sp, color = MaterialTheme.colorScheme.error)
        }
        WalkieTalkiePanel(walkieState, onWalkieJoin, onWalkieLeave, onWalkieMicrophone,
            onWalkieStart, onWalkieStop, tapToTalk, onWalkieMuteSpeaker)
        if (hasPausedEditor) RadioActionButton(onClick = onResumeEditor,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            Text("작성 중인 글 이어쓰기", fontSize = 20.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioActionButton(
                onClick = { onWriteRequest("위험 알림") },
                modifier = Modifier.weight(1f).heightIn(min = 76.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text("위험 알리기", fontSize = 20.sp) }
            RadioActionButton(
                onClick = { onWriteRequest("도움 요청") },
                modifier = Modifier.weight(1f).heightIn(min = 76.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text("도움 요청하기", fontSize = 20.sp) }
        }
        if (savedDraft.isNotBlank()) {
            Text("보낼 신호", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(savedDraft, fontSize = 20.sp)
            Text(
                "$savedDraftChannel · 반경 ${savedDraftRange}km",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RadioActionButton(
                onClick = onSendDraft,
                enabled = relayState.connected && !relayState.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text(if (relayState.busy) "처리 중" else "신호 보내기", fontSize = 20.sp) }
        }

        relayState.lastTransmission?.let { transmission ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("최근 전송", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(transmission.replace("PC 서버 접수", "서버 접수"), fontSize = 18.sp)
                Text(
                    "앱 수신 확인은 메시지 처리 응답입니다. 사람이 읽었는지는 확인하지 않습니다.",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text("받은 메시지", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "최근 10건 · 전송 후 약 2분 보관",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (relayState.messages.isEmpty()) {
            Text(
                if (!receiving) "수신 허용이 꺼져 있습니다."
                else if (!relayState.connected) "통신 연결 후 신호를 받을 수 있습니다."
                else "받은 메시지가 없습니다.",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            relayState.messages.take(10).forEach { message ->
                key(message.id) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "${message.sender} · ${message.distanceMeters}m",
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (message.kind == "voice") {
                                Text("음성 메시지 · ${(message.durationMs + 999) / 1000}초", fontSize = 20.sp)
                                RadioActionButton(
                                    onClick = {
                                        if (voiceState.playingId == message.id) onStopVoicePlayback()
                                        else onPlayVoice(message)
                                    },
                                    enabled = message.voiceFile != null && !voiceState.recording,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                                ) { Text(if (voiceState.playingId == message.id) "재생 중지" else "보낸 목소리 듣기", fontSize = 20.sp) }
                            } else {
                                Text(message.text, fontSize = 20.sp)
                            }
                            Text(
                                "이 메시지에 답장 · 같은 채널에 공개",
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                RadioActionButton(
                                    onClick = { onQuickReply(message, "확인했습니다.") },
                                    enabled = relayState.connected && !relayState.busy,
                                    modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                                    shape = RoundedCornerShape(8.dp),
                                ) { Text("확인했습니다", fontSize = 18.sp) }
                                RadioActionButton(
                                    onClick = { onQuickReply(message, "도움 가능합니다.") },
                                    enabled = relayState.connected && !relayState.busy,
                                    modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                                    shape = RoundedCornerShape(8.dp),
                                ) { Text("도움 가능합니다", fontSize = 18.sp) }
                            }
                        }
                    }
                }
            }
        }


    }
}

private fun userConnectionLabel(state: LocalRelayState): String = when {
    state.status.contains("전송 실패") -> "전송 실패"
    state.status.contains("연결 실패") -> "연결 실패"
    state.connected -> "연결됨"
    state.active -> "연결 중"
    else -> "연결 안됨"
}

@Composable
private fun LocalTestPanel(
    state: LocalRelayState,
    canConnect: Boolean,
    onToggle: () -> Unit,
    onAddTestVehicles: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioActionButton(
                onClick = onToggle,
                enabled = state.active || canConnect,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(if (state.active) "연결 해제" else "동의하고 연결", fontSize = 18.sp)
            }
            RadioActionButton(
                onClick = onAddTestVehicles,
                enabled = state.connected && !state.busy,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text("가상 차량 배치", fontSize = 18.sp) }
        }
        Text(
            if (state.connected) "같은 채널·반경 안 기기 ${state.peers.size}대 · 약 2초 간격 갱신"
            else if (canConnect) "입력번호·위치·메시지를 PC 테스트 서버와 수신 기기에 공유합니다. 메시지는 약 2분 보관됩니다."
            else "차량번호 4자리 입력과 위치 연결 후 사용할 수 있습니다.",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.peers.take(4).forEach { peer ->
            Text("${peer.label} · ${peer.distanceMeters}m", fontSize = 18.sp)
        }
        if (state.peers.size > 4) Text("외 ${state.peers.size - 4}대", fontSize = 16.sp)
    }
}

@Preview(name = "CarRadio - day", showBackground = true, widthDp = 1000, heightDp = 680,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "CarRadio - night", showBackground = true, widthDp = 1000, heightDp = 680,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CarRadioPreview() {
    CarRadioTheme { CarRadioScreen(drivingState = DrivingUiState.unrestricted()) }
}

@Preview(name = "Driving - day", showBackground = true, widthDp = 1000, heightDp = 850,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Driving - night", showBackground = true, widthDp = 1000, heightDp = 850,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DrivingPreview() {
    CarRadioTheme {
        CarRadioScreen(drivingState = DrivingUiState.unrestricted().withTestMode(DrivingTestMode.RESTRICTED),
            relayState = LocalRelayState(active = true, connected = true),
            walkieState = WalkieUiState(joined = true, microphoneReady = true, participants = 1, speaker = "입력번호 1234"))
    }
}

@Preview(name = "Signal review - day", showBackground = true, widthDp = 1000, heightDp = 1000,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Signal review - night", showBackground = true, widthDp = 1000, heightDp = 1000,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SignalReviewPreview() {
    // Fixed sample data and empty callbacks: no SDK, microphone or network is started.
    CarRadioTheme {
        Surface {
            ConversationPanel(
                channel = "도로 위험", rangeKm = 1, receiving = true,
                savedDraft = "위험 알림 · 도로 위 낙하물\n오른쪽 차로에 낙하물이 있습니다.",
                savedDraftChannel = "도로 위험", savedDraftRange = 1,
                relayState = LocalRelayState(active = true, connected = true),
                onSendDraft = {}, onWriteRequest = {}, onQuickReply = { _, _ -> },
                voiceState = VoiceUiState(), onPlayVoice = {}, onStopVoicePlayback = {},
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
