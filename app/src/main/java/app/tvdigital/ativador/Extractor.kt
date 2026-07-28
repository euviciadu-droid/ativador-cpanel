package app.tvdigital.ativador

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object Extractor {

    /**
     * Extrai um arquivo ZIP preservando a estrutura interna.
     *
     * Tenta gravar na raiz do armazenamento (/storage/emulated/0/) — funciona em
     * TV boxes Android ≤10 e Android 11+ com "Gerenciar todos os arquivos" concedida.
     * Se falhar (emulador ou Android novo sem permissão), cai para o diretório
     * privado do app: /storage/emulated/0/Android/data/<pacote>/files/.
     *
     * Retorna o diretório base onde o conteúdo foi extraído.
     */
    fun extractToRoot(context: Context, zipFile: File): File {
        val root = resolveExtractionRoot(context)
        val target = File(root, "Alarms")
        if (target.exists()) {
            val removed = if (target.isDirectory) target.deleteRecursively() else target.delete()
            if (!removed || target.exists()) throw RuntimeException("Não foi possível substituir a pasta Alarms")
        }
        target.mkdirs()

        // Compatível com o ativador que funciona: destino fixo /sdcard/Alarms.
        // Se o ZIP vier como Alarms/system_uf/google.wav, system_uf/google.wav
        // ou com uma pasta extra por cima, normaliza para Alarms/system_uf/...
        val targetCanonical = target.canonicalFile
        val targetPrefix = targetCanonical.canonicalPath.trimEnd('/') + File.separator
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile), 64 * 1024)).use { zis ->
            val buffer = ByteArray(64 * 1024)
            var entry = zis.nextEntry
            while (entry != null) {
                val entryName = normalizeEntryName(entry.name)
                if (entryName.isEmpty()) {
                    zis.closeEntry(); entry = zis.nextEntry; continue
                }
                val outFile = File(target, entryName)
                val outCanonical = outFile.canonicalFile
                if (outCanonical != targetCanonical && !outCanonical.canonicalPath.startsWith(targetPrefix)) {
                    zis.closeEntry(); entry = zis.nextEntry; continue
                }
                if (entry.isDirectory) {
                    if (outFile.exists() && !outFile.isDirectory) {
                        if (!outFile.delete()) throw RuntimeException("Não foi possível substituir ${entry.name}")
                    }
                    outFile.mkdirs()
                } else {
                    if (outFile.exists()) {
                        val removed = if (outFile.isDirectory) outFile.deleteRecursively() else outFile.delete()
                        if (!removed || outFile.exists()) {
                            throw RuntimeException("Não foi possível sobrescrever ${entry.name}")
                        }
                    }
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var n = zis.read(buffer)
                        while (n >= 0) {
                            fos.write(buffer, 0, n)
                            n = zis.read(buffer)
                        }
                        fos.flush()
                        try { fos.fd.sync() } catch (_: Exception) {}
                    }
                    // Preserva timestamp original quando disponível
                    val t = entry.time
                    if (t > 0) {
                        try { outFile.setLastModified(t) } catch (_: Exception) {}
                    }
                    try { outFile.setReadable(true, false) } catch (_: Exception) {}
                    try { outFile.setWritable(true, true) } catch (_: Exception) {}
                    // Validação de integridade: tamanho declarado vs gravado
                    val expected = entry.size
                    if (expected >= 0 && outFile.length() != expected) {
                        throw RuntimeException(
                            "Falha ao extrair ${entry.name}: esperado $expected bytes, gravado ${outFile.length()}"
                        )
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        try { MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null) } catch (_: Exception) {}
        return target
    }

    private fun normalizeEntryName(rawName: String): String {
        var normalized = rawName.replace('\\', '/').trimStart('/')
        if (normalized.isBlank()) return ""
        normalized = when {
            normalized.contains("system_uf/") -> normalized.substring(normalized.indexOf("system_uf/"))
            normalized.contains("Alarms/") -> normalized.substring(normalized.indexOf("Alarms/") + "Alarms/".length)
            else -> normalized
        }
        val parts = normalized.split('/').filter { it.isNotBlank() }
        if (parts.any { it == "." || it == ".." }) return ""
        return parts.joinToString("/")
    }

    private fun resolveExtractionRoot(context: Context): File {
        val hasAllFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager() else true

        if (hasAllFiles) {
            val root = Environment.getExternalStorageDirectory()
            if (root != null && root.isDirectory) {
                // Teste real de escrita (Android 11+ pode negar mesmo com permissão)
                val probe = File(root, ".tvdigital_probe")
                try {
                    FileOutputStream(probe).use { it.write(byteArrayOf()) }
                    probe.delete()
                    return root
                } catch (_: Exception) {
                    // cai no fallback
                }
            }
        }

        val fallback = context.getExternalFilesDir(null)
            ?: throw RuntimeException("Armazenamento externo indisponível.")
        if (!fallback.exists()) fallback.mkdirs()
        return fallback
    }
}
