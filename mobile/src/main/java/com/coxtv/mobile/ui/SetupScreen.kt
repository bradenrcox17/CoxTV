package com.coxtv.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.coxtv.AppContainer
import com.coxtv.data.SetupCodes
import com.coxtv.data.SourceConfig
import com.coxtv.mobile.ui.theme.CoxColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(container: AppContainer, canGoBack: Boolean, onBack: () -> Unit, onConnected: () -> Unit) {
    val scope = rememberCoroutineScope()
    var m3u by rememberSaveable { mutableStateOf("") }
    var epg by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var showXtream by rememberSaveable { mutableStateOf(false) }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!loaded) {
            val c = container.settings.config()
            m3u = c.m3uUrl; epg = c.epgUrl
            server = c.xtreamServer; user = c.xtreamUser; pass = c.xtreamPass
            showXtream = c.hasXtream
            loaded = true
        }
    }

    fun useCode() {
        if (SetupCodes.normalize(code).length != 6) {
            isError = true
            message = "Enter the 6-character code from tv.thecoxhome.com (Set up a TV), like K7P-2QX."
            return
        }
        busy = true
        isError = false
        message = "Loading channels… large playlists can take a minute."
        scope.launch {
            try {
                val count = container.repository.connectWithSetupCode(code, container.device())
                message = if (count == null) "Linked. Your Xtream login was kept." else "Loaded $count channels"
                if (count == null) delay(1_500)
                onConnected()
            } catch (e: Exception) {
                isError = true
                message = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    fun connect() {
        val config = SourceConfig(server.trim(), user.trim(), pass, m3u.trim(), epg.trim())
        if (!config.isConfigured) {
            isError = true
            message = "Enter your playlist (M3U) link, or an Xtream server and username."
            return
        }
        busy = true
        isError = false
        message = "Loading channels… large playlists can take a minute."
        scope.launch {
            try {
                val count = container.repository.connect(config)
                message = "Loaded $count channels"
                onConnected()
            } catch (e: Exception) {
                isError = true
                message = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoxWordmark(MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.weight(1f))
                if (canGoBack) TextButton(onClick = onBack) { Text("Cancel") }
            }
            Text("Connect your TV service", style = MaterialTheme.typography.titleMedium, color = CoxColors.TextDim)
            Spacer(Modifier.height(4.dp))

            // Easiest: a one-time code from tv.thecoxhome.com instead of typing links.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(12) },
                    label = { Text("Setup code") },
                    placeholder = { Text("K7P-2QX") },
                    supportingText = { Text("From tv.thecoxhome.com > Watch > Set up a TV") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { if (!busy) useCode() }),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { if (!busy) useCode() }, enabled = !busy) { Text("Use code") }
            }
            HorizontalDivider()
            Text("Or enter your links yourself", style = MaterialTheme.typography.titleSmall, color = CoxColors.TextDim)

            OutlinedTextField(
                value = m3u,
                onValueChange = { m3u = it },
                label = { Text("Playlist (M3U) link") },
                placeholder = { Text("http://provider.example/get.php?…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = epg,
                onValueChange = { epg = it },
                label = { Text("TV guide (XMLTV) link — optional") },
                supportingText = { Text("Leave blank if your playlist includes its own guide.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()
            if (!showXtream) {
                TextButton(onClick = { showXtream = true }) { Text("Have an Xtream login instead? (server, username, password)") }
            } else {
                Text("Xtream login", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = server, onValueChange = { server = it },
                    label = { Text("Server, e.g. http://provider.example:8080") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it },
                    label = { Text("Username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { if (!busy) connect() }, enabled = !busy) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(10.dp))
                    }
                    Text(if (busy) "Connecting…" else "Connect")
                }
            }
            message?.let {
                Text(it, color = if (isError) CoxColors.Error else CoxColors.TextDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
