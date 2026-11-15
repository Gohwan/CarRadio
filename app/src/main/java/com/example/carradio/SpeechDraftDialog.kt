package com.example.carradio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.carradio.ui.theme.RadioActionButton
import java.util.Locale

@Composable
internal fun SpeechDraftDialog(
    state: RecognitionUiState,
    channel: String,
    rangeKm: Int,
    replacesDraft: Boolean,
    onEdit: (String) -> Unit,
    onFinish: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("말해서 글 작성", fontSize = 26.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("$channel · 반경 ${rangeKm}km", fontSize = 18.sp)
                Text(state.status, fontSize = 18.sp)
                if (BuildConfig.DEBUG) {
                    val latest = state.signal.latest?.let { String.format(Locale.US, "%.3g", it) } ?: "미수신"
                    val peak = state.signal.peak?.let { String.format(Locale.US, "%.3g", it) } ?: "미수신"
                    Text(
                        "시작 응답: ${if (state.sdkStarted) "받음" else "대기"} · 입력 응답 ${state.signal.callbacks}회",
                        fontSize = 16.sp,
                    )
                    Text(
                        "입력 RMS: $latest · 최대 $peak · 문자 응답 ${state.textCallbacks}회",
                        fontSize = 16.sp,
                    )
                    state.errorCode?.let { Text("SDK 오류: $it", fontSize = 16.sp) }
                }
                OutlinedTextField(
                    value = state.text,
                    onValueChange = onEdit,
                    enabled = !state.active,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("메시지 내용") },
                    supportingText = { Text("${state.text.length}/200") },
                    minLines = 3,
                    maxLines = 5,
                )
                if (state.canFinish) {
                    RadioActionButton(
                        onClick = onFinish,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text("말하기 끝내기", fontSize = 19.sp) }
                } else if (!state.active) {
                    RadioActionButton(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text("다시 말하기", fontSize = 19.sp) }
                }
                Text(
                    if (replacesDraft) "작성 완료를 누르면 기존에 작성한 신호를 교체합니다."
                    else "내용을 확인한 뒤 작성 완료를 누르세요.",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "작성한 내용은 메인 화면에서 신호 보내기를 눌러 전송합니다.",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            RadioActionButton(
                onClick = onSave,
                enabled = state.canSave,
                modifier = Modifier.heightIn(min = 56.dp),
            ) { Text("작성 완료", fontSize = 19.sp) }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.heightIn(min = 56.dp),
            ) { Text("취소", fontSize = 19.sp) }
        },
    )
}
