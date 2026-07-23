package com.pbec.preboardexamchecker.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.pbec.preboardexamchecker.domain.email.EmailConfig
import com.pbec.preboardexamchecker.domain.email.EmailProvider
import com.pbec.preboardexamchecker.domain.email.EmailSettings
import com.pbec.preboardexamchecker.domain.email.SmtpSlipSender
import com.pbec.preboardexamchecker.ui.theme.BrandTopAppBar
import kotlinx.coroutines.launch

/** Provider-specific page where the user creates an app password. Null when we don't know (Other). */
private fun appPasswordHelpUrl(provider: EmailProvider): String? = when (provider) {
    EmailProvider.GMAIL -> "https://myaccount.google.com/apppasswords"
    EmailProvider.YAHOO -> "https://login.yahoo.com/account/security"
    EmailProvider.OUTLOOK -> "https://account.microsoft.com/security"
    EmailProvider.OTHER -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val sender = remember { SmtpSlipSender() }

    val initial = remember { EmailSettings.load(context) }
    var provider by remember { mutableStateOf(initial.provider) }
    var fromAddress by remember { mutableStateOf(initial.fromAddress) }
    var senderName by remember { mutableStateOf(initial.senderName) }
    var password by remember { mutableStateOf(initial.password) }
    var customHost by remember { mutableStateOf(initial.customHost) }
    var customPort by remember { mutableStateOf(initial.customPort.toString()) }
    var showPassword by remember { mutableStateOf(false) }
    var sendingTest by remember { mutableStateOf(false) }
    // Dialog, not snackbar: TLS/connection errors are long and a timed snackbar closes before it can be read.
    var testResultDialog by remember { mutableStateOf<String?>(null) }

    fun currentConfig() = EmailConfig(
        provider = provider,
        fromAddress = fromAddress,
        senderName = senderName,
        password = password,
        customHost = customHost,
        customPort = customPort.toIntOrNull() ?: 587,
    )

    fun persist() = EmailSettings.save(context, currentConfig())

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            BrandTopAppBar(
                title = "Email",
                navigationIcon = {
                    IconButton(onClick = { persist(); navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            SettingsSectionLabel("Send result slips by email")
            SettingsSectionCaption(
                "Slips are sent from your own email account, one per student, in a single tap. " +
                    "Your credentials are stored encrypted on this device only."
            )

            SettingsSectionLabel("Email provider")
            EmailProvider.values().forEachIndexed { i, p ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                SettingRadioOption(
                    title = p.label,
                    subtitle = when (p) {
                        EmailProvider.GMAIL -> "smtp.gmail.com"
                        EmailProvider.YAHOO -> "smtp.mail.yahoo.com"
                        EmailProvider.OUTLOOK -> "smtp.office365.com"
                        EmailProvider.OTHER -> "Enter your own SMTP server"
                    },
                    selected = provider == p,
                    onSelect = { provider = p },
                )
            }

            Spacer(Modifier.height(24.dp))
            SettingsSectionLabel("Your account")
            OutlinedTextField(
                value = fromAddress,
                onValueChange = { fromAddress = it },
                label = { Text("Email address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = senderName,
                onValueChange = { senderName = it },
                label = { Text("Sender name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("App password") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "Hide" else "Show",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Not your normal login password. Turn on 2-Step Verification for your account first, " +
                    "then generate an app password and paste the 16-character code here. " +
                    "If you cannot find or create an app password, 2-Step Verification is probably not " +
                    "turned on yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            appPasswordHelpUrl(provider)?.let { url ->
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }.onFailure {
                            scope.launch { snackbar.showSnackbar("Couldn't open browser.") }
                        }
                    },
                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 0.dp),
                ) { Text("How do I get a ${provider.label} app password?") }
            }

            if (provider == EmailProvider.OTHER) {
                Spacer(Modifier.height(16.dp))
                SettingsSectionLabel("SMTP server")
                OutlinedTextField(
                    value = customHost,
                    onValueChange = { customHost = it },
                    label = { Text("Host") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customPort,
                    onValueChange = { customPort = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Port (587 STARTTLS / 465 SSL)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { persist(); scope.launch { snackbar.showSnackbar("Email settings saved.") } },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
                OutlinedButton(
                    enabled = !sendingTest && currentConfig().isConfigured,
                    onClick = {
                        persist()
                        sendingTest = true
                        scope.launch {
                            val error = sender.sendTest(currentConfig())
                            sendingTest = false
                            testResultDialog = if (error == null) "Test email sent to $fromAddress."
                                else "Test failed: $error"
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(if (sendingTest) "Sending…" else "Send test") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    testResultDialog?.let { message ->
        AlertDialog(
            onDismissRequest = { testResultDialog = null },
            title = { Text("Send test") },
            text = { Text(message, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { testResultDialog = null }) { Text("OK") }
            },
        )
    }
}
