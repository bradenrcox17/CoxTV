package com.coxtv.mobile.ui

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coxtv.mobile.ui.theme.CoxColors
import com.coxtv.update.UpdateManager
import com.coxtv.update.UpdateState

/** Update prompts and progress from [UpdateManager]; renders nothing while idle. */
@Composable
fun UpdateDialog(updates: UpdateManager, activity: Activity) {
    val state by updates.state.collectAsStateWithLifecycle()

    // Once downloaded, go straight to the installer (or the permission screen).
    LaunchedEffect(state) {
        if (state is UpdateState.ReadyToInstall) updates.install(activity)
    }

    val s = state
    if (s is UpdateState.Idle) return

    val title: String
    val body: @Composable () -> Unit
    var confirm: Pair<String, () -> Unit>? = null
    var dismiss: Pair<String, () -> Unit>? = "Close" to updates::dismiss

    when (s) {
        UpdateState.Checking -> {
            title = "Checking for updates…"
            body = { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            dismiss = "Cancel" to updates::dismiss
        }
        UpdateState.UpToDate -> {
            title = "CoxTV is up to date"
            body = { Text("Version ${updates.currentVersion} is the newest release.") }
            confirm = "OK" to updates::dismiss
            dismiss = null
        }
        is UpdateState.Available -> {
            title = "Update available: ${s.release.version}"
            body = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text("You have ${updates.currentVersion}. Your channels and favorites are kept.")
                    if (s.release.notes.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(s.release.notes, color = CoxColors.TextDim)
                    }
                }
            }
            confirm = "Update now" to updates::download
            dismiss = "Later" to updates::dismiss
        }
        is UpdateState.Downloading -> {
            title = "Downloading ${s.release.version}…"
            body = {
                Column {
                    if (s.progress >= 0) {
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text("${(s.progress * 100).toInt()}%")
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            dismiss = "Cancel" to updates::dismiss
        }
        is UpdateState.NeedsPermission -> {
            title = "Allow CoxTV to install updates"
            body = { Text("On the screen that opened, turn on \"Allow from this source\", then go back. The update continues automatically.") }
            confirm = "Try again" to { updates.install(activity) }
            dismiss = "Cancel" to updates::dismiss
        }
        is UpdateState.ReadyToInstall -> {
            title = "Ready to install ${s.release.version}"
            body = { Text("Tap Install (or Update) on the next screen.") }
            confirm = "Install" to { updates.install(activity) }
            dismiss = "Later" to updates::dismiss
        }
        is UpdateState.Failed -> {
            title = "Update problem"
            body = { Text(s.message) }
            confirm = "Try again" to { updates.check() }
        }
        UpdateState.Idle -> return
    }

    AlertDialog(
        onDismissRequest = { if (s !is UpdateState.Downloading) updates.dismiss() },
        properties = DialogProperties(dismissOnClickOutside = s !is UpdateState.Downloading),
        title = { Text(title) },
        text = body,
        confirmButton = { confirm?.let { (label, action) -> TextButton(onClick = action) { Text(label) } } },
        dismissButton = dismiss?.let { (label, action) -> { TextButton(onClick = action) { Text(label) } } },
    )
}
