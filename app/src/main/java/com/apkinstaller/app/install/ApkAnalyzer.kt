package com.apkinstaller.app.install

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.security.MessageDigest

enum class SignatureCheck { COMPATIBLE, MISMATCH, UNKNOWN }

data class AppInfo(
    val label: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val icon: Bitmap?,
    val installedVersionName: String?,
    val installedVersionCode: Long?,
    val comparison: VersionComparison,
    val signature: SignatureCheck,
    /** L'APK exige une version d'Android plus récente que celle de l'appareil. */
    val minSdkTooHigh: Boolean,
    val minSdk: Int,
)

/** Métadonnées d'un APK via PackageManager (l'APK doit être un fichier local, p. ex. dans le cache). */
class ApkAnalyzer(private val context: Context) {
    private val pm: PackageManager get() = context.packageManager

    fun analyze(apk: File): AppInfo? {
        val path = apk.absolutePath
        val pi = archiveInfo(path) ?: return null
        val ai = pi.applicationInfo ?: return null
        ai.sourceDir = path
        ai.publicSourceDir = path

        val label = try {
            ai.loadLabel(pm).toString()
        } catch (_: Exception) {
            pi.packageName
        }
        val icon = try {
            ai.loadIcon(pm).toBitmap(144, 144)
        } catch (_: Exception) {
            null
        }

        val installed = installedInfo(pi.packageName)
        val installedCode = installed?.let { PackageInfoCompat.getLongVersionCode(it) }
        val code = PackageInfoCompat.getLongVersionCode(pi)
        val signature = if (installed == null) {
            SignatureCheck.COMPATIBLE
        } else {
            val a = certs(pi)
            val b = certs(installed)
            when {
                a.isEmpty() || b.isEmpty() -> SignatureCheck.UNKNOWN
                a.intersect(b).isNotEmpty() -> SignatureCheck.COMPATIBLE
                else -> SignatureCheck.MISMATCH
            }
        }
        return AppInfo(
            label = label.ifBlank { pi.packageName },
            packageName = pi.packageName,
            versionName = pi.versionName,
            versionCode = code,
            icon = icon,
            installedVersionName = installed?.versionName,
            installedVersionCode = installedCode,
            comparison = VersionLogic.compare(installedCode, code),
            signature = signature,
            minSdkTooHigh = ai.minSdkVersion > Build.VERSION.SDK_INT,
            minSdk = ai.minSdkVersion,
        )
    }

    private fun signingFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    private fun archiveInfo(path: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(signingFlag().toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, signingFlag())
        }
    } catch (_: Exception) {
        null
    }

    /** Null si non installée (ou invisible : les restrictions de visibilité des paquets s'appliquent). */
    private fun installedInfo(pkg: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(signingFlag().toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, signingFlag())
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun certs(pi: PackageInfo): Set<String> {
        val list = ArrayList<Signature>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.signingInfo?.let { si ->
                si.apkContentsSigners?.let { list += it }
                si.signingCertificateHistory?.let { list += it }
            }
        } else {
            @Suppress("DEPRECATION")
            pi.signatures?.let { list += it }
        }
        val md = MessageDigest.getInstance("SHA-256")
        return list.map { md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }
}
