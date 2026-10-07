package com.coxtv.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.coxtv.AppContainer
import com.coxtv.data.SetupCodes
import com.coxtv.data.SourceConfig
import com.coxtv.ui.components.CoxButton
import com.coxtv.ui.components.CoxWordmark
import com.coxtv.ui.components.TvTextField
import com.coxtv.ui.components.collectAsStateCompat
import com.coxtv.ui.theme.CoxColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val EXAMPLE_M3U = "http://provider.example/playlist.m3u"
private const val EXAMPLE_EPG = "http://provider.example/guide.xml"

@Composable
fun LoginScreen(container: AppContainer, onConnected: () -> Unit) {
    val scope = rememberCoroutineScope()
    var server by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var m3u by rememberSaveable { mutableStateOf("") }
    var epg by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val firstField = remember { FocusRequester() }
    val connectButton = remember { FocusRequester() }
    val linked by container.settings.isLinked.collectAsStateCompat(false)
    val deviceName by container.settings.deviceName.collectAsStateCompat("")
    val useServer by container.settings.useServer.collectAsStateCompat(true)

    LaunchedEffect(Unit) {
        if (!loaded) {
            val c = container.settings.config()
            server = c.xtreamServer; user = c.xtreamUser; pass = c.xtreamPass
            m3u = c.m3uUrl; epg = c.epgUrl
            loaded = true
        }
        runCatching { firstField.requestFocus() }
    }

    fun useCode() {
        if (SetupCodes.normalize(code).length != 6) {
            isError = true
            message = "Enter the 6-character code from tv.thecoxhome.com (Set up a TV), like K7P-2QX."
            return
        }
        busy = true
        isError = false
        message = "Getting your links…"
        scope.launch {
            try {
                message = "Loading channels…"
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
            message = "Enter Xtream server + username, or an M3U playlist URL."
            return
        }
        if (config.xtreamServer.isNotBlank() && config.xtreamUser.isBlank()) {
            isError = true
            message = "Xtream username is required."
            return
        }
        busy = true
        isError = false
        message = "Connecting…"
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

    Column(
        Modifier.fillMaxSize().background(CoxColors.Bg).verticalScroll(rememberScrollState()).padding(horizontal = 56.dp, vertical = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            CoxWordmark(40.sp)
            Spacer(Modifier.width(16.dp))
            Text("Connect your TV sources", style = MaterialTheme.typography.titleMedium, color = CoxColors.TextDim)
        }
        Spacer(Modifier.height(24.dp))

        // Easiest: a one-time code from tv.thecoxhome.com instead of typing links.
        Section(title = "Enter setup code", subtitle = "From tv.thecoxhome.com > Watch > Set up a TV", modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvTextField(code, { code = it }, "Setup code", Modifier.width(320.dp).focusRequester(firstField), placeholder = "K7P-2QX")
                Spacer(Modifier.width(20.dp))
                CoxButton(if (busy) "Connecting…" else "Use code", onClick = { if (!busy) useCode() }, primary = true)
            }
            if (linked) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Linked as \"$deviceName\" to the account that made the code (its Remote on tv.thecoxhome.com and phone). " +
                            if (useServer) "Channels play through your stream server." else "Channels play straight from the provider.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoxColors.TextDim,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    CoxButton(
                        if (useServer) "Play from provider instead" else "Play through stream server",
                        onClick = { scope.launch { container.settings.setUseServer(!useServer) } },
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Or enter your links yourself:", style = MaterialTheme.typography.titleSmall, color = CoxColors.TextDim)
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Section(title = "Xtream Codes", subtitle = "Primary source", modifier = Modifier.weight(1f)) {
                TvTextField(server, { server = it }, "Server URL", placeholder = "http://provider.example:8080")
                TvTextField(user, { user = it }, "Username", keyboardType = androidx.compose.ui.text.input.KeyboardType.Text)
                TvTextField(pass, { pass = it }, "Password", isPassword = true)
            }
            Section(title = "M3U + XMLTV", subtitle = "Optional extra source", modifier = Modifier.weight(1f)) {
                TvTextField(m3u, { m3u = it }, "M3U playlist URL", placeholder = EXAMPLE_M3U)
                TvTextField(epg, { epg = it }, "XMLTV guide URL", placeholder = EXAMPLE_EPG)
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoxButton(if (busy) "Connecting…" else "Connect", onClick = { if (!busy) connect() }, primary = true, modifier = Modifier.focusRequester(connectButton))
            Spacer(Modifier.width(20.dp))
            message?.let {
                Text(it, color = if (isError) CoxColors.Error else CoxColors.TextDim, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun Section(title: String, subtitle: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.background(CoxColors.Panel, RoundedCornerShape(14.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CoxColors.TextDim)
        }
        content()
    }
}
