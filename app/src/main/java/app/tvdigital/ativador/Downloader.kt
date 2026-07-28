package app.tvdigital.ativador

import android.content.Context
import android.os.Build
import android.os.Environment
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object Downloader {

    data class InstallPackage(val apks: List<File>)

    /**
     * Tenta gravar o `.config` em /storage/emulated/0/Android/.config (requisito do cliente).
     * A partir do Android 11 essa pasta raiz é bloqueada pelo Scoped Storage mesmo com
     * MANAGE_EXTERNAL_STORAGE. Nesse caso, cai automaticamente para o diretório do app
     * em /Android/data/<pacote>/files/.config, que funciona em 100% dos aparelhos.
     */
    fun downloadConfig(context: Context, url: String, savePath: String, fileName: String): File {
        val bytes = downloadBytes(url)
        return saveConfigBytes(context, bytes)
    }

    fun saveConfigContent(context: Context, content: String): File {
        return saveConfigBytes(context, content.toByteArray(Charsets.UTF_8))
    }

    private fun saveConfigBytes(context: Context, bytes: ByteArray): File {
        val targets = resolveConfigTargets(context)
        var firstSaved: File? = null
        val errors = mutableListOf<String>()

        for (target in targets) {
            try {
                target.parentFile?.mkdirs()
                if (target.exists()) {
                    if (target.isDirectory) target.deleteRecursively() else target.delete()
                }
                FileOutputStream(target).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                    try { fos.fd.sync() } catch (_: Exception) {}
                }
                if (target.exists() && target.length() > 0 && firstSaved == null) firstSaved = target
            } catch (e: Exception) {
                errors += "${target.path}: ${e.message}"
            }
        }
        return firstSaved ?: throw RuntimeException("Não foi possível gravar .config/.properties: ${errors.joinToString("; ")}")
    }

    private fun resolveConfigTargets(context: Context): List<File> {
        val hasAllFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager() else true

        val out = mutableListOf<File>()
        if (hasAllFiles) {
            val root = Environment.getExternalStorageDirectory()
            if (root != null) {
                out += File(root, ".config")
                out += File(root, ".properties")
                out += File(root, "Android/.config")
                out += File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ".config")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    out += File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), ".config")
                }
            }
        }

        val ext = context.getExternalFilesDir(null)
            ?: throw RuntimeException("Armazenamento externo indisponível.")
        if (!ext.exists()) ext.mkdirs()
        out += File(ext, ".config")
        return out.distinctBy { it.absolutePath }
    }


    /** Baixa o pacote de instalação para cache externo.
     *  Se o arquivo baixado for um APK normal, retorna ele diretamente.
     *  Se for um ZIP/APKS/XAPK com vários APKs split, extrai TODOS os APKs
     *  para instalação em sessão única — instalar só o primeiro split pode
     *  gerar "dispositivo não compatível" em apps como UniTV.
     */
    fun downloadInstallPackage(context: android.content.Context, url: String): InstallPackage {
        val dir = File(context.externalCacheDir ?: context.cacheDir, "pack")
        if (!dir.exists()) dir.mkdirs()
        dir.listFiles()?.forEach { try { it.deleteRecursively() } catch (_: Exception) {} }
        val downloaded = File(dir, "pack.bin")
        writeUrlTo(url, downloaded)

        // Detecta assinatura do arquivo (magic bytes)
        val head = ByteArray(4)
        downloaded.inputStream().use { it.read(head) }
        val isZip = head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                    head[2] == 0x03.toByte() && head[3] == 0x04.toByte()

        if (!isZip) {
            val apkOut = File(dir, "pack.apk")
            if (apkOut.exists()) apkOut.delete()
            downloaded.renameTo(apkOut)
            return InstallPackage(listOf(apkOut))
        }

        // É um ZIP: procura um .apk dentro. APK também começa com PK, então
        // só chegamos aqui se NÃO houver AndroidManifest.xml na raiz do ZIP
        // (APKs válidos são ZIPs mas com AndroidManifest.xml). Fazemos uma
        // segunda checagem: se o ZIP tiver AndroidManifest.xml no topo, é um APK.
        val hasAndroidManifest = try {
            var found = false
            java.util.zip.ZipInputStream(downloaded.inputStream().buffered()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (e.name == "AndroidManifest.xml") { found = true; break }
                    zis.closeEntry(); e = zis.nextEntry
                }
            }
            found
        } catch (_: Exception) { false }

        if (hasAndroidManifest) {
            val apkOut = File(dir, "pack.apk")
            if (apkOut.exists()) apkOut.delete()
            downloaded.renameTo(apkOut)
            return InstallPackage(listOf(apkOut))
        }

        // ZIP/APKS/XAPK normal: extrai todos os APKs encontrados.
        val extractedApks = mutableListOf<File>()
        java.util.zip.ZipInputStream(downloaded.inputStream().buffered(64 * 1024)).use { zis ->
            val buffer = ByteArray(64 * 1024)
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".apk")) {
                    val safeName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        .ifEmpty { "split_${extractedApks.size}.apk" }
                    val apkOut = File(dir, "${extractedApks.size}_$safeName")
                    FileOutputStream(apkOut).use { fos ->
                        var n = zis.read(buffer)
                        while (n >= 0) { fos.write(buffer, 0, n); n = zis.read(buffer) }
                        fos.flush()
                        try { fos.fd.sync() } catch (_: Exception) {}
                    }
                    if (apkOut.length() > 0) extractedApks += apkOut
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        try { downloaded.delete() } catch (_: Exception) {}
        if (extractedApks.isEmpty()) throw RuntimeException("Nenhum APK encontrado dentro do ZIP baixado.")

        val orderedApks = extractedApks.sortedWith(
            compareBy<File> {
                val n = it.name.lowercase()
                when {
                    n == "base.apk" || n.endsWith("_base.apk") || n.contains("/base.apk") -> 0
                    n.contains("base") -> 1
                    else -> 2
                }
            }.thenBy { it.name }
        )
        return InstallPackage(orderedApks)
    }


    /** Baixa um ZIP para o cache do app (não requer permissão). */
    fun downloadZipToCache(context: android.content.Context, url: String, name: String): File {
        val dir = File(context.externalCacheDir ?: context.cacheDir, "zips")
        if (!dir.exists()) dir.mkdirs()
        val safe = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifEmpty { "pack.zip" }
        val out = File(dir, safe)
        if (out.exists()) out.delete()
        writeUrlTo(url, out)
        return out
    }

    private fun writeUrlTo(url: String, out: File) {
        val req = Request.Builder().url(url).build()
        Api.http().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} ao baixar")
            val body = resp.body ?: throw RuntimeException("Corpo vazio")
            FileOutputStream(out).use { fos ->
                body.byteStream().copyTo(fos)
            }
        }
    }

    private fun downloadBytes(url: String): ByteArray {
        val req = Request.Builder().url(url).build()
        Api.http().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} ao baixar")
            val body = resp.body ?: throw RuntimeException("Corpo vazio")
            val baos = ByteArrayOutputStream()
            body.byteStream().use { input -> input.copyTo(baos) }
            return baos.toByteArray()
        }
    }
}
