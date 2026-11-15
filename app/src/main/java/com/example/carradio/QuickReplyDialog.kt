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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.carradio.ui.theme.RadioActionButton

@Composable
internal fun QuickReplyDialog(
    sender: String,
    originalText: String,
    text: String,
    channel: String,
    rangeKm: Int,
    replacesDraft: Boolean,
    onEdit: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("답장 작성", fontSize = 26.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("받은 메시지 · $sender", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(originalText, fontSize = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    "어떤 메시지의 답장인지 알 수 있도록 원문 앞부분을 함께 보냅니다.",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("전송 범위: $channel · 반경 ${rangeKm}km", fontSize = 18.sp)
                Text(
                    "이 답장은 같은 채널에서 범위·수신 설정을 만족하는 주변 기기에 공개됩니다.",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = onEdit,
                    label = { Text("답장 내용") },
                    supportingText = { Text("${text.length}/200") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (replacesDraft) "작성 완료를 누르면 기존에 작성한 신호를 교체합니다."
                    else "작성 완료 후 보낼 내용을 확인하고 신호 보내기를 누르세요.",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            RadioActionButton(
                onClick = onSave,
                enabled = text.isNotBlank(),
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
