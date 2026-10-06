package com.coxtv.ui.update

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.coxtv.ui.components.CoxButton
import com.coxtv.ui.components.ProgressLine
import com.coxtv.ui.theme.CoxColors
import com.coxtv.update.UpdateManager
import com.coxtv.update.UpdateState

/** Shows update prompts/progress from [UpdateManager]; renders nothing while idle. */
@Composable
fun UpdateDialog(updates: UpdateManager) {
    val state by updates.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as Activity

    // Once downloaded, go straight to the installer (or the permission screen).
    LaunchedEffect(state) {
        if (state is UpdateState.ReadyToInstall) updates.install(activity)
    }

    val s = state
    if (s is UpdateState.Idle) return

    Dialog(onDismissRequest = { if (s !is UpdateState.Downloading) updates.dismiss() }) {
        val primary = remember { FocusRequester() }
        LaunchedEffect(s::class) {
            withFrameNanos { }
            runCatching { primary.requestFocus() }
        }
        Column(
            Modifier.width(560.dp).background(CoxColors.Panel, RoundedCornerShape(14.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (s) {
                UpdateState.Checking -> {
                    Title("Checking for updates…")
                    Body("Version ${updates.currentVersion} installed")
                    Buttons { CoxButton("Cancel", onClick = updates::dismiss, modifier = Modifier.focusRequester(primary)) }
                }
                UpdateState.UpToDate -> {
                    Title("CoxTV is up to date")
                    Body("Version ${updates.currentVersion} is the newest release.")
                    Buttons { CoxButton("OK", onClick = updates::dismiss, primary = true, modifier = Modifier.focusRequester(primary)) }
                }
                is UpdateState.Available -> {
                    Title("Update available: ${s.release.version}")
                    Body("You have ${updates.currentVersion}. The update downloads and installs in a minute; your channels and favorites are kept.")
                    if (s.release.notes.isNotBlank()) Notes(s.release.notes)
                    Buttons {
                        CoxButton("Update now", onClick = updates::download, primary = true, modifier = Modifier.focusRequester(primary))
                        CoxButton("Later", onClick = updates::dismiss)
                    }
                }
                is UpdateState.Downloading -> {
                    Title("Downloading ${s.release.version}…")
                    if (s.progress >= 0) {
                        ProgressLine(s.progress, Modifier.fillMaxWidth())
                        Body("${(s.progress * 100).toInt()}%")
                    } else {
                        Body("Please wait…")
                    }
                    Buttons { CoxButton("Cancel", onClick = updates::dismiss, modifier = Modifier.focusRequester(primary)) }
                }
                is UpdateState.NeedsPermission -> {
                    Title("Allow CoxTV to install updates")
                    Body("In the settings screen that opened, turn on CoxTV (\"Install unknown apps\" / \"Apps from unknown sources\"), then press Back. The update continues automatically.")
                    Buttons {
                        CoxButton("Try again", onClick = { updates.install(activity) }, primary = true, modifier = Modifier.focusRequester(primary))
                        CoxButton("Cancel", onClick = updates::dismiss)
                    }
                }
                is UpdateState.ReadyToInstall -> {
                    Title("Ready to install ${s.release.version}")
                    Body("Choose Install on the next screen. CoxTV restarts with the new version.")
                    Buttons {
                        CoxButton("Install", onClick = { updates.install(activity) }, primary = true, modifier = Modifier.focusRequester(primary))
                        CoxButton("Later", onClick = updates::dismiss)
                    }
                }
                is UpdateState.Failed -> {
                    Title("Update problem")
                    Body(s.message)
                    Buttons {
                        CoxButton("Try again", onClick = { updates.check() }, primary = true, modifier = Modifier.focusRequester(primary))
                        CoxButton("Close", onClick = updates::dismiss)
                    }
                }
                UpdateState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge, color = CoxColors.TextDim)

@Composable
private fun Notes(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = CoxColors.Text,
    maxLines = 6,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.background(CoxColors.PanelHi, RoundedCornerShape(8.dp)).padding(12.dp).fillMaxWidth(),
)

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { content() }
}
