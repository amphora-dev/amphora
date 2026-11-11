package app.amphora.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How many cells of at least [minCell] fit in [width] with [spacing] between them. */
fun columnsFor(width: Dp, minCell: Dp, spacing: Dp): Int =
    ((width + spacing) / (minCell + spacing)).toInt().coerceAtLeast(1)

/**
 * Lays [items] out in rows of [columns] equal cells, one lazy item per row,
 * so a grid can sit inside a screen's list next to panels and headers.
 */
fun <T> LazyListScope.gridItems(
    items: List<T>,
    columns: Int,
    key: (T) -> Any,
    contentType: Any? = null,
    spacing: Dp = 12.dp,
    cell: @Composable (T) -> Unit,
) {
    val rows = items.chunked(columns.coerceAtLeast(1))
    items(count = rows.size, key = { index ->
        "row:" + key(rows[index].first())
    }, contentType = { contentType }) { index ->
        val row = rows[index]
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
            row.forEach { item -> Box(Modifier.weight(1f)) { cell(item) } }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
