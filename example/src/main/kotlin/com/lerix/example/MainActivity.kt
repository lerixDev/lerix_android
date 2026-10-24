package com.lerix.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lerix.sdk.Lerix
import com.lerix.sdk.notifications.LerixNotificationPayload
import com.lerix.sdk.surveys.LerixSurveyPrompt
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LerixExampleScreen(::requestNotificationPermission) }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun LerixExampleScreen(requestOsPermission: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("Not requested") }
    var userId by remember { mutableStateOf<String?>(null) }
    var deviceId by remember { mutableStateOf("") }
    var tokenId by remember { mutableStateOf<String?>(null) }
    var lastReceived by remember { mutableStateOf("none") }
    var lastTapped by remember { mutableStateOf("none") }
    var lastAction by remember { mutableStateOf("") }
    var headless by remember { mutableStateOf<LerixSurveyPrompt?>(null) }

    fun refresh() {
        userId = Lerix.getUserId()
        deviceId = Lerix.notifications.getDeviceId()
        tokenId = Lerix.notifications.getRegisteredTokenId()
    }

    fun copyToClipboard(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        lastAction = "Copied $label"
    }

    LaunchedEffect(Unit) {
        Lerix.notifications.setOnNotificationReceived { payload: LerixNotificationPayload ->
            lastReceived = "${payload.title ?: "(no title)"} — ${payload.body ?: ""}"
        }
        Lerix.notifications.setOnNotificationTapped { payload: LerixNotificationPayload ->
            lastTapped = "${payload.title ?: "(no title)"} — ${payload.body ?: ""}"
        }
        repeat(10) {
            if (userId != null) return@repeat
            kotlinx.coroutines.delay(500)
            refresh()
        }
    }

    // The app's own UI for a headless survey prompt.
    headless?.let { prompt ->
        LaunchedEffect(prompt) { prompt.shown() }
        AlertDialog(
            onDismissRequest = {
                prompt.dismiss()
                headless = null
                lastAction = "Headless prompt dismissed"
            },
            title = { Text(prompt.question ?: "Rate us") },
            text = {
                Column {
                    Text("My own UI (headless)", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        prompt.choices.forEach { choice ->
                            OutlinedButton(
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                onClick = {
                                    val ok = prompt.answer(choice.id, "headless message")
                                    headless = null
                                    lastAction = "Headless answer ${choice.id} (${prompt.groupOf(choice.id)}): $ok"
                                },
                            ) { Text(choice.emoji ?: choice.value.toString()) }
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Lerix Android Example", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))

            Text("SDK state", style = MaterialTheme.typography.titleMedium)
            LabeledRow("Permission", status)
            CopyableRow("User ID", userId) { copyToClipboard("user id", it) }
            CopyableRow("Device ID", deviceId) { copyToClipboard("device id", it) }
            CopyableRow("Notification Token ID", tokenId) { copyToClipboard("notification token id", it) }
            Text(
                "To send a test push from the dashboard, copy the Notification Token ID (not the User or Device ID) into its \"device tokens\" field — it only appears after notification permission is granted.",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(16.dp))
            Text("Notification events", style = MaterialTheme.typography.titleMedium)
            LabeledRow("Last received (foreground)", lastReceived)
            LabeledRow("Last tapped", lastTapped)

            Spacer(Modifier.height(16.dp))
            Text("Actions", style = MaterialTheme.typography.titleMedium)

            Button(onClick = {
                requestOsPermission()
                scope.launch {
                    val granted = Lerix.notifications.requestPermissions()
                    status = if (granted) "authorized" else "denied"
                    repeat(10) {
                        if (tokenId != null) return@repeat
                        kotlinx.coroutines.delay(500)
                        refresh()
                    }
                }
            }) { Text("Request notification permission") }

            Button(onClick = {
                status = Lerix.notifications.checkPermissionStatus().name
            }) { Text("Check permission status") }

            Button(onClick = {
                Lerix.throwError(
                    "Example button tapped: simulated error",
                    stack = Thread.currentThread().stackTrace.map { it.toString() },
                )
                lastAction = "Reported test error"
            }) { Text("Throw test error") }

            Button(onClick = {
                scope.launch {
                    Lerix.reRegisterUser()
                    refresh()
                    lastAction = "Re-registered user"
                }
            }) { Text("Re-register user") }

            Button(onClick = {
                (context as? android.app.Activity)?.let { activity ->
                    Lerix.feedback.show(activity) { result ->
                        lastAction = if (result.submitted) "Feedback sent (${result.clientId})" else "Feedback cancelled"
                    }
                }
            }) { Text("Send feedback") }

            Button(onClick = {
                (context as? android.app.Activity)?.let { activity ->
                    scope.launch {
                        val shown = Lerix.surveys.maybeShowNps(activity, force = true)
                        lastAction = if (shown) "NPS shown" else "NPS not shown (see logcat)"
                    }
                }
            }) { Text("Show NPS survey (force)") }

            // Rating surveys: keys set in the dashboard.
            listOf("checkout", "delivery", "help", "checkout-v2").forEach { key ->
                Button(onClick = {
                    (context as? android.app.Activity)?.let { activity ->
                        scope.launch {
                            val shown = Lerix.surveys.showRating(activity, key, force = true, custom = mapOf("source" to "example"))
                            lastAction = if (shown) "Rating \"$key\" shown" else "Rating \"$key\" not shown (see logcat)"
                        }
                    }
                }) { Text("Show rating \"$key\" (force)") }
            }

            Button(onClick = {
                (context as? android.app.Activity)?.let { activity ->
                    scope.launch {
                        val shown = Lerix.surveys.showRating(activity, "checkout-v2")
                        lastAction = if (shown) "Rating \"checkout-v2\" shown" else "Rating \"checkout-v2\" not due (see logcat)"
                    }
                }
            }) { Text("Show rating \"checkout-v2\" (if due)") }

            Button(onClick = {
                scope.launch {
                    headless = Lerix.surveys.dueRating("delivery", force = true, custom = mapOf("source" to "headless"))
                    lastAction = if (headless != null) "Headless prompt ready" else "No headless prompt"
                }
            }) { Text("Headless rating \"delivery\"") }

            Button(onClick = {
                Lerix.track("example_button", mapOf("count" to 1, "source" to "example"))
                Lerix.analytics.flush()
                lastAction = "Tracked example_button"
            }) { Text("Track event + flush") }

            Button(onClick = {
                val list = emptyList<Int>()
                @Suppress("UNUSED_EXPRESSION")
                list[5]
            }) { Text("Trigger native crash") }

            if (lastAction.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Last action", style = MaterialTheme.typography.titleMedium)
                Text(lastAction)
            }
        }
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CopyableRow(label: String, value: String?, onCopy: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value ?: "none", style = MaterialTheme.typography.bodyMedium)
            if (value != null) {
                TextButton(onClick = { onCopy(value) }) { Text("Copy") }
            }
        }
    }
}
