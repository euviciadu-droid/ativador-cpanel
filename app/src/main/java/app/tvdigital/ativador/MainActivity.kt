package app.tvdigital.ativador

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security

class MainActivity : AppCompatActivity() {

    private lateinit var inputCode: EditText
    private lateinit var btnActivate: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    private var pollJob: Job? = null
    private val shownMessageIds = mutableSetOf<String>()

    /** Código digitado antes de ir para a tela de permissão. Ao voltar, se a
     *  permissão foi concedida, o app dispara a ativação sozinho. */
    private var pendingCode: String? = null
    private var isActivating = false
    private var updateChecked = false

    private var isTv = false

    companion object {
        private const val REQ_UNINSTALL_UNITV = 5011
        private const val REQ_ALL_FILES_ACCESS = 5012
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installModernTlsProvider()
        setContentView(R.layout.activity_main)
        inputCode = findViewById(R.id.inputCode)
        btnActivate = findViewById(R.id.btnActivate)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        btnActivate.setOnClickListener { onActivateClick() }
        inputCode.setOnEditorActionListener { _, actionId, event ->
            // Aceita qualquer ação do teclado (IR, OK, Concluído, Buscar, Próximo…)
            // e Enter/OK do controle remoto — todos disparam ATIVAR quando
            // o código tem 6 dígitos.
            val isImeAction = actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_NEXT ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_UNSPECIFIED
            val isEnter = event != null && (
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            ) && event.action == KeyEvent.ACTION_DOWN
            if (isImeAction || isEnter) {
                onActivateClick()
                true
            } else {
                false
            }
        }

        // Modo TV Box / Android TV: sem teclado virtual, entrada por D-pad
        isTv = TvMode.isTv(this)
        if (isTv) {
            TvMode.setupCodeInputForTv(inputCode)
            status.text = "Controle remoto: números digitam, ←→ navega, ↑↓ altera e OK/IR ativa. MENU diagnóstico."
        }

        // Guardião: se a licença expirou / offline demais / relógio pra trás → wipe silencioso
        try { LicenseGuard.checkAndWipeIfNeeded(this) } catch (_: Exception) {}
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isTv) {
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                showDiagnosticDialog(); return true
            }
            if (TvMode.handleTvKey(keyCode, event, inputCode) { onActivateClick() }) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showDiagnosticDialog() {
        val perm = if (hasStoragePermission()) "OK" else "faltando"
        val install = if (Installer.canInstallUnknownApps(this)) "OK" else "faltando"
        val msg = buildString {
            append("Fabricante: ${Build.MANUFACTURER}\n")
            append("Modelo: ${Build.MODEL}\n")
            append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("APK: ${LicenseGuard.currentVersionName(this@MainActivity)}\n")
            append("Modo TV: ${if (isTv) "sim" else "não"}\n")
            append("Permissão arquivos: $perm\n")
            append("Instalar desconhecidos: $install")
        }
        AlertDialog.Builder(this).setTitle("Diagnóstico").setMessage(msg)
            .setPositiveButton("OK", null).show()
    }

    private fun resetTerminal() {}

    private fun log(line: String, color: String = "#4ADE80") {}

    private fun installModernTlsProvider() {
        try {
            if (Security.getProvider("Conscrypt") == null) {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            }
        } catch (_: Exception) {
            // Se o provedor não carregar, o Android usa o TLS padrão do aparelho.
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionHint()
        startMessagePolling()

        // Guardião: verifica antes de qualquer ação
        try {
            if (LicenseGuard.checkAndWipeIfNeeded(this)) {
                setBusy(false, "Licença encerrada. Contate o suporte.")
                return
            }
            LicenseGuard.triggerHeartbeat(this, "tvdigital", LicenseGuard.currentVersionName(this))
        } catch (_: Exception) {}

        // Verifica atualização do próprio ativador uma vez por sessão.
        if (!updateChecked) {
            updateChecked = true
            CoroutineScope(Dispatchers.IO).launch { checkForUpdate() }
        }

        // Se o usuário concedeu a permissão e voltou do Settings, retoma a
        // ativação automaticamente com o código já digitado — sem precisar
        // voltar manualmente e apertar ATIVAR de novo.
        val code = pendingCode
        if (code != null && hasStoragePermission() && !isActivating) {
            pendingCode = null
            runActivation(code)
        }
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
        pollJob = null
    }

    private fun startMessagePolling() {
        pollJob?.cancel()
        val did = deviceId()
        pollJob = CoroutineScope(Dispatchers.IO).launch {
            val pendingAck = mutableListOf<String>()
            while (isActive) {
                try {
                    val code = LicenseGuard.currentCode(this@MainActivity)
                    if (code.isBlank()) { delay(3000L); continue }
                    val messages = Api.fetchMessages(this@MainActivity, did, "tvdigital")
                    pendingAck.clear()
                    if (messages.isNotEmpty()) {
                        val newOnes = messages.filter { shownMessageIds.add(it.id) }
                        pendingAck.addAll(messages.map { it.id })
                        if (newOnes.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                newOnes.forEach { showRemoteMessage(it.text) }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // silencioso: rede pode oscilar
                }
                delay(3000L)
            }
        }
    }

    private fun showRemoteMessage(text: String) {
        if (isFinishing || isDestroyed) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Mensagem do suporte")
            .setMessage(text)
            .setCancelable(false)
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }

    private fun hasStoragePermission(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                Environment.isExternalStorageManager()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            else -> true
        }
    }

    private fun updatePermissionHint() {
        if (!hasStoragePermission() && pendingCode == null && !isActivating) {
            status.text = if (isTv) {
                "Digite com o controle: números, ←→ navega, ↑↓ altera e OK/IR ativa."
            } else {
                "Digite o código e toque em ATIVAR ou IR no teclado."
            }
        }
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivityForResult(intent, REQ_ALL_FILES_ACCESS)
            } catch (_: Exception) {
                try {
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                        REQ_ALL_FILES_ACCESS,
                    )
                } catch (_: Exception) {
                    val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    i.data = Uri.parse("package:$packageName")
                    startActivityForResult(i, REQ_ALL_FILES_ACCESS)
                }
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                42,
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_UNINSTALL_UNITV -> {
                val stillThere = try { packageManager.getPackageInfo("com.integration.unitvsiptv", 0); true }
                catch (_: Exception) { false }
                setBusy(false, if (stillThere) "UniTV ainda instalado — tente de novo." else "Limpeza concluída. UniTV removido.")
            }
            REQ_ALL_FILES_ACCESS -> {
                if (hasStoragePermission()) {
                    val code = pendingCode
                    if (code != null && !isActivating) { pendingCode = null; runActivation(code) }
                } else {
                    setBusy(false, "Permissão negada. Ative \"gerenciar todos os arquivos\" e volte.")
                }
            }
        }
    }

    /** Guarda o código pendente e leva o usuário direto ao switch de permissão.
     *  Ao voltar, onResume detecta a permissão e continua sozinho. */
    private fun promptPermissionForCode(code: String) {
        pendingCode = code
        Toast.makeText(
            this,
            "Ative \"Permitir gerenciar todos os arquivos\" e volte ao ativador.",
            Toast.LENGTH_LONG,
        ).show()
        setBusy(false, "Aguardando permissão… volte ao ativador após conceder.")
        openAllFilesAccessSettings()
    }

    @SuppressLint("HardwareIds")
    private fun deviceId(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    private fun setBusy(busy: Boolean, msg: String? = null, percent: Int? = null) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy && percent != null) {
            progress.isIndeterminate = false
            progress.progress = percent.coerceIn(0, 100)
        } else if (busy) {
            progress.isIndeterminate = true
        } else {
            progress.isIndeterminate = true
            progress.progress = 0
        }
        btnActivate.isEnabled = !busy
        if (msg != null) status.text = msg
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            val view = currentFocus ?: inputCode
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
            inputCode.clearFocus()
        } catch (_: Exception) {}
    }

    private fun onActivateClick() {
        hideKeyboard()
        val code = inputCode.text.toString().trim()
        if (code.length != 6 || !code.all { it.isDigit() }) {
            Toast.makeText(this, "Digite o código de 6 dígitos", Toast.LENGTH_SHORT).show()
            return
        }
        // Código secreto 555555: abre o painel de vínculo (URL do painel + código de conexão)
        if (code == "555555") {
            inputCode.setText("")
            showPanelBindDialog()
            return
        }
        // Código secreto 999999: abre menu de manutenção (limpar tudo)
        if (code == "999999") {
            inputCode.setText("")
            showSecretMenu()
            return
        }
        // Sem painel configurado: força o usuário a digitar 555555 primeiro
        if (!PanelConfig.isConfigured(this)) {
            AlertDialog.Builder(this)
                .setTitle("Painel não vinculado")
                .setMessage("Este APK ainda não foi vinculado a um painel.\n\nDigite 555555 no campo do código para informar o endereço do painel e o código de conexão de 6 dígitos.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        if (!hasStoragePermission()) {
            promptPermissionForCode(code)
            return
        }
        runActivation(code)
    }

    private fun showSecretMenu() {
        AlertDialog.Builder(this)
            .setTitle("Manutenção")
            .setMessage("Isso vai apagar .config, .properties, /Alarms, limpar os dados do UniTV e desinstalá-lo. Continuar?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("LIMPAR TUDO") { _, _ -> doCleanAll() }
            .show()
    }

    /** Menu secreto 555555 — vincula o APK ao painel do cPanel. */
    private fun showPanelBindDialog() {
        val ctx = this
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val urlInput = EditText(ctx).apply {
            hint = "URL do painel (ex.: https://meusite.com.br)"
            setText(PanelConfig.getUrl(ctx))
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val codeInput = EditText(ctx).apply {
            hint = "Código de conexão (6 dígitos)"
            setText(PanelConfig.getCode(ctx))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        container.addView(urlInput)
        container.addView(codeInput)

        AlertDialog.Builder(ctx)
            .setTitle("Vincular ao painel")
            .setMessage("Digite o endereço do seu painel e o código de conexão de 6 dígitos gerado nele (Configurações APK → Códigos).")
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Limpar") { _, _ ->
                PanelConfig.clear(ctx)
                Toast.makeText(ctx, "Vínculo removido.", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Salvar") { _, _ ->
                val url = urlInput.text.toString().trim()
                val code = codeInput.text.toString().trim()
                if (!url.startsWith("http")) {
                    Toast.makeText(ctx, "URL inválida — precisa começar com http:// ou https://", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (code.length != 6 || !code.all { it.isDigit() }) {
                    Toast.makeText(ctx, "Código precisa ter 6 dígitos numéricos.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                PanelConfig.save(ctx, url, code)
                Toast.makeText(ctx, "Painel vinculado. Agora digite a licença para ativar.", Toast.LENGTH_LONG).show()
            }
            .show()
    }


    private fun tryRootDeletePaths(paths: List<String>): Boolean {
        return try {
            val cmd = paths.joinToString(" && ") { "rm -rf \"$it\"" }
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            p.waitFor() == 0
        } catch (_: Exception) { false }
    }

    private fun tryRootClearData(pkg: String): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm clear $pkg"))
            p.waitFor() == 0
        } catch (_: Exception) { false }
    }

    private fun doCleanAll() {
        if (!hasStoragePermission()) {
            Toast.makeText(this,
                "Ative \"Permitir gerenciar todos os arquivos\" para a limpeza funcionar.",
                Toast.LENGTH_LONG).show()
            openAllFilesAccessSettings()
            return
        }
        setBusy(true, "Limpando tudo.....")
        val pkg = "com.integration.unitvsiptv"

        CoroutineScope(Dispatchers.Main).launch {
            val clearedApp = withContext(Dispatchers.IO) { tryRootClearData(pkg) }
            val remaining = withContext(Dispatchers.IO) {
                val root = Environment.getExternalStorageDirectory()
                val failed = mutableListOf<String>()
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
                                val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                                if (!ok || f.exists()) failed.add(f.absolutePath)
                            }
                        } catch (_: Exception) { failed.add(f.absolutePath) }
                    }
                }
                if (failed.isNotEmpty()) {
                    val ok = tryRootDeletePaths(failed)
                    if (ok) emptyList() else failed.filter { File(it).exists() }
                } else emptyList()
            }

            val installed = try { packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
            if (installed) {
                try {
                    val i = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                        data = Uri.parse("package:$pkg")
                        putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    }
                    startActivityForResult(i, REQ_UNINSTALL_UNITV)
                } catch (_: Exception) {
                    try {
                        val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                        startActivityForResult(i, REQ_UNINSTALL_UNITV)
                    } catch (_: Exception) {}
                }
            }

            val parts = mutableListOf<String>()
            parts.add(if (clearedApp) "Dados do UniTV limpos" else "Dados do UniTV: abra Apps → UniTV → Limpar dados")
            parts.add(if (remaining.isEmpty()) "arquivos apagados" else "arquivos pendentes: ${remaining.size}")
            if (installed) parts.add("confirme a desinstalação")
            setBusy(false, parts.joinToString(" • "))
        }
    }

    private fun runActivation(code: String) {
        if (isActivating) return
        isActivating = true
        resetTerminal()
        log("> Iniciando Ativador TV Digital", "#22D3EE")
        log("> Validando código: $code")
        setBusy(true, "Ativando sistema…")
        val ctx = applicationContext
        val did = deviceId()
        val os = "Android ${Build.VERSION.RELEASE}"
        log("  device_id: $did")
        log("  android: $os")

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    Api.activate(this@MainActivity, code, did, os, "tvdigital", LicenseGuard.currentVersionName(this@MainActivity))
                }
                if (!result.success) {
                    log("  [ERRO] ${result.message}", "#F87171")
                    setBusy(false, result.message)
                    return@launch
                }
                log("  [OK] Código autorizado", "#4ADE80")
                result.expiresAt?.let { log("  válido até: $it") }
                // Salva o estado do guardião
                LicenseGuard.saveFromActivation(
                    applicationContext, code, result.codeExpiresAt, result.maxOfflineDays, result.unitvPackageName,
                )

                var savedConfigFile: File? = null
                val extractedDirs = mutableListOf<File>()

                if (!result.configFileUrl.isNullOrEmpty() && !result.configFileName.isNullOrEmpty()) {
                    log("> Baixando configuração ${result.configFileName}…", "#FACC15")
                    setBusy(true, "Ativando sistema…")
                    try {
                        savedConfigFile = withContext(Dispatchers.IO) {
                            Downloader.downloadConfig(
                                context = ctx,
                                url = result.configFileUrl,
                                savePath = result.configSavePath ?: "Android/.config",
                                fileName = result.configFileName,
                            )
                        }
                    } catch (downloadError: Exception) {
                        if (!result.configContent.isNullOrEmpty()) {
                            log("  [AVISO] download falhou; gravando configuração direta", "#FACC15")
                            savedConfigFile = withContext(Dispatchers.IO) {
                                Downloader.saveConfigContent(ctx, result.configContent)
                            }
                        } else {
                            throw downloadError
                        }
                    }
                    log("  [OK] salvo em ${savedConfigFile?.absolutePath}", "#4ADE80")
                    setBusy(true, "Ativando sistema…")
                } else if (!result.configContent.isNullOrEmpty()) {
                    val configContent = result.configContent
                    log("> Gravando configuração local…", "#FACC15")
                    setBusy(true, "Ativando sistema…")
                    savedConfigFile = withContext(Dispatchers.IO) {
                        Downloader.saveConfigContent(ctx, configContent)
                    }
                    log("  [OK] ${savedConfigFile?.absolutePath}", "#4ADE80")
                    setBusy(true, "Ativando sistema…")
                }

                if (result.extractZips.isNotEmpty()) {
                    for (zip in result.extractZips) {
                        try {
                            setBusy(true, "Ativando sistema…")
                            val zipFile = withContext(Dispatchers.IO) {
                                Downloader.downloadZipToCache(ctx, zip.url, zip.name)
                            }
                            setBusy(true, "Ativando sistema…")
                            val dest = withContext(Dispatchers.IO) {
                                Extractor.extractToRoot(ctx, zipFile)
                            }
                            extractedDirs += dest
                            try { zipFile.delete() } catch (_: Exception) {}
                            setBusy(true, "Ativando sistema…")
                        } catch (ex: Exception) {
                            log("  [ERRO] ${ex.message}", "#F87171")
                            setBusy(false, "Falha ao extrair ${zip.name}: ${ex.message}")
                            return@launch
                        }
                    }
                }

                val missing = mutableListOf<String>()
                if (savedConfigFile == null || savedConfigFile?.exists() != true || (savedConfigFile?.length() ?: 0L) <= 0L) {
                    missing += ".config"
                }
                extractedDirs.forEach { dir ->
                    if (!dir.exists() || !dir.isDirectory || dir.listFiles().isNullOrEmpty()) missing += dir.name
                }

                withContext(Dispatchers.IO) { Api.confirmInstall(code, did) }

                // Baixa e abre o instalador do APK do UniTV enviado no painel.
                val apkUrl = result.unitvApkUrl
                if (!apkUrl.isNullOrBlank()) {
                    try {
                        val apkName = result.unitvApkName ?: "unitv.apk"
                        setBusy(true, "Tudo ativado 100% — Baixando UniTV…", 0)
                        val apkFile = withContext(Dispatchers.IO) {
                            downloadApkTo(apkUrl, "unitv", apkName) { pct, mb, total ->
                                CoroutineScope(Dispatchers.Main).launch {
                                    setBusy(true, "Tudo ativado 100% — Baixando UniTV… $pct%", pct)
                                }
                            }
                        }
                        setBusy(false, "Tudo ativado 100% — Abrindo instalador do UniTV…")
                        openApkInstaller(apkFile)
                    } catch (ex: Exception) {
                        setBusy(false, "Tudo ativado 100% — falha ao baixar UniTV: ${ex.message}")
                        return@launch
                    }
                }

                if (missing.isEmpty()) {
                    setBusy(false, "Tudo ativado 100% ✓")
                } else {
                    log("  [ERRO] faltando: ${missing.joinToString(", ")}", "#F87171")
                    setBusy(false, "Erro na instalação: faltando ${missing.joinToString(", ")}")
                }

            } catch (e: Exception) {
                log("  [ERRO] ${e.message}", "#F87171")
                setBusy(false, "Erro: ${e.message}")
            } finally {
                isActivating = false
            }
        }
    }


    // ---------- Auto-update do próprio ativador ----------

    private fun currentVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
    } catch (_: Exception) { "0.0.0" }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").mapNotNull { it.toIntOrNull() }
        val pb = b.split(".").mapNotNull { it.toIntOrNull() }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun checkForUpdate() {
        try {
            val (remoteVer, apkUrl) = Api.fetchUpdateInfo()
            if (remoteVer.isBlank() || apkUrl.isBlank()) return
            // Atualiza sempre que a versão publicada for diferente da instalada,
            // assim reuploads no painel também disparam o download.
            if (compareVersions(remoteVer, currentVersionName()) == 0) return

            val apk = downloadApkTo(apkUrl, "update", "ativador-update.apk") { pct, mb, total ->
                CoroutineScope(Dispatchers.Main).launch {
                    val totalTxt = if (total > 0) "${"%.1f".format(total)}MB" else "?"
                    setBusy(true, "Baixando atualização $remoteVer… $pct%  (${"%.1f".format(mb)}/$totalTxt)", pct)
                }
            }
            CoroutineScope(Dispatchers.Main).launch {
                setBusy(false, "Atualização baixada. Abrindo instalador…")
                openApkInstaller(apk)
            }
        } catch (_: Exception) {
            // Auto-update é opcional: se DNS/rede falhar, não atrapalha a ativação.
        }
    }

    private fun downloadApkTo(
        url: String,
        subdir: String,
        fileName: String,
        onProgress: (pct: Int, downloadedMb: Double, totalMb: Double) -> Unit,
    ): File {
        val dir = File(externalCacheDir ?: cacheDir, subdir)
        if (!dir.exists()) dir.mkdirs()
        dir.listFiles()?.forEach { try { it.delete() } catch (_: Exception) {} }
        val safe = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifEmpty { "pack.apk" }
        val out = File(dir, safe)
        val req = Request.Builder().url(url).build()
        Api.http().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val body = resp.body ?: throw RuntimeException("Corpo vazio")
            val total = body.contentLength()
            val totalMb = if (total > 0) total / 1024.0 / 1024.0 else 0.0
            var downloaded = 0L
            var lastPct = -1
            java.io.FileOutputStream(out).use { fos ->
                val input = body.byteStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    fos.write(buf, 0, n)
                    downloaded += n
                    if (total > 0) {
                        val pct = ((downloaded * 100) / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct, downloaded / 1024.0 / 1024.0, totalMb)
                        }
                    } else {
                        onProgress(0, downloaded / 1024.0 / 1024.0, 0.0)
                    }
                }
            }
        }
        return out
    }




    private fun openApkInstaller(apk: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir instalador: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
