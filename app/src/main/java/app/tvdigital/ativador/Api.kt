package app.tvdigital.ativador

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP para o painel PHP hospedado no cPanel do cliente.
 *
 * A URL do painel e o código de conexão são configurados pelo usuário no menu
 * secreto 555555 e ficam guardados em PanelConfig.  Todas as chamadas
 * carregam o codigo_painel para o servidor validar antes de responder.
 */
object Api {

    private val tlsSpecs = listOf(
        ConnectionSpec.MODERN_TLS,
        ConnectionSpec.COMPATIBLE_TLS,
    )

    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionSpecs(tlsSpecs)
        .build()

    private val dohCloudflare: Dns by lazy {
        DnsOverHttps.Builder()
            .client(dohClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1"),
            )
            .build()
    }

    private val dohGoogle: Dns by lazy {
        DnsOverHttps.Builder()
            .client(dohClient)
            .url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("8.8.4.4"),
            )
            .build()
    }

    private val resilientDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val errors = mutableListOf<Exception>()
            val resolved = linkedMapOf<String, InetAddress>()
            for (resolver in listOf(Dns.SYSTEM, dohCloudflare, dohGoogle)) {
                try {
                    val r = resolver.lookup(hostname)
                    for (a in r) resolved[a.hostAddress] = a
                } catch (e: Exception) { errors += e }
            }
            if (resolved.isNotEmpty()) {
                return resolved.values.sortedWith(
                    compareBy<InetAddress> { if (it.hostAddress.contains(":")) 1 else 0 }
                        .thenBy { it.hostAddress }
                )
            }
            throw UnknownHostException(
                "Falha ao resolver $hostname: ${errors.joinToString { it.message ?: it.javaClass.simpleName }}"
            )
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .dns(resilientDns)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectionSpecs(tlsSpecs)
        .build()

    fun http(): OkHttpClient = client

    // -------------------------------------------------------------------
    //  Endpoints
    // -------------------------------------------------------------------

    private fun activateUrl(base: String) = "$base/api/public/activate.php"
    private fun heartbeatUrl(base: String) = "$base/api/public/heartbeat.php"
    private fun messagesUrl(base: String) = "$base/api/public/messages.php"

    private fun requireConfig(ctx: Context): Pair<String, String> {
        val url = PanelConfig.getUrl(ctx)
        val code = PanelConfig.getCode(ctx)
        if (url.isBlank() || code.length != 6) {
            throw RuntimeException(
                "Este APK ainda não foi vinculado a um painel. Digite 555555 e informe o endereço do painel + código de conexão."
            )
        }
        return url to code
    }

    // -------------------------------------------------------------------
    //  DTOs
    // -------------------------------------------------------------------

    data class ExtractZip(val name: String, val url: String)

    data class ActivateResult(
        val success: Boolean,
        val message: String,
        val configContent: String?,
        val configFileUrl: String?,
        val configFileName: String?,
        val configSavePath: String?,
        val extractZips: List<ExtractZip>,
        val unitvApkUrl: String?,
        val unitvApkName: String?,
        val unitvPackageName: String?,
        val expiresAt: String?,
        val codeExpiresAt: String?,
        val maxOfflineDays: Int,
        val serverTime: String?,
    )

    data class HeartbeatResult(
        val ok: Boolean,
        val wipe: Boolean,
        val codeExpiresAt: String?,
        val maxOfflineDays: Int,
        val serverTime: String?,
    )

    data class RemoteMessage(val id: String, val text: String)

    // -------------------------------------------------------------------
    //  Ativação
    // -------------------------------------------------------------------

    fun activate(
        ctx: Context,
        code: String,
        deviceId: String,
        deviceOs: String,
        appVariant: String = "tvdigital",
        appVersion: String = "",
    ): ActivateResult {
        val (base, panelCode) = requireConfig(ctx)
        val body = JSONObject().apply {
            put("action", "activate")
            put("activation_code", code)
            put("codigo_painel", panelCode)
            put("device_id", deviceId)
            put("device_os", deviceOs)
            put("app_variant", appVariant)
            put("app_version", appVersion)
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder().url(activateUrl(base)).post(body).build()
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}: ${txt.take(160)}")
            }
            val j = if (txt.isNotEmpty()) JSONObject(txt) else JSONObject()
            val zips = mutableListOf<ExtractZip>()
            j.optJSONArray("extract_zips")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val z = arr.optJSONObject(i) ?: continue
                    val url = z.optString("url")
                    val name = z.optString("name")
                    if (url.isNotEmpty()) zips += ExtractZip(name, url)
                }
            }
            if (zips.isEmpty()) {
                val fallbackUrl = listOf(
                    j.optString("extract_zip_url"),
                    j.optString("zip_url"),
                    j.optString("zip_file_url"),
                ).firstOrNull { it.isNotBlank() }.orEmpty()
                val fallbackName = j.optString("zip_file_name")
                    .ifBlank { j.optString("zip_filename") }
                    .ifBlank { "ativacao.zip" }
                if (fallbackUrl.isNotBlank()) zips += ExtractZip(fallbackName, fallbackUrl)
            }
            val unitvApkUrl = listOf(
                j.optString("unitv_apk_url"),
                j.optString("apk_url"),
                j.optString("download_url"),
            ).firstOrNull { it.isNotBlank() }.orEmpty()
            return ActivateResult(
                success = j.optBoolean("success", false),
                message = j.optString("message", "Sem resposta"),
                configContent = j.optString("config_content").ifEmpty { null },
                configFileUrl = j.optString("config_file_url").ifEmpty { null },
                configFileName = j.optString("config_file_name").ifEmpty { null },
                configSavePath = j.optString("config_save_path").ifEmpty { "Android/.config" },
                extractZips = zips,
                unitvApkUrl = unitvApkUrl.ifEmpty { null },
                unitvApkName = j.optString("unitv_apk_name").ifBlank { "unitv.apk" },
                unitvPackageName = j.optString("unitv_package_name").ifBlank { null },
                expiresAt = j.optString("expires_at").ifEmpty { null },
                codeExpiresAt = j.optString("code_expires_at").ifEmpty { null },
                maxOfflineDays = j.optInt("max_offline_days", 365),
                serverTime = j.optString("server_time").ifEmpty { null },
            )
        }
    }

    // -------------------------------------------------------------------
    //  Heartbeat
    // -------------------------------------------------------------------

    fun heartbeat(ctx: Context, deviceId: String, code: String, appVariant: String, appVersion: String): HeartbeatResult {
        val (base, panelCode) = requireConfig(ctx)
        val body = JSONObject().apply {
            put("action", "heartbeat")
            put("activation_code", code)
            put("codigo_painel", panelCode)
            put("device_id", deviceId)
            put("app_variant", appVariant)
            put("app_version", appVersion)
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder().url(heartbeatUrl(base)).post(body).build()
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val j = if (txt.isNotEmpty()) JSONObject(txt) else JSONObject()
            return HeartbeatResult(
                ok = j.optBoolean("ok", false),
                wipe = j.optBoolean("wipe", false),
                codeExpiresAt = j.optString("code_expires_at").ifEmpty { null },
                maxOfflineDays = j.optInt("max_offline_days", 365),
                serverTime = j.optString("server_time").ifEmpty { null },
            )
        }
    }

    // Overload sem contexto (compat) — cai em falha se PanelConfig faltar
    fun heartbeat(deviceId: String, code: String, appVariant: String, appVersion: String): HeartbeatResult {
        throw RuntimeException("Use heartbeat(ctx, ...) — PanelConfig requerido.")
    }

    // -------------------------------------------------------------------
    //  Mensagens remotas
    // -------------------------------------------------------------------

    fun fetchMessages(ctx: Context, deviceId: String, appVariant: String): List<RemoteMessage> {
        val (base, panelCode) = requireConfig(ctx)
        val url = messagesUrl(base).toHttpUrl().newBuilder()
            .addQueryParameter("codigo_painel", panelCode)
            .addQueryParameter("device_id", deviceId)
            .addQueryParameter("app_variant", appVariant)
            .build()
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val txt = resp.body?.string().orEmpty()
            val j = if (txt.isNotEmpty()) JSONObject(txt) else JSONObject()
            val arr = j.optJSONArray("messages") ?: return emptyList()
            val out = mutableListOf<RemoteMessage>()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val id = m.optString("id"); val text = m.optString("text")
                if (id.isNotBlank() && text.isNotBlank()) out += RemoteMessage(id, text)
            }
            return out
        }
    }
}
