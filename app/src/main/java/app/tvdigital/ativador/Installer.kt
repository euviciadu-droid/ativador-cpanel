package app.tvdigital.ativador

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object Installer {

    const val ACTION_INSTALL_COMMIT = "app.tvdigital.ativador.INSTALL_COMMIT"

    /**
     * Retorna true se o app já pode instalar APKs de fontes desconhecidas.
     * Em Android < 8 a permissão era global (via Settings), então assumimos true
     * (a flag REQUEST_INSTALL_PACKAGES no manifest é suficiente para disparar o instalador).
     */
    fun canInstallUnknownApps(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    /**
     * Abre a tela do sistema para o usuário conceder "Instalar apps desconhecidos"
     * especificamente para este pacote. Em versões antigas, cai para a tela geral
     * de segurança onde o usuário liga "Fontes desconhecidas".
     */
    fun openUnknownSourcesSettings(activity: Activity, requestCode: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivityForResult(intent, requestCode)
            } else {
                @Suppress("DEPRECATION")
                val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivityForResult(intent, requestCode)
            }
        } catch (_: Exception) {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(i)
        }
    }

    fun install(activity: Activity, installPackage: Downloader.InstallPackage) {
        if (installPackage.apks.isEmpty()) {
            throw IllegalArgumentException("Nenhum APK para instalar.")
        }

        if (installPackage.apks.size == 1) {
            installSingle(activity, installPackage.apks.first())
        } else {
            installSplitPackage(activity, installPackage.apks)
        }
    }

    private fun installSingle(context: Context, apk: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        } else {
            Uri.fromFile(apk)
        }
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        context.startActivity(intent)
    }

    private fun installSplitPackage(activity: Activity, apks: List<File>) {
        val packageInstaller = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = packageInstaller.createSession(params)
        var session: PackageInstaller.Session? = null

        try {
            session = packageInstaller.openSession(sessionId)
            for ((index, apk) in apks.withIndex()) {
                apk.inputStream().use { input ->
                    session.openWrite("${index}_${apk.name}", 0, apk.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }
            }

            val callbackIntent = Intent(activity, MainActivity::class.java).apply {
                action = ACTION_INSTALL_COMMIT
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(activity, sessionId, callbackIntent, flags)
            session.commit(pendingIntent.intentSender)
        } catch (e: Exception) {
            try { packageInstaller.abandonSession(sessionId) } catch (_: Exception) {}
            throw e
        } finally {
            try { session?.close() } catch (_: Exception) {}
        }
    }
}
