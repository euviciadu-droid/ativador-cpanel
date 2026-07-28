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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.conscrypt.Conscrypt
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.Security

/**
 * Tela do APK "Gerador UniTvFree" (variante vermelha).
 * Reutiliza a Api/Downloader/Extractor compartilhados em src/main.
 */
class UniTvFreeActivity : AppCompatActivity() {

    private lateinit var inputCode: EditText
    private lateinit var btnStep1: Button
    private lateinit var btnStep2: Button
    private lateinit var btnStep3: Button
    private lateinit var btnResetLicense: Button
    private lateinit var codeBlock: LinearLayout
    private lateinit var postBlock: LinearLayout
    private lateinit var secretBlock: LinearLayout
    private lateinit var btnCleanAll: Button
    private lateinit var txtLicense: TextView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    private var activatedCode: String? = null
    private var unitvPackageName: String? = null
    private var unitvApkUrl: String? = null
    private var unitvApkName: String? = null
    private var pendingCode: String? = null
    private var pendingApk: File? = null
    private var isBusy = false
    private var isTv = false

    companion object {
        private const val REQ_UNKNOWN_SOURCES = 4321
        private const val REQ_UNINSTALL_UNITV = 4322
        private const val REQ_ALL_FILES_ACCESS = 4323
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installModernTlsProvider()
        setContentView(R.layout.activity_unitv)

        inputCode = findViewById(R.id.inputCode)
        btnStep1 = findViewById(R.id.btnStep1)
        btnStep2 = findViewById(R.id.btnStep2)
        btnStep3 = findViewById(R.id.btnStep3)
        
        btnResetLicense = findViewById(R.id.btnResetLicense)
        codeBlock = findViewById(R.id.codeBlock)
        postBlock = findViewById(R.id.postBlock)
        secretBlock = findViewById(R.id.secretBlock)
        btnCleanAll = findViewById(R.id.btnCleanAll)
        txtLicense = findViewById(R.id.txtLicense)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        btnStep1.setOnClickListener { onStep1Click() }
        btnStep2.setOnClickListener { runInstallUnitv() }
        btnStep3.setOnClickListener { openUnitv() }
        
        btnResetLicense.setOnClickListener { resetLicense() }
        btnCleanAll.setOnClickListener { confirmCleanAll() }
        inputCode.setOnEditorActionListener { _, actionId, event ->
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
                onStep1Click()
                true
            } else {
                false
            }
        }

        // Modo TV Box: input sem teclado virtual, entrada via D-pad
        isTv = TvMode.isTv(this)
        if (isTv) {
            TvMode.setupCodeInputForTv(inputCode)
            status.text = "Controle: números digitam, ←→ navega, ↑↓ altera e OK/IR ativa. MENU diagnóstico."
        }

        // Guardião: se a licença expirou / offline demais / relógio pra trás → wipe silencioso
        try { LicenseGuard.checkAndWipeIfNeeded(this) } catch (_: Exception) {}
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isTv) {
            if (keyCode == KeyEvent.KEYCODE_MENU) { showDiagnosticDialog(); return true }
            if (codeBlock.visibility == View.VISIBLE &&
                TvMode.handleTvKey(keyCode, event, inputCode) { onStep1Click() }) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showDiagnosticDialog() {
        val perm = if (hasStoragePermission()) "OK" else "faltando"
        val install = if (Installer.canInstallUnknownApps(this)) "OK" else "faltando"
        val msg = "Fabricante: ${Build.MANUFACTURER}\n" +
            "Modelo: ${Build.MODEL}\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "APK: ${LicenseGuard.currentVersionName(this)}\n" +
            "Modo TV: ${if (isTv) "sim" else "não"}\n" +
            "Permissão arquivos: $perm\n" +
            "Instalar desconhecidos: $install"
        AlertDialog.Builder(this).setTitle("Diagnóstico").setMessage(msg)
            .setPositiveButton("OK", null).show()
    }

    override fun onResume() {
        super.onResume()
        try {
            if (LicenseGuard.checkAndWipeIfNeeded(this)) {
                setBusy(false, "Licença encerrada. Contate o suporte.")
                return
            }
            LicenseGuard.triggerHeartbeat(this, "unitvfree", LicenseGuard.currentVersionName(this))
        } catch (_: Exception) {}
        val code = pendingCode
        if (code != null && hasStoragePermission() && !isBusy) {
            pendingCode = null
            runActivation(code)
        }
        val apk = pendingApk
        if (apk != null && apk.exists() && Installer.canInstallUnknownApps(this) && !isBusy) {
            pendingApk = null
            setBusy(false, "Abrindo instalador do UniTV…")
            openApkInstaller(apk)
        }
    }


    private fun installModernTlsProvider() {
        try {
            if (Security.getProvider("Conscrypt") == null) {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("HardwareIds")
    private fun deviceId(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    private fun hasStoragePermission(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        else -> true
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
            ActivityCompat.requestPermissions(this,
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 42)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_UNINSTALL_UNITV -> {
                val pkg = unitvPackageName?.takeIf { it.isNotBlank() } ?: "com.integration.unitvsiptv"
                val still = try { packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
                setBusy(false, if (still) "UniTV ainda instalado — tente de novo." else "Limpeza concluída.")
            }
            REQ_ALL_FILES_ACCESS -> {
                if (hasStoragePermission()) {
                    val code = pendingCode
                    if (code != null && !isBusy) { pendingCode = null; runActivation(code) }
                } else {
                    setBusy(false, "Permissão negada. Ative \"gerenciar todos os arquivos\" e volte.")
                }
            }
        }
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            val view = currentFocus ?: inputCode
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
            inputCode.clearFocus()
        } catch (_: Exception) {}
    }

    private fun setBusy(busy: Boolean, msg: String? = null, percent: Int? = null) {
        isBusy = busy
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
        btnStep1.isEnabled = !busy
        btnStep2.isEnabled = !busy
        btnStep3.isEnabled = !busy
        
        btnResetLicense.isEnabled = !busy
        if (msg != null) status.text = msg
    }

    private fun onStep1Click() {
        hideKeyboard()
        val code = inputCode.text.toString().trim()
        if (code.length != 6 || !code.all { it.isDigit() }) {
            Toast.makeText(this, "Digite o código de 6 dígitos", Toast.LENGTH_SHORT).show()
            return
        }
        // 555555 — vincular painel
        if (code == "555555") {
            inputCode.setText("")
            showPanelBindDialog()
            return
        }
        // 999999 — modo manutenção
        if (code == "999999") {
            codeBlock.visibility = View.GONE
            postBlock.visibility = View.GONE
            secretBlock.visibility = View.VISIBLE
            status.text = "Modo manutenção. Toque em LIMPAR TUDO para apagar dados e desinstalar o UniTV."
            return
        }
        if (!PanelConfig.isConfigured(this)) {
            AlertDialog.Builder(this)
                .setTitle("Painel não vinculado")
                .setMessage("Digite 555555 para informar o endereço do painel e o código de conexão.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        if (!hasStoragePermission()) {
            pendingCode = code
            Toast.makeText(this,
                "Ative \"Permitir gerenciar todos os arquivos\" e volte ao ativador.",
                Toast.LENGTH_LONG).show()
            setBusy(false, "Aguardando permissão… volte ao ativador após conceder.")
            openAllFilesAccessSettings()
            return
        }
        runActivation(code)
    }

    /** Menu 555555 — vincula o APK ao painel. */
    private fun showPanelBindDialog() {
        val ctx = this
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val urlInput = EditText(ctx).apply {
            hint = "URL do painel (https://...)"
            setText(PanelConfig.getUrl(ctx))
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val codeInput = EditText(ctx).apply {
            hint = "Código de conexão (6 dígitos)"
            setText(PanelConfig.getCode(ctx))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        container.addView(urlInput); container.addView(codeInput)
        AlertDialog.Builder(ctx)
            .setTitle("Vincular ao painel")
            .setMessage("Endereço do painel + código de conexão de 6 dígitos gerado no painel.")
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
                    Toast.makeText(ctx, "URL precisa começar com http:// ou https://", Toast.LENGTH_LONG).show(); return@setPositiveButton
                }
                if (code.length != 6 || !code.all { it.isDigit() }) {
                    Toast.makeText(ctx, "Código de 6 dígitos numéricos.", Toast.LENGTH_LONG).show(); return@setPositiveButton
                }
                PanelConfig.save(ctx, url, code)
                Toast.makeText(ctx, "Painel vinculado.", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun confirmCleanAll() {
        AlertDialog.Builder(this)
            .setTitle("Limpar tudo")
            .setMessage("Isso vai apagar o .config, .properties, a pasta /Alarms, limpar os dados do UniTV e desinstalá-lo. Continuar?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Sim, limpar") { _, _ -> doCleanAll() }
            .show()
    }

    private fun tryRootDeletePaths(paths: List<String>): Boolean {
        return try {
            val cmd = paths.joinToString(" && ") { "rm -rf \"$it\"" }
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
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
        val pkg = unitvPackageName?.takeIf { it.isNotBlank() } ?: "com.integration.unitvsiptv"

        CoroutineScope(Dispatchers.Main).launch {
            // 1) pm clear no UniTV (requer root em TV boxes)
            val clearedApp = withContext(Dispatchers.IO) { tryRootClearData(pkg) }

            // 2) Apaga arquivos — tenta API normal e, se falhar, cai para root
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

            // 3) Desinstalar UniTV
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

            secretBlock.visibility = View.GONE
            codeBlock.visibility = View.VISIBLE
            inputCode.setText("")
        }
    }

    private fun runActivation(code: String) {
        if (isBusy) return
        setBusy(true, "Ativando licença…")
        val ctx = applicationContext
        val did = deviceId()
        val os = "Android ${Build.VERSION.RELEASE}"

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    Api.activate(this@UniTvFreeActivity, code, did, os, "unitvfree", LicenseGuard.currentVersionName(this@UniTvFreeActivity))
                }
                if (!result.success) {
                    setBusy(false, result.message)
                    return@launch
                }
                LicenseGuard.saveFromActivation(
                    applicationContext, code, result.codeExpiresAt, result.maxOfflineDays, result.unitvPackageName,
                )

                if (!result.configFileUrl.isNullOrEmpty() && !result.configFileName.isNullOrEmpty()) {
                    try {
                        withContext(Dispatchers.IO) {
                            Downloader.downloadConfig(
                                context = ctx,
                                url = result.configFileUrl,
                                savePath = result.configSavePath ?: "Android/.config",
                                fileName = result.configFileName,
                            )
                        }
                    } catch (_: Exception) {
                        if (!result.configContent.isNullOrEmpty()) {
                            withContext(Dispatchers.IO) {
                                Downloader.saveConfigContent(ctx, result.configContent)
                            }
                        }
                    }
                } else if (!result.configContent.isNullOrEmpty()) {
                    withContext(Dispatchers.IO) {
                        Downloader.saveConfigContent(ctx, result.configContent)
                    }
                }

                for (zip in result.extractZips) {
                    try {
                        val zf = withContext(Dispatchers.IO) {
                            Downloader.downloadZipToCache(ctx, zip.url, zip.name)
                        }
                        withContext(Dispatchers.IO) { Extractor.extractToRoot(ctx, zf) }
                        try { zf.delete() } catch (_: Exception) {}
                    } catch (ex: Exception) {
                        setBusy(false, "Falha ao extrair ${zip.name}: ${ex.message}")
                        return@launch
                    }
                }

                withContext(Dispatchers.IO) { Api.confirmInstall(code, did) }

                activatedCode = code
                unitvPackageName = result.unitvPackageName
                unitvApkUrl = result.unitvApkUrl
                unitvApkName = result.unitvApkName

                txtLicense.text = "Licença ativada: $code"
                codeBlock.visibility = View.GONE
                postBlock.visibility = View.VISIBLE
                setBusy(false, "Licença ativada. Siga os próximos passos.")
            } catch (e: Exception) {
                setBusy(false, "Erro: ${e.message}")
            }
        }
    }

    private fun runInstallUnitv() {
        val url = unitvApkUrl
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "APK do UniTV não configurado no painel.", Toast.LENGTH_LONG).show()
            return
        }
        setBusy(true, "Baixando UniTV…", 0)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    downloadApkTo(url, "unitv", unitvApkName ?: "unitv.apk") { pct, _, _ ->
                        CoroutineScope(Dispatchers.Main).launch {
                            setBusy(true, "Baixando UniTV… $pct%", pct)
                        }
                    }
                }
                setBusy(false, "Abrindo instalador do UniTV…")
                openApkInstaller(apk)
            } catch (ex: Exception) {
                setBusy(false, "Falha ao baixar UniTV: ${ex.message}")
            }
        }
    }

    private fun openUnitv() {
        val pkg = unitvPackageName
        if (pkg.isNullOrBlank()) {
            Toast.makeText(this, "Package do UniTV não configurado no painel.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            Toast.makeText(this, "UniTV ($pkg) não está instalado.", Toast.LENGTH_LONG).show()
        }
    }


    private fun tryRootClearData(pkg: String): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm clear $pkg"))
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
            val code = p.waitFor()
            code == 0 && out.trim().equals("Success", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun openAppInfo(pkg: String) {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:$pkg")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            Toast.makeText(this, "Toque em Armazenamento → Limpar dados.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Não foi possível abrir as configurações do UniTV.", Toast.LENGTH_LONG).show()
        }
    }

    private fun resetLicense() {
        AlertDialog.Builder(this)
            .setTitle("Resetar licença")
            .setMessage("Isso vai limpar os dados do UniTV e apagar o .config e a pasta /Alarms. Continuar?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Sim, apagar") { _, _ -> doResetLicense() }
            .show()
    }

    private fun doResetLicense() {
        setBusy(true, "Limpando tudo.....")
        val pkg = unitvPackageName
        CoroutineScope(Dispatchers.Main).launch {
            // 1) Tenta limpar dados do app UniTV (requer root em TV boxes)
            var clearedApp = false
            if (!pkg.isNullOrBlank()) {
                clearedApp = withContext(Dispatchers.IO) { tryRootClearData(pkg) }
            }

            // 2) Apaga .config, .properties e pasta Alarms
            withContext(Dispatchers.IO) {
                val root = Environment.getExternalStorageDirectory()
                if (root != null) {
                    listOf(
                        File(root, ".config"),
                        File(root, ".properties"),
                        File(root, "Android/.config"),
                    ).forEach { try { if (it.exists()) it.delete() } catch (_: Exception) {} }
                    val alarms = File(root, "Alarms")
                    try { if (alarms.exists()) alarms.deleteRecursively() } catch (_: Exception) {}
                }
                val ext = getExternalFilesDir(null)
                if (ext != null) {
                    listOf(File(ext, ".config")).forEach {
                        try { if (it.exists()) it.delete() } catch (_: Exception) {}
                    }
                }
            }

            activatedCode = null
            postBlock.visibility = View.GONE
            codeBlock.visibility = View.VISIBLE
            inputCode.setText("")
            txtLicense.text = ""
            val extra = if (!pkg.isNullOrBlank() && !clearedApp)
                " (não foi possível limpar os dados do UniTV automaticamente — abra Configurações → Apps → UniTV → Limpar dados)"
            else ""
            setBusy(false, "Dados limpos. Digite um código para ativar de novo.$extra")
        }
    }


    private fun downloadApkTo(
        url: String, subdir: String, fileName: String,
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
                        val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            val mb = downloaded / 1024.0 / 1024.0
                            onProgress(pct, mb, totalMb)
                        }
                    }
                }
            }
        }
        return out
    }

    private fun openApkInstaller(apk: File) {
        if (!Installer.canInstallUnknownApps(this)) {
            pendingApk = apk
            Toast.makeText(this,
                "Ative \"Instalar apps desconhecidos\" para este app e volte.",
                Toast.LENGTH_LONG).show()
            setBusy(false, "Aguardando permissão de instalação… volte após conceder.")
            Installer.openUnknownSourcesSettings(this, REQ_UNKNOWN_SOURCES)
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir o instalador: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

}
