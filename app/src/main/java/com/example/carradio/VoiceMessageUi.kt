package com.example.carradio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.carradio.ui.theme.RadioActionButton

@Composable
internal fun VoiceMessageDialog(
    state: VoiceUiState, busy: Boolean, connected: Boolean,
    onRecord: () -> Unit, onFinish: () -> Unit, onCancel: () -> Unit,
    onPreview: () -> Unit, onStopPlayback: () -> Unit, onSend: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("목소리로 보내기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("같은 채널·반경 안의 수신 차량에 녹음한 목소리를 공유합니다. PC 테스트 서버와 수신 앱에 최대 2분 보관됩니다. 녹음은 전송을 누르기 전까지 이 기기에만 있습니다.")
                Text(state.status)
                if (state.recording) Text("${state.elapsedSeconds} / 15초")
                if (state.draft == null) {
                    RadioActionButton(
                        onClick = if (state.recording) onFinish else onRecord,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    ) { Text(if (state.recording) "녹음 종료" else "동의하고 녹음") }
                } else {
                    Text("${state.draft.channel} · 반경 ${state.draft.rangeKm}km · ${(state.draft.durationMs + 999) / 1000}초")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioActionButton(
                            onClick = if (state.playingId != null) onStopPlayback else onPreview,
                            enabled = !busy,
                        ) { Text(if (state.playingId != null) "재생 중지" else "녹음 들어보기") }
                    }
                }
            }
        },
        confirmButton = {
            RadioActionButton(
                onClick = onSend,
                enabled = connected && !busy && state.draft != null && state.playingId == null,
            ) { Text(if (busy) "전송 중" else "목소리 전송") }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("삭제하고 닫기") } },
    )
}

