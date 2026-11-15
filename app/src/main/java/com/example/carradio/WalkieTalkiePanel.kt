package com.example.carradio

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.carradio.ui.theme.CarRadioTheme
import com.example.carradio.ui.theme.RadioActionButton

@Composable
internal fun WalkieTalkiePanel(
    state: WalkieUiState,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    onMicrophone: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    tapToTalk: Boolean = true,
    onMuteSpeaker: () -> Unit = {},
    compact: Boolean = false,
    canStart: Boolean = true,
    canJoin: Boolean = true,
    speakerLabel: String? = state.speaker,
) {
    val interactions = remember { MutableInteractionSource() }
    val latestStart by rememberUpdatedState(onStart)
    val latestStop by rememberUpdatedState(onStop)
    val enabled = state.transmitting || state.requesting || (canStart && state.joined &&
        state.microphoneReady && state.speaker == null && state.cooldownSeconds == 0)
    LaunchedEffect(interactions, tapToTalk, enabled) {
        if (!tapToTalk && enabled) {
            try {
                interactions.interactions.collect { event ->
                    when (event) {
                        is PressInteraction.Press -> latestStart()
                        is PressInteraction.Release, is PressInteraction.Cancel -> latestStop()
                    }
                }
            } finally { latestStop() }
        }
    }
    DisposableEffect(Unit) { onDispose { latestStop() } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("워키토키", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (!state.joined) {
            RadioActionButton(onClick = onJoin,
                enabled = canJoin && state.available && state.supported && !state.joining,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text(if (state.joining) "참여 중" else "워키토키 참여", fontSize = 22.sp)
            }
            Text(if (!canJoin) "정차 후 워키토키 참여와 마이크 허용을 준비해 주세요."
                else if (state.available && !state.supported) "PC 중계 서버를 종료하고 다시 실행해 주세요."
                else state.status, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                if (!compact) Text("함께 참여한 기기 ${state.participants}대", fontSize = 18.sp)
                RadioActionButton(onClick = onLeave, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("나가기", fontSize = 18.sp)
                }
            }
            Text(when {
                state.transmitting -> if (state.testTone) "현재 발언자 · 테스트음" else "현재 발언자 · 나"
                state.speaker != null -> if (speakerLabel == null) "상대 음성 수신 중" else "현재 발언자 · $speakerLabel"
                else -> "발언 대기"
            }, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            if (!compact) Text(state.status, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!compact) state.notice?.let { Text(it, fontSize = 17.sp) }
            if (state.speaker != null && state.muteSupported) {
                RadioActionButton(onClick = onMuteSpeaker,
                    enabled = !state.moderationBusy && state.mutedCount < 32 && state.speakerId != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("이 발언자 음소거", fontSize = 19.sp)
                }
            }
            if (!state.microphoneReady && !compact) {
                RadioActionButton(onClick = onMicrophone, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("말하기용 마이크 허용", fontSize = 20.sp)
                }
            }
            if (!state.microphoneReady && compact) Text("마이크 허용은 정차 후 가능합니다.",
                fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RadioActionButton(
                onClick = { if (tapToTalk) { if (state.transmitting || state.requesting) onStop() else onStart() } },
                enabled = enabled,
                selected = state.transmitting,
                interactionSource = interactions,
                modifier = Modifier.fillMaxWidth().heightIn(min = 128.dp),
            ) {
                Text(when {
                    state.transmitting -> "말하는 중 ${state.elapsedSeconds}초 · " + if (tapToTalk) "눌러서 종료" else "손을 떼면 종료"
                    state.requesting -> if (tapToTalk) "연결 중 · 눌러서 취소" else "연결 중 · 누른 채 기다리세요"
                    state.speaker != null -> "상대가 말하는 중"
                    !canStart -> "주행 상태 확인 후 말하기 가능"
                    state.cooldownSeconds > 0 -> "다음 발언까지 ${state.cooldownSeconds}초"
                    tapToTalk -> "말하기 시작"
                    else -> "누른 채 말하기"
                }, fontSize = 22.sp)
            }
            if (!compact) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("마이크 입력", fontSize = 18.sp)
                LinearProgressIndicator(
                    progress = { state.inputLevel.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(when {
                    state.testTone -> "테스트음 표시 · 마이크 측정 아님"
                    !state.transmitting -> "말하기를 시작하면 입력 크기가 표시됩니다."
                    state.quietInput -> "입력이 작습니다 · 마이크 설정을 확인하세요."
                    else -> "목소리 입력 중"
                }, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("한 번에 한 명 · 최대 15초 · 다음 발언 전 2초 대기", fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Preview(name = "Walkie - day", showBackground = true, widthDp = 720,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Walkie - night", showBackground = true, widthDp = 720,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WalkiePreview() {
    CarRadioTheme {
        WalkieTalkiePanel(WalkieUiState(joined = true, microphoneReady = true, participants = 1), {}, {}, {}, {}, {})
    }
}
