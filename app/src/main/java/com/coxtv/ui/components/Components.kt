package com.coxtv.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.SubcomposeAsyncImage
import com.coxtv.data.db.Channel
import com.coxtv.ui.theme.CoxColors
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A focusable list row / tile with a consistent TV focus treatment. */
@Composable
fun FocusTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    focusedScale: Float = 1.02f,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.touchClick(onClick, onLongClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) CoxColors.AccentDim else Color.Transparent,
            contentColor = CoxColors.Text,
            focusedContainerColor = CoxColors.Accent,
            focusedContentColor = CoxColors.OnAccent,
            pressedContainerColor = CoxColors.Accent,
            pressedContentColor = CoxColors.OnAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        content = content,
    )
}

@Composable
fun CoxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp).then(if (enabled) Modifier.touchClick(onClick) else Modifier),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) CoxColors.AccentDim else CoxColors.PanelHi,
            contentColor = if (primary) CoxColors.Accent else CoxColors.Text,
            focusedContainerColor = CoxColors.Accent,
            focusedContentColor = CoxColors.OnAccent,
            disabledContainerColor = CoxColors.Panel,
            disabledContentColor = CoxColors.TextDim,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
fun ChannelLogo(channel: Channel, modifier: Modifier = Modifier) {
    val fallback: @Composable () -> Unit = {
        Box(
            Modifier.fillMaxWidth().fillMaxHeight().background(CoxColors.PanelHi, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                channel.name.take(3).uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = CoxColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
    Box(modifier.clip(RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        if (channel.logo.isNullOrBlank()) {
            fallback()
        } else {
            SubcomposeAsyncImage(
                model = channel.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                error = { fallback() },
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            )
        }
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = CoxColors.Accent) {
    Box(modifier.height(3.dp).background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(2.dp))) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(color, RoundedCornerShape(2.dp)),
        )
    }
}

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

/** Lifecycle-aware collection: stops while the app is in the background. */
@Composable
fun <T> kotlinx.coroutines.flow.Flow<T>.collectAsStateCompat(initial: T): State<T> =
    collectAsStateWithLifecycle(initial)

/** The CoxTV logo: the C monogram and the wordmark (white "Cox", orange "TV"), as on the
 * launcher icon, the Roku poster and the website. */
@Composable
fun CoxWordmark(fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier, monogram: Boolean = true) {
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (monogram) {
            val size = with(androidx.compose.ui.platform.LocalDensity.current) { (fontSize * 1.25f).toDp() }
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(com.coxtv.core.R.drawable.cox_monogram),
                contentDescription = null,
                modifier = Modifier.padding(end = size * 0.32f).size(size),
            )
        }
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(color = CoxColors.Text)) { append("Cox") }
                withStyle(androidx.compose.ui.text.SpanStyle(color = CoxColors.Accent)) { append("TV") }
            },
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = androidx.compose.ui.unit.TextUnit(-0.04f, androidx.compose.ui.unit.TextUnitType.Em),
        )
    }
}


@Composable
fun Clock(modifier: Modifier = Modifier) {
    val now = rememberNow(15_000).value
    val fmt = rememberTimeFormatter()
    Text(fmt.time(now), modifier = modifier, style = MaterialTheme.typography.titleMedium, color = CoxColors.TextDim)
}
