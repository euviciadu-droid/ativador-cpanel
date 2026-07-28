package app.tvdigital.ativador

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Guardião de licença — vigia expiração/bloqueio remoto e apaga tudo
 * (arquivos, dados do UniTV e o próprio UniTV) quando a licença cai.
 * Compartilhado pelas duas variantes (Ativador Digital e Gerador UniTvFree).
 */
object LicenseGuard {
    private const val PREFS = "license_guard"
    private const val K_LAST_CONTACT = "last_contact_ms"
    private const val K_MAX_OFFLINE_DAYS = "max_offline_days"
    private const val K_CODE_EXPIRES_AT = "code_expires_ms" // 0 = vitalício
    private const val K_ACTIVATION_CODE = "activation_code"
    private const val K_UNITV_PKG = "unitv_pkg"
    private const val K_MAX_SEEN = "max_seen_ms" // proteção contra relógio pra trás

    private const val DEFAULT_MAX_OFFLINE_DAYS = 365
    private const val HEARTBEAT_INTERVAL_MS = 6L * 60L * 60L * 1000L // 6h

    @SuppressLint("HardwareIds")
    fun deviceId(ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun parseIso8601(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return try {
            val fmts = listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
            )
            for (f in fmts) {
                try {
                    val sdf = SimpleDateFormat(f, Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    return sdf.parse(iso)?.time ?: continue
                } catch (_: Exception) {}
            }
            0L
        } catch (_: Exception) { 0L }
    }

    /** Salva os dados vindos da ativação bem-sucedida. */
    fun saveFromActivation(
        ctx: Context,
        activationCode: String,
        codeExpiresAtIso: String?,
        maxOfflineDays: Int,
        unitvPackage: String?,
    ) {
        val now = System.currentTimeMillis()
        val exp = parseIso8601(codeExpiresAtIso)
        prefs(ctx).edit()
            .putString(K_ACTIVATION_CODE, activationCode)
            .putLong(K_LAST_CONTACT, now)
            .putLong(K_MAX_SEEN, maxOf(now, prefs(ctx).getLong(K_MAX_SEEN, 0L)))
            .putInt(K_MAX_OFFLINE_DAYS, if (maxOfflineDays > 0) maxOfflineDays else DEFAULT_MAX_OFFLINE_DAYS)
            .putLong(K_CODE_EXPIRES_AT, exp)
            .putString(K_UNITV_PKG, unitvPackage?.takeIf { it.isNotBlank() } ?: "com.integration.unitvsiptv")
            .apply()
    }

    /** Atualiza campos após heartbeat OK. */
    fun saveFromHeartbeat(ctx: Context, codeExpiresAtIso: String?, maxOfflineDays: Int) {
        val now = System.currentTimeMillis()
        val exp = if (codeExpiresAtIso.isNullOrBlank()) 0L else parseIso8601(codeExpiresAtIso)
        val e = prefs(ctx).edit()
            .putLong(K_LAST_CONTACT, now)
            .putLong(K_MAX_SEEN, maxOf(now, prefs(ctx).getLong(K_MAX_SEEN, 0L)))
        if (maxOfflineDays > 0) e.putInt(K_MAX_OFFLINE_DAYS, maxOfflineDays)
        if (exp > 0L) e.putLong(K_CODE_EXPIRES_AT, exp)
        e.apply()
    }

    fun hasLicense(ctx: Context): Boolean =
        !prefs(ctx).getString(K_ACTIVATION_CODE, "").isNullOrBlank()

    /** Código de ativação atualmente salvo (vazio se nunca ativou). */
    fun currentCode(ctx: Context): String =
        prefs(ctx).getString(K_ACTIVATION_CODE, "") ?: ""

    /**
     * Checa se está expirado, offline demais ou com relógio pra trás.
     * Se sim, dispara wipe silencioso e retorna true.
     */
    fun checkAndWipeIfNeeded(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (p.getString(K_ACTIVATION_CODE, "").isNullOrBlank()) return false // nunca ativou

        val now = System.currentTimeMillis()
        val last = p.getLong(K_LAST_CONTACT, 0L)
        val maxSeen = p.getLong(K_MAX_SEEN, 0L)
        val offlineDays = p.getInt(K_MAX_OFFLINE_DAYS, DEFAULT_MAX_OFFLINE_DAYS)
        val exp = p.getLong(K_CODE_EXPIRES_AT, 0L)

        // Proteção contra retrocesso de relógio (> 7 dias): trata como violação
        if (maxSeen > 0 && now + 7L * 86400_000L < maxSeen) {
            silentWipe(ctx)
            return true
        }

        // Expiração dura pelo painel
        if (exp > 0L && now > exp) {
            silentWipe(ctx)
            return true
        }

        // Offline por tempo demais
        if (last > 0L) {
            val diff = now - last
            val limit = offlineDays.toLong() * 86400_000L
            if (diff > limit) {
                silentWipe(ctx)
                return true
            }
        }
        return false
    }

    /** Dispara o heartbeat em background — não bloqueia UI. */
    fun triggerHeartbeat(ctx: Context, variant: String, versionName: String) {
        val p = prefs(ctx)
        val code = p.getString(K_ACTIVATION_CODE, "") ?: ""
        if (code.isBlank()) return
        val last = p.getLong(K_LAST_CONTACT, 0L)
        val now = System.currentTimeMillis()
        if (now - last < HEARTBEAT_INTERVAL_MS / 2) return // já bateu recente
        val did = deviceId(ctx)
        val appCtx = ctx.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val r = Api.heartbeat(appCtx, did, code, variant, versionName)
                if (r.wipe) {
                    silentWipe(appCtx)
                } else {
                    saveFromHeartbeat(appCtx, r.codeExpiresAt, r.maxOfflineDays)
                }
            } catch (_: Exception) {
                // rede falhou; check offline vai cuidar
            }
        }
    }

    /** Wipe silencioso: apaga arquivos, tenta limpar dados do UniTV e dispara desinstalação. */
    fun silentWipe(ctx: Context) {
        val p = prefs(ctx)
        val pkg = p.getString(K_UNITV_PKG, "com.integration.unitvsiptv")
            ?: "com.integration.unitvsiptv"

        // 1) tenta limpar dados do UniTV (só funciona com root em TV boxes)
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm clear $pkg"))
            proc.waitFor()
        } catch (_: Exception) {}

        // 2) apaga .config, .properties, /Alarms
        try {
            val root = Environment.getExternalStorageDirectory()
            if (root != null) {
                val targets = listOf(
                    File(root, ".config"),
                    File(root, ".properties"),
                    File(root, "Android/.config"),
                    File(root, "Alarms"),
                )
                for (f in targets) {
                    try {
                        if (f.exists()) {
                            if (f.isDirectory) f.deleteRecursively() else f.delete()
                        }
                    } catch (_: Exception) {}
                }
                // fallback com root
                try {
                    val cmd = targets.joinToString(" && ") { "rm -rf \"${it.absolutePath}\"" }
                    Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // 3) dispara desinstalação do UniTV
        try {
            val installed = try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
            if (installed) {
                val i = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                    data = Uri.parse("package:$pkg")
                    putExtra(Intent.EXTRA_RETURN_RESULT, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { ctx.startActivity(i) } catch (_: Exception) {
                    val d = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                    d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { ctx.startActivity(d) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        // 4) apaga estado local — próxima abertura será "sem licença"
        prefs(ctx).edit().clear().apply()
    }

    fun currentVersionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0"
    } catch (_: Exception) { "0.0.0" }

    /** Retorna "tvdigital" ou "unitvfree" baseado no BuildConfig applicationId. */
    fun variantFromPackage(ctx: Context): String {
        val p = ctx.packageName.lowercase(Locale.US)
        return when {
            p.contains("unitvfree") -> "unitvfree"
            else -> "tvdigital"
        }
    }
}
