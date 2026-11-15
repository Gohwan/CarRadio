package com.example.carradio.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Selection is separate from availability: only selected choices use a filled background. */
@Composable
internal fun RadioActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    shape: Shape = RoundedCornerShape(8.dp),
    interactionSource: MutableInteractionSource? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        interactionSource = interactionSource,
        contentPadding = contentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) colors.primary else colors.surface,
            contentColor = if (selected) colors.onPrimary else colors.onSurface,
            disabledContainerColor = colors.surfaceVariant,
            disabledContentColor = colors.onSurface.copy(alpha = 0.38f),
        ),
        border = BorderStroke(
            if (selected && enabled) 2.dp else 1.dp,
            if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.12f),
        ),
        content = content,
    )
}

@Composable
internal fun radioSwitchColors(): SwitchColors {
    val colors = MaterialTheme.colorScheme
    return SwitchDefaults.colors(
        checkedThumbColor = colors.onPrimary,
        checkedTrackColor = colors.primary,
        checkedBorderColor = colors.primary,
        uncheckedThumbColor = colors.surface,
        uncheckedTrackColor = colors.outline,
        uncheckedBorderColor = colors.outline,
        disabledCheckedThumbColor = colors.onSurface.copy(alpha = 0.38f),
        disabledCheckedTrackColor = colors.surfaceVariant,
        disabledCheckedBorderColor = colors.surfaceVariant,
        disabledUncheckedThumbColor = colors.onSurface.copy(alpha = 0.38f),
        disabledUncheckedTrackColor = colors.surfaceVariant,
        disabledUncheckedBorderColor = colors.surfaceVariant,
    )
}

@Preview(name = "Button states - day", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Button states - night", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RadioButtonStatesPreview() {
    CarRadioTheme {
        Column(
            Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RadioActionButton(onClick = {}, selected = true) { Text("✓ 일반 대화") }
            RadioActionButton(onClick = {}) { Text("위험 알림 작성") }
            RadioActionButton(onClick = {}, enabled = false) { Text("PC 테스트로 전송") }
        }
    }
}
