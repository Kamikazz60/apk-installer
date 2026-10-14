package com.apkinstaller.app

import android.app.Application
import android.content.res.Resources
import android.os.Build
import androidx.core.os.ConfigurationCompat
import com.apkinstaller.app.data.CacheManager
import com.apkinstaller.app.data.FileRepository
import com.apkinstaller.app.data.FileScanner
import com.apkinstaller.app.data.SettingsRepository
import com.apkinstaller.app.install.ApkAnalyzer
import com.apkinstaller.app.install.DeviceSpec
import com.apkinstaller.app.install.PackageInstallManager

/** Conteneur de dépendances minimal (pas de framework d'injection). */
class AppContainer(app: Application) {
    val settings = SettingsRepository(app)
    val files = FileRepository(app)
    val scanner = FileScanner(app, files)
    val cache = CacheManager(app)
    val analyzer = ApkAnalyzer(app)
    val installer = PackageInstallManager(app)

    val device: DeviceSpec
        get() {
            val cfg = Resources.getSystem().configuration
            val locales = ConfigurationCompat.getLocales(cfg)
            val langs = (0 until locales.size()).mapNotNull { locales.get(it)?.language }.distinct().ifEmpty { listOf("en") }
            return DeviceSpec(
                abis = Build.SUPPORTED_ABIS.toList(),
                densityDpi = Resources.getSystem().displayMetrics.densityDpi,
                languages = langs,
            )
        }
}

class ApkInstallerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
