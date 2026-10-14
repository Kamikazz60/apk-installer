package com.apkinstaller.app.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Résultat communiqué par PackageInstaller. */
data class InstallEvent(
    val sessionId: Int,
    val status: Int,
    val message: String?,
    /** Intention de confirmation fournie par Android (STATUS_PENDING_USER_ACTION). */
    val confirmIntent: Intent?,
)

object InstallEvents {
    private val flow = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<InstallEvent> = flow.asSharedFlow()

    /** Renvoie true si quelqu'un écoutait. */
    fun emit(event: InstallEvent): Boolean {
        val listening = flow.subscriptionCount.value > 0
        flow.tryEmit(event)
        return listening
    }
}

class PackageInstallManager(private val context: Context) {
    fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Réglage Android « Installer des applications inconnues » pour cette application. */
    fun installPermissionSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Écrit les APK (base en premier) dans UNE session PackageInstaller et la valide. Le résultat
     * arrive ensuite via [InstallEvents]. Renvoie l'identifiant de session.
     */
    suspend fun commit(files: List<File>, packageName: String): Int = withContext(Dispatchers.IO) {
        if (files.isEmpty() || files.any { !it.isFile }) {
            throw InstallerException("Les fichiers temporaires ont été supprimés. Sélectionnez à nouveau le fichier.")
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(files.sumOf { it.length() })
        }
        val id = try {
            installer.createSession(params)
        } catch (e: IOException) {
            throw InstallerException("Impossible de créer la session d'installation : ${e.message}", e)
        } catch (e: SecurityException) {
            throw InstallerException("Android refuse de créer la session d'installation.", e)
        }
        try {
            installer.openSession(id).use { session ->
                files.forEachIndexed { i, f ->
                    currentCoroutineContext().ensureActive()
                    val name = if (i == 0) "base.apk" else "split_$i.apk"
                    f.inputStream().use { input ->
                        session.openWrite(name, 0, f.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                }
                session.commit(statusIntentSender(id))
            }
        } catch (e: Throwable) {
            runCatching { installer.abandonSession(id) }
            if (e is IOException) throw InstallerException("Écriture de la session d'installation impossible : ${e.message}", e)
            throw e
        }
        id
    }

    fun abandon(sessionId: Int) {
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
    }

    private fun statusIntentSender(sessionId: Int): android.content.IntentSender {
        val intent = Intent(context, InstallStatusReceiver::class.java).setAction(ACTION_STATUS)
        // FLAG_MUTABLE est requis : le système y ajoute les extras de statut. L'intention est explicite.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }

    companion object {
        const val ACTION_STATUS = "com.apkinstaller.app.INSTALL_STATUS"

        fun describeFailure(status: Int, systemMessage: String?): String {
            val sys = systemMessage.orEmpty()
            val base = when {
                sys.contains("DOWNGRADE") -> "Android refuse de rétrograder l'application : la version installée est plus récente."
                sys.contains("UPDATE_INCOMPATIBLE") || sys.contains("SIGNATURE") ->
                    "Signature incompatible avec l'application déjà installée : désinstallez-la d'abord (ses données seront perdues) ou utilisez un APK signé par le même développeur."
                else -> describeStatus(status)
            }
            return if (systemMessage.isNullOrBlank()) base else "$base\nDétail Android : $systemMessage"
        }

        private fun describeStatus(status: Int): String {
            return when (status) {
                PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation bloquée par l'appareil (Play Protect, contrôle parental ou stratégie de sécurité)."
                PackageInstaller.STATUS_FAILURE_CONFLICT -> "Conflit avec l'application déjà installée (signature différente, version antérieure ou paquet en conflit)."
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Application incompatible avec cet appareil."
                PackageInstaller.STATUS_FAILURE_INVALID -> "APK invalide, corrompu ou incomplet."
                PackageInstaller.STATUS_FAILURE_STORAGE -> "Espace de stockage insuffisant."
                else -> "L'installation a échoué (code $status)."
            }
        }
    }
}

/** Reçoit les statuts de PackageInstaller et les transmet à l'interface. */
class InstallStatusReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirm = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        } else null
        val event = InstallEvent(
            sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1),
            status = status,
            message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            confirmIntent = confirm,
        )
        val listened = InstallEvents.emit(event)
        if (!listened && confirm != null) {
            // Interface absente (processus recréé) : on ouvre directement la confirmation Android.
            runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
