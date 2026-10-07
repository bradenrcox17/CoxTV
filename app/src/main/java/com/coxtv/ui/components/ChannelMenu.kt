package com.coxtv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.coxtv.data.DeviceLink
import com.coxtv.data.Teams
import com.coxtv.data.db.Channel
import com.coxtv.ui.theme.CoxColors

/** Options for one channel (☰ Menu or hold OK): favorite on/off, hide, and for a game, star its teams. */
@Composable
fun ChannelMenu(
    channel: Channel,
    onFavorite: () -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
    teams: List<Pair<DeviceLink.Team, Boolean>> = emptyList(),
    teamSport: String = "",
    onToggleTeam: (DeviceLink.Team) -> Unit = {},
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(channel.id) { runCatching { first.requestFocus() } }
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).touchClick(onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(380.dp).background(CoxColors.Panel, RoundedCornerShape(14.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            CoxButton(
                if (channel.favorite) "★  Remove from favorites" else "☆  Add to favorites",
                onClick = { onFavorite(); onDismiss() },
                modifier = Modifier.fillMaxWidth().focusRequester(first),
            )
            CoxButton("Hide this channel", onClick = { onHide(); onDismiss() }, modifier = Modifier.fillMaxWidth())
            teams.forEach { (team, mine) ->
                CoxButton(
                    (if (mine) "★  Remove " else "☆  Star ") + team.display + " (" + Teams.sportLabel(teamSport) + " team)",
                    onClick = { onToggleTeam(team); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            CoxButton("Cancel", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            Text(
                "Hidden channels can be brought back in Categories & favorites.",
                style = MaterialTheme.typography.bodySmall,
                color = CoxColors.TextDim,
            )
        }
    }
}
