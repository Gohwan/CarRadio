package com.example.carradio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.carradio.ui.theme.RadioActionButton

@Composable
internal fun DrivingStatus(state: DrivingUiState, onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.description, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        if (state.compact) Text(
            if (!state.canTalk) "말하기·설정·글 작성은 상태 확인 후 사용할 수 있습니다."
            else "설정·글 작성은 정차 후 사용할 수 있습니다.",
            fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!state.known || state.unsupportedRestrictions) RadioActionButton(onClick = onRefresh,
            modifier = Modifier.heightIn(min = 56.dp)) { Text("상태 다시 확인", fontSize = 19.sp) }
    }
}

@Composable
internal fun CompactRadioPanel(
    driving: DrivingUiState,
    channel: String,
    rangeKm: Int,
    relay: LocalRelayState,
    walkie: WalkieUiState,
    tapToTalk: Boolean,
    onLeave: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMute: () -> Unit,
    onPreset: (SignalPreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("$channel · 반경 ${rangeKm}km", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        WalkieTalkiePanel(state = walkie, onJoin = {}, onLeave = onLeave, onMicrophone = {},
            onStart = onStart, onStop = onStop, tapToTalk = tapToTalk, onMuteSpeaker = onMute,
            compact = true, canStart = driving.canTalk, canJoin = false,
            speakerLabel = driving.speakerLabel(walkie.speaker))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SignalPreset.entries.forEach { preset ->
                RadioActionButton(onClick = { onPreset(preset) },
                    enabled = relay.connected && !relay.busy && driving.canSendPreset(preset),
                    modifier = Modifier.weight(1f).heightIn(min = 76.dp)) {
                    Text(preset.title, fontSize = 21.sp)
                }
            }
        }
        Text(if (driving.noTextMessages) "문자 신호는 정차 후 사용할 수 있습니다."
            else "미리 정한 신호만 확인 후 보냅니다. 받은 글은 정차 후 확인할 수 있습니다.",
            fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun DrivingTestDialog(mode: DrivingTestMode, onSelect: (DrivingTestMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("주행 화면 테스트", fontSize = 26.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("화면 제한을 추가하는 디버그 테스트입니다. 실제 플랫폼 제한은 유지됩니다. 앱을 새로 시작하면 자동으로 돌아갑니다.", fontSize = 17.sp)
                DrivingTestMode.entries.forEach { item ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { onSelect(item) },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == item, onClick = null)
                        Text(item.label, fontSize = 19.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기", fontSize = 19.sp) } })
}

@Composable
internal fun PresetSignalDialog(preset: SignalPreset, relay: LocalRelayState, onSend: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!relay.busy) onDismiss() },
        title = { Text(preset.title, fontSize = 26.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(preset.text, fontSize = 22.sp)
                Text("현재 채널과 반경에 이 신호를 보냅니다.", fontSize = 18.sp)
                if (relay.status.contains("전송 실패")) Text("전송하지 못했습니다. 연결을 확인한 뒤 다시 보내세요.",
                    fontSize = 18.sp, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            RadioActionButton(onClick = onSend, enabled = relay.connected && !relay.busy,
                modifier = Modifier.heightIn(min = 64.dp)) {
                Text(if (relay.busy) "보내는 중" else "신호 보내기", fontSize = 20.sp)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !relay.busy,
            modifier = Modifier.heightIn(min = 64.dp)) { Text("취소", fontSize = 20.sp) } })
}
