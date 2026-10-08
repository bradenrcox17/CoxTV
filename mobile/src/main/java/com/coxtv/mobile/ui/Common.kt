package com.coxtv.mobile.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.coxtv.data.Categories
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Current time, ticking on period boundaries. */
@Composable
fun rememberNow(periodMs: Long = 30_000): State<Long> = produceState(System.currentTimeMillis(), periodMs) {
    while (true) {
        value = System.currentTimeMillis()
        delay(periodMs - value % periodMs + 20)
    }
}

class TimeFormatter(context: Context) {
    private val time: DateFormat = android.text.format.DateFormat.getTimeFormat(context)
    private val day = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    fun time(ms: Long): String = time.format(Date(ms))
    fun day(ms: Long): String = day.format(Date(ms))
    fun range(start: Long, end: Long) = "${time(start)} – ${time(end)}"
}

@Composable
fun rememberTimeFormatter(): TimeFormatter {
    val context = LocalContext.current
    return remember { TimeFormatter(context) }
}

fun minutesLeft(endMs: Long, now: Long): String {
    val mins = ((endMs - now) / 60_000).coerceAtLeast(0)
    return if (mins >= 60) "${mins / 60}h ${mins % 60}m left" else "$mins min left"
}

/** Horizontally scrolling chips for the categories the user turned on, in their order. */
@Composable
fun CategoryChips(
    keys: List<String>,
    selected: String,
    counts: Map<String, Int>,
    onSelect: (String) -> Unit,
) {
    val state = rememberLazyListState()
    LaunchedEffect(selected, keys) {
        val i = keys.indexOf(selected)
        if (i >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == i }) state.scrollToItem(i)
    }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(keys, key = { it }) { key ->
            val count = counts[key]
            FilterChip(
                selected = key == selected,
                onClick = { onSelect(key) },
                label = { Text(if (count != null) "${Categories.label(key)}  $count" else Categories.label(key)) },
            )
        }
    }
}

/** The CoxTV logo: the C monogram and the wordmark (white "Cox", orange "TV"), as on the
 * launcher icon, the TVs and the website. */
@Composable
fun CoxWordmark(style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        val size = with(androidx.compose.ui.platform.LocalDensity.current) { (style.fontSize * 1.25f).toDp() }
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.coxtv.core.R.drawable.cox_monogram),
            contentDescription = null,
            modifier = Modifier.padding(end = size * 0.3f).size(size),
        )
        androidx.compose.material3.Text(
            androidx.compose.ui.text.buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(color = com.coxtv.mobile.ui.theme.CoxColors.Text)) { append("Cox") }
                withStyle(androidx.compose.ui.text.SpanStyle(color = com.coxtv.mobile.ui.theme.CoxColors.Accent)) { append("TV") }
            },
            style = style,
            fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
            letterSpacing = androidx.compose.ui.unit.TextUnit(-0.04f, androidx.compose.ui.unit.TextUnitType.Em),
        )
    }
}

/** Picture-in-picture icon (not part of the core Material icon set). */
val PipIcon: ImageVector by lazy {
    ImageVector.Builder("Pip", 24.dp, 24.dp, 24f, 24f).apply {
        // Even-odd fill: frame outline with a hole, and the small window filled inside it.
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            // Outer frame
            moveTo(21f, 3f); horizontalLineTo(3f)
            curveTo(1.9f, 3f, 1f, 3.9f, 1f, 5f); verticalLineTo(19f)
            curveTo(1f, 20.1f, 1.9f, 21f, 3f, 21f); horizontalLineTo(21f)
            curveTo(22.1f, 21f, 23f, 20.1f, 23f, 19f); verticalLineTo(5f)
            curveTo(23f, 3.9f, 22.1f, 3f, 21f, 3f); close()
            moveTo(21f, 19f); horizontalLineTo(3f); verticalLineTo(5f); horizontalLineTo(21f); close()
            // Small window
            moveTo(19f, 11f); horizontalLineTo(11f); verticalLineTo(17f); horizontalLineTo(19f); close()
        }
    }.build()
}
