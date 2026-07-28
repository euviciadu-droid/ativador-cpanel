package app.tvdigital.ativador

import android.content.Context

/**
 * Configuração de conexão com o painel — digitada pelo usuário via menu
 * secreto 555555 e guardada em SharedPreferences.
 *
 *  - panelUrl : URL raiz do painel (ex.: https://meusite.com.br)
 *  - panelCode: código de 6 dígitos gerado no painel (apk_config.codigo_conexao)
 *
 * Sem esses dois valores preenchidos e validados o app se recusa a ativar.
 */
object PanelConfig {
    private const val PREFS = "panel_config"
    private const val K_URL = "panel_url"
    private const val K_CODE = "panel_code"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getUrl(ctx: Context): String =
        (prefs(ctx).getString(K_URL, "") ?: "").trim().trimEnd('/')

    fun getCode(ctx: Context): String =
        (prefs(ctx).getString(K_CODE, "") ?: "").trim()

    fun isConfigured(ctx: Context): Boolean =
        getUrl(ctx).isNotBlank() && getCode(ctx).length == 6

    fun save(ctx: Context, url: String, code: String) {
        val cleanUrl = url.trim().trimEnd('/')
        val cleanCode = code.trim()
        prefs(ctx).edit()
            .putString(K_URL, cleanUrl)
            .putString(K_CODE, cleanCode)
            .apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
