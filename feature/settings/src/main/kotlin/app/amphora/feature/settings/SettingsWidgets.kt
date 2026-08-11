package app.amphora.feature.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingSection(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier =
                Modifier
                    .animateContentSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun <T> ChoiceSetting(
    title: String,
    description: String,
    impact: String,
    selected: T,
    defaultValue: T,
    values: List<T>,
    label: (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        ModifiedResetAction(
            modified = selected != defaultValue,
            onReset = onReset,
        )
    }
    Text(
        description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                enabled = enabled,
                label = { Text(label(value)) },
            )
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            impact,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ModifiedResetAction(modified: Boolean, onReset: () -> Unit) {
    TextButton(
        onClick = onReset,
        enabled = modified,
        modifier = Modifier.alpha(if (modified) 1f else 0f),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text("Modified · Reset", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun StatusDot(tone: HealthTone, size: Int = 10) {
    Box(
        Modifier
            .padding(top = 4.dp)
            .size(size.dp)
            .background(tone.color(), CircleShape),
    )
}

internal enum class HealthTone {
    GOOD,
    BAD,
    NEUTRAL,
    LOCAL,
}

@Composable
internal fun HealthTone.color(): Color = when (this) {
    HealthTone.GOOD -> Color(0xFF2E7D5B)
    HealthTone.BAD -> MaterialTheme.colorScheme.error
    HealthTone.NEUTRAL -> MaterialTheme.colorScheme.outline
    HealthTone.LOCAL -> Color(0xFF3976A8)
}
