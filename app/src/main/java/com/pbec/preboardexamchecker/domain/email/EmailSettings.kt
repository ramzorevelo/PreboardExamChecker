package com.pbec.preboardexamchecker.domain.email

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** SMTP provider presets so the instructor never has to know host/port (except "Other"). */
enum class EmailProvider(val label: String, val host: String, val port: Int) {
    // 465 (implicit SSL) rather than 587 (STARTTLS): some networks (mobile carriers, public/school
    // Wi-Fi) intercept or strip the STARTTLS upgrade mid-handshake, surfacing as "Could not convert
    // socket to TLS: Socket is closed". Port 465 is TLS from the first byte, so there's no plaintext
    // negotiation for a middlebox to interfere with.
    GMAIL("Gmail", "smtp.gmail.com", 465),
    YAHOO("Yahoo", "smtp.mail.yahoo.com", 465),
    // Office 365 only serves STARTTLS on 587 — it has no implicit-SSL listener.
    OUTLOOK("Outlook", "smtp.office365.com", 587),
    OTHER("Other", "", 0),
}

/**
 * The instructor's SMTP config for emailing result slips. The account is their own; the app password
 * is stored in [EncryptedSharedPreferences] (encrypted at rest, never hardcoded in the APK).
 */
data class EmailConfig(
    val provider: EmailProvider = EmailProvider.GMAIL,
    val fromAddress: String = "",
    val senderName: String = "",
    val password: String = "",
    // Only used when provider == OTHER.
    val customHost: String = "",
    val customPort: Int = 587,
) {
    val host: String get() = if (provider == EmailProvider.OTHER) customHost else provider.host
    val port: Int get() = if (provider == EmailProvider.OTHER) customPort else provider.port
    /** Ready to send once we have a from-address, a password, and a resolvable host. */
    val isConfigured: Boolean
        get() = fromAddress.isNotBlank() && password.isNotBlank() && host.isNotBlank() && port > 0
}

object EmailSettings {
    private const val PREFS = "email_prefs"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_FROM = "from_address"
    private const val KEY_SENDER_NAME = "sender_name"
    private const val KEY_PASSWORD = "app_password"
    private const val KEY_HOST = "custom_host"
    private const val KEY_PORT = "custom_port"

    // EncryptedSharedPreferences.create() is comparatively expensive, so reuse one instance.
    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences = cached ?: synchronized(this) {
        cached ?: run {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also { cached = it }
        }
    }

    fun load(context: Context): EmailConfig {
        val p = prefs(context)
        val provider = runCatching { EmailProvider.valueOf(p.getString(KEY_PROVIDER, null) ?: "GMAIL") }
            .getOrDefault(EmailProvider.GMAIL)
        return EmailConfig(
            provider = provider,
            fromAddress = p.getString(KEY_FROM, "").orEmpty(),
            senderName = p.getString(KEY_SENDER_NAME, "").orEmpty(),
            password = p.getString(KEY_PASSWORD, "").orEmpty(),
            customHost = p.getString(KEY_HOST, "").orEmpty(),
            customPort = p.getInt(KEY_PORT, 587),
        )
    }

    fun save(context: Context, config: EmailConfig) {
        prefs(context).edit()
            .putString(KEY_PROVIDER, config.provider.name)
            .putString(KEY_FROM, config.fromAddress.trim())
            .putString(KEY_SENDER_NAME, config.senderName.trim())
            .putString(KEY_PASSWORD, config.password)
            .putString(KEY_HOST, config.customHost.trim())
            .putInt(KEY_PORT, config.customPort)
            .apply()
    }

    fun isConfigured(context: Context): Boolean = load(context).isConfigured
}
