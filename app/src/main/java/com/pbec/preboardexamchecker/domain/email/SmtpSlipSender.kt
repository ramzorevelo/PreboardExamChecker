package com.pbec.preboardexamchecker.domain.email

import com.pbec.preboardexamchecker.BuildConfig
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext

/** One student's slip to email. */
data class SlipMail(val recipient: String, val studentName: String, val file: File)

/** Outcome of a batch send: how many went out and which failed with what error. */
data class EmailSendResult(
    val sent: Int,
    val failures: List<Pair<String, String>>, // studentName -> error message
)

/** Result of trying to open an SMTP [Transport]: either a connected transport+port, or a combined error. */
private data class ConnectResult(val transport: Transport?, val port: Int, val error: String?)

/**
 * Sends result-slip PDFs over the instructor's own SMTP account (see [EmailConfig]). The whole
 * batch shares a single [Transport] connection — connect once, send every slip, close once —
 * rather than reconnecting per message. Reconnecting per message is what used to trip Gmail's
 * abuse detection on bursts of student slips ("Could not convert socket to TLS: Socket is
 * closed" was really Gmail temp-blocking the account after a rapid string of fresh connections).
 * Client-side SMTP keeps credentials on-device (no backend).
 */
@Singleton
class SmtpSlipSender @Inject constructor() {

    /**
     * The platform's own TLS provider, requested by name rather than via the default
     * [SSLContext.getInstance] lookup. Google Play Services installs its bundled "GmsCore_OpenSSL"
     * provider asynchronously sometime after process start (observed here: not present at app
     * launch, present a few minutes later, most likely triggered by Firebase/Firestore's own
     * network init) and gives it top priority, so `SSLContext.getInstance("TLS")` silently starts
     * resolving to it instead of "AndroidOpenSSL". Its SSLSocket handshake is broken on this
     * device (confirmed via stack trace: SocketException: Socket is closed at
     * com.google.android.gms.org.conscrypt.NativeSsl.doHandshake), which is why the very first
     * SMTP send in a process succeeds and every one after fails once GMS's provider installs.
     * Naming "AndroidOpenSSL" explicitly sidesteps whatever is currently highest-priority.
     */
    private fun platformSslContext(): SSLContext =
        runCatching { SSLContext.getInstance("TLS", "AndroidOpenSSL") }
            .getOrElse { SSLContext.getInstance("TLS") }
            .apply { init(null, null, null) }

    /**
     * Some networks (mobile carriers, school/public Wi-Fi) block one SMTP port outright while
     * letting the other through, which surfaces as a connect-level failure no matter how the
     * session properties are tuned. For hosts known to answer on both, try the configured port
     * first, then the other, so a single blocked port doesn't take email down entirely.
     */
    private fun candidatePorts(config: EmailConfig): List<Int> {
        val alternate = when (config.host) {
            "smtp.gmail.com", "smtp.mail.yahoo.com" -> if (config.port == 465) 587 else 465
            else -> null // Office 365 / a custom "Other" host: only the configured port is known-good.
        }
        return listOfNotNull(config.port, alternate)
    }

    /** Builds a Jakarta Mail [Session] for [config] on [port]; STARTTLS on 587, implicit SSL on 465. */
    private fun session(config: EmailConfig, port: Int): Session {
        val props = Properties().apply {
            put("mail.smtp.host", config.host)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", "true")
            if (port == 465) {
                put("mail.smtp.ssl.enable", "true")
            } else {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }
            // Trusting the configured host explicitly avoids handshake failures when the
            // bundled trust store doesn't chain the host's cert.
            put("mail.smtp.ssl.trust", config.host)
            put("mail.smtp.ssl.protocols", "TLSv1.2")
            put("mail.smtp.ssl.socketFactory", platformSslContext().socketFactory)
            // Fail fast rather than hang the send if the server is unreachable.
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "20000")
            put("mail.smtp.writetimeout", "20000")
            if (BuildConfig.DEBUG) {
                put("mail.debug", "true")
            }
        }
        return Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(config.fromAddress, config.password)
        }).apply {
            if (BuildConfig.DEBUG) debug = true
        }
    }

    /**
     * [MessagingException] wraps the real cause (e.g. an [javax.net.ssl.SSLHandshakeException])
     * in [MessagingException.getNextException] rather than [Throwable.getCause], so plain
     * `e.message` alone can hide the actual reason. This walks both chains and prefixes each
     * part with its exception's simple class name, e.g. "SSLHandshakeException: Remote host
     * terminated the handshake".
     */
    private fun fullMessage(e: Throwable): String {
        if (BuildConfig.DEBUG) android.util.Log.e("SmtpSlipSender", "send failure", e)
        val parts = mutableListOf<String>()
        var cur: Throwable? = e
        while (cur != null) {
            val label = cur.javaClass.simpleName
            val msg = cur.message
            val part = if (msg.isNullOrBlank()) label else "$label: $msg"
            if (part !in parts) parts.add(part)
            cur = (cur as? MessagingException)?.nextException ?: cur.cause?.takeIf { it !== cur }
        }
        return if (parts.isEmpty()) e.javaClass.simpleName else parts.joinToString("; ")
    }

    private fun fromAddress(config: EmailConfig): InternetAddress =
        if (config.senderName.isNotBlank()) InternetAddress(config.fromAddress, config.senderName)
        else InternetAddress(config.fromAddress)

    private fun buildMessage(
        session: Session,
        config: EmailConfig,
        to: String,
        subject: String,
        body: String,
        attachment: File?,
    ): MimeMessage = MimeMessage(session).apply {
        setFrom(fromAddress(config))
        setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
        setSubject(subject)
        if (attachment == null) {
            setText(body)
        } else {
            val multipart = MimeMultipart().apply {
                addBodyPart(MimeBodyPart().apply { setText(body) })
                addBodyPart(MimeBodyPart().apply { attachFile(attachment) })
            }
            setContent(multipart)
        }
    }

    /**
     * Opens one [Transport] connection on [port], trying up to two connect attempts with a
     * short delay between them (a fresh handshake occasionally fails transiently). Returns the
     * connected transport, or null with the last exception seen.
     */
    private suspend fun openTransport(config: EmailConfig, port: Int): Pair<Transport?, Exception?> {
        var last: Exception? = null
        repeat(2) { attempt ->
            try {
                val transport = session(config, port).getTransport("smtp")
                transport.connect(config.host, port, config.fromAddress, config.password)
                return transport to null
            } catch (e: Exception) {
                last = e
                if (attempt == 0) delay(700)
            }
        }
        return null to last
    }

    /**
     * Connects to the first candidate port that accepts a connection, trying each port up to
     * twice (see [openTransport]). Returns the live transport and the port it connected on, or a
     * null transport with [ConnectResult.error] holding EVERY port's error, e.g.
     * `"465: SSLHandshakeException: ...; 587: SocketTimeoutException: ..."`.
     */
    private suspend fun connectAny(config: EmailConfig): ConnectResult {
        val perPortErrors = mutableListOf<String>()
        for (port in candidatePorts(config)) {
            val (transport, error) = openTransport(config, port)
            if (transport != null) return ConnectResult(transport, port, null)
            perPortErrors.add("$port: ${fullMessage(error!!)}")
        }
        return ConnectResult(null, -1, perPortErrors.joinToString("; "))
    }

    /**
     * Emails each [slips] entry over a single shared SMTP connection: connect once, then loop
     * `transport.sendMessage(...)` per student. Never throws for a single bad recipient — that
     * student is recorded in [EmailSendResult.failures] and the batch continues. If the transport
     * drops mid-batch, one reconnect (on the same port) is attempted and that student's send is
     * retried once; further drops without a successful reconnect just fail the remaining
     * students rather than reopening the connection over and over. If no candidate port can be
     * connected to at all, every slip fails with the combined per-port error.
     */
    suspend fun send(config: EmailConfig, slips: List<SlipMail>): EmailSendResult =
        withContext(Dispatchers.IO) {
            if (slips.isEmpty()) return@withContext EmailSendResult(0, emptyList())

            val initial = connectAny(config)
            if (initial.transport == null) {
                return@withContext EmailSendResult(0, slips.map { it.studentName to initial.error!! })
            }

            var transport: Transport = initial.transport!!
            val port = initial.port
            val failures = mutableListOf<Pair<String, String>>()
            var sent = 0
            var reconnectAllowanceUsed = false

            try {
                for (slip in slips) {
                    val subject = "Your PreBoard Examination Result Slip"
                    val body = "Hello ${slip.studentName},\n\n" +
                        "Attached is your PreBoard examination result slip.\n\n" +
                        "This is an automated message; please contact your instructor with any questions."

                    fun buildAndSend(t: Transport) {
                        val msg = buildMessage(session(config, port), config, slip.recipient, subject, body, slip.file)
                        msg.saveChanges()
                        t.sendMessage(msg, msg.allRecipients)
                    }

                    try {
                        buildAndSend(transport)
                        sent++
                    } catch (e: Exception) {
                        if (transport.isConnected) {
                            // Connection is still alive; this student's send itself failed.
                            failures.add(slip.studentName to fullMessage(e))
                        } else if (!reconnectAllowanceUsed) {
                            reconnectAllowanceUsed = true
                            val (reconnected, reconnectError) = openTransport(config, port)
                            if (reconnected != null) {
                                transport = reconnected
                                try {
                                    buildAndSend(transport)
                                    sent++
                                } catch (retryException: Exception) {
                                    failures.add(slip.studentName to fullMessage(retryException))
                                }
                            } else {
                                failures.add(slip.studentName to fullMessage(reconnectError ?: e))
                            }
                        } else {
                            failures.add(slip.studentName to fullMessage(e))
                        }
                    }
                }
            } finally {
                runCatching { transport.close() }
            }

            EmailSendResult(sent, failures)
        }

    /** Sends a single test email to the configured from-address; returns null on success, else the error. */
    suspend fun sendTest(config: EmailConfig): String? = withContext(Dispatchers.IO) {
        val result = connectAny(config)
        val transport = result.transport ?: return@withContext result.error
        try {
            val msg = buildMessage(
                session(config, result.port), config, config.fromAddress,
                subject = "PreBoard test email",
                body = "This is a test email from the PreBoard Exam Checker app. Your email settings work.",
                attachment = null,
            )
            msg.saveChanges()
            transport.sendMessage(msg, msg.allRecipients)
            null
        } catch (e: Exception) {
            fullMessage(e)
        } finally {
            runCatching { transport.close() }
        }
    }
}
