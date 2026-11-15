package com.example.carradio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.carradio.ui.theme.RadioActionButton
import com.example.carradio.ui.theme.radioSwitchColors

@Composable
internal fun RadioPreferencesDialog(
    plate4: String,
    profileEditable: Boolean,
    onPlateChange: (String) -> Unit,
    locationState: LocationUiState,
    onLocationAction: () -> Unit,
    tapToTalk: Boolean,
    onTapToTalkChange: (Boolean) -> Unit,
    walkieState: WalkieUiState,
    onUnmuteAll: () -> Unit,
    speechState: SpeechUiState,
    onSpeechEnabledChange: (Boolean) -> Unit,
    onSpeechTest: () -> Unit,
    voiceState: VoiceUiState,
    canSendVoice: Boolean,
    onOpenVoice: () -> Unit,
    onSpeakDraft: () -> Unit,
    onDevelopment: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("설정", fontSize = 26.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("차량과 위치", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = plate4, onValueChange = onPlateChange, enabled = profileEditable,
                    label = { Text("차량번호 뒤 4자리") },
                    supportingText = { Text(if (profileEditable) "직접 입력 · 차량 인증 전"
                        else "입력번호 · 통신 연결 해제 후 변경 가능") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text(locationState.description, fontSize = 17.sp)
                locationState.coordinates?.let { Text(it, fontSize = 17.sp) }
                RadioActionButton(onClick = onLocationAction,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(if (locationState.active) "위치 연결 해제" else "위치 연결", fontSize = 19.sp)
                }
                Text("워키토키", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("탭하여 시작·종료", fontSize = 19.sp)
                    Switch(checked = tapToTalk, onCheckedChange = onTapToTalkChange,
                        colors = radioSwitchColors())
                }
                Text(if (tapToTalk) "말하기를 한 번 누르면 시작하고 다시 누르면 종료합니다."
                    else "말하기 버튼을 누르는 동안만 전송합니다.", fontSize = 17.sp)
                if (walkieState.muteSupported) {
                    Text("음소거 ${walkieState.mutedCount}명 · 참여 종료 시 해제", fontSize = 17.sp)
                    RadioActionButton(onClick = onUnmuteAll,
                        enabled = walkieState.joined && walkieState.mutedCount > 0 && !walkieState.moderationBusy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("음소거 모두 해제", fontSize = 19.sp)
                    }
                }
                Text("메시지 음성 읽기", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("받은 글을 소리로 읽기", fontSize = 19.sp)
                    Switch(checked = speechState.enabled, onCheckedChange = onSpeechEnabledChange,
                        colors = radioSwitchColors())
                }
                RadioActionButton(onClick = onSpeechTest,
                    enabled = speechState.enabled && !speechState.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(if (speechState.ready) "읽기 음성 테스트" else "읽기 음성 다시 연결", fontSize = 19.sp)
                }
                Text(speechState.status, fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("음성 메시지", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                RadioActionButton(onClick = onOpenVoice, enabled = canSendVoice,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Text("녹음 파일로 보내기 · 최대 15초", fontSize = 19.sp)
                }
                RadioActionButton(onClick = onSpeakDraft,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Text("말해서 글로 작성", fontSize = 19.sp)
                }
                Text("녹음·음성 작성 시 워키토키 참여가 종료됩니다.", fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(voiceState.status, fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (BuildConfig.LOCAL_DEMO) RadioActionButton(onClick = onDevelopment,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("개발 테스트", fontSize = 19.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("닫기", fontSize = 19.sp)
            }
        },
    )
}
