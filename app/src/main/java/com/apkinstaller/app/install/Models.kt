package com.apkinstaller.app.install

/** Erreur dont le message est destiné à être affiché tel quel à l'utilisateur. */
class InstallerException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Informations extraites du AndroidManifest.xml binaire d'un APK. */
data class ManifestInfo(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    /** Non nul pour un APK fractionné (split). */
    val splitName: String?,
    val isFeatureSplit: Boolean,
    val configForSplit: String?,
)

/** Un APK trouvé dans une archive ZIP. */
data class ApkEntry(
    val entryName: String,
    val size: Long,
    val compressedSize: Long,
    val manifest: ManifestInfo,
) {
    val isBase: Boolean get() = manifest.splitName == null
    val displayName: String get() = entryName.substringAfterLast('/')
}

/** Une application installable : un APK de base et les splits retenus pour cet appareil. */
data class AppGroup(
    val packageName: String,
    val base: ApkEntry,
    val splits: List<ApkEntry>,
) {
    val entries: List<ApkEntry> get() = listOf(base) + splits
    val isSplitApp: Boolean get() = splits.isNotEmpty()
}

/** Caractéristiques de l'appareil utiles au choix des splits. */
data class DeviceSpec(
    val abis: List<String>,
    val densityDpi: Int,
    val languages: List<String>,
)

/** Limites de sécurité appliquées aux archives. */
data class ArchiveLimits(
    val maxEntries: Int = 5_000,
    val maxApkEntries: Int = 64,
    val maxEntryBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxTotalExtractedBytes: Long = 4L * 1024 * 1024 * 1024,
    val maxInputBytes: Long = 4L * 1024 * 1024 * 1024,
    /** Au-delà de cette taille décompressée, un ratio supérieur à [maxCompressionRatio] est jugé suspect. */
    val ratioCheckMinBytes: Long = 50L * 1024 * 1024,
    val maxCompressionRatio: Long = 200,
    val maxManifestBytes: Int = 8 * 1024 * 1024,
    /** Quantité maximale lue dans un APK imbriqué pour y trouver son manifeste. */
    val maxManifestScanBytes: Long = 512L * 1024 * 1024,
)

enum class VersionComparison { NOT_INSTALLED, NEWER, SAME, OLDER }

object VersionLogic {
    fun compare(installedVersionCode: Long?, candidateVersionCode: Long): VersionComparison = when {
        installedVersionCode == null -> VersionComparison.NOT_INSTALLED
        candidateVersionCode > installedVersionCode -> VersionComparison.NEWER
        candidateVersionCode == installedVersionCode -> VersionComparison.SAME
        else -> VersionComparison.OLDER
    }
}
