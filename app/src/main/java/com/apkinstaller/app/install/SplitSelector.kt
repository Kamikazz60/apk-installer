package com.apkinstaller.app.install

import kotlin.math.abs

sealed interface GroupResult {
    data class Ok(val groups: List<AppGroup>, val skipped: List<ApkEntry>) : GroupResult
    data class Error(val message: String) : GroupResult
}

/**
 * Regroupe les APK d'une archive par application et choisit, pour les applications à APK
 * fractionnés, uniquement les splits compatibles avec l'appareil. En cas d'ambiguïté,
 * renvoie une erreur plutôt que de deviner.
 */
object SplitSelector {
    private val DENSITIES = mapOf(
        "ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240,
        "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640,
    )
    private val ABIS = setOf("arm64_v8a", "armeabi_v7a", "armeabi", "x86", "x86_64", "mips", "mips64")
    private val LANGUAGE = Regex("^[a-z]{2,3}([_-]r?[A-Za-z]{2,4})?$")

    private enum class Kind { ABI, DENSITY, LANGUAGE, UNKNOWN }

    private class ConfigSplit(val entry: ApkEntry, val forSplit: String, val kind: Kind, val qualifier: String)

    fun normalizeAbi(abi: String) = abi.replace('-', '_').lowercase()

    fun group(entries: List<ApkEntry>, device: DeviceSpec): GroupResult {
        if (entries.isEmpty()) return GroupResult.Ok(emptyList(), emptyList())
        val groups = ArrayList<AppGroup>()
        val skipped = ArrayList<ApkEntry>()
        for ((pkg, list) in entries.groupBy { it.manifest.packageName }) {
            when (val r = groupPackage(pkg, list, device)) {
                is GroupResult.Error -> return r
                is GroupResult.Ok -> {
                    groups += r.groups
                    skipped += r.skipped
                }
            }
        }
        return GroupResult.Ok(groups.sortedBy { it.base.entryName }, skipped)
    }

    private fun groupPackage(pkg: String, list: List<ApkEntry>, device: DeviceSpec): GroupResult {
        val bases = list.filter { it.isBase }
        val splits = list.filter { !it.isBase }
        if (bases.isEmpty()) {
            return GroupResult.Error("L'APK de base de « $pkg » est absent de l'archive (seuls des APK fractionnés sont présents).")
        }
        if (bases.size > 1) {
            return GroupResult.Error("L'archive contient plusieurs APK de base pour « $pkg » (versions ou variantes différentes). Installation refusée car ambiguë.")
        }
        val base = bases.single()
        if (splits.isEmpty()) return GroupResult.Ok(listOf(AppGroup(pkg, base, emptyList())), emptyList())

        val names = splits.map { it.manifest.splitName!! }
        if (names.toSet().size != names.size) {
            return GroupResult.Error("L'archive contient des splits en double pour « $pkg ». Installation refusée car ambiguë.")
        }
        val wrongVersion = splits.firstOrNull { it.manifest.versionCode != base.manifest.versionCode }
        if (wrongVersion != null) {
            return GroupResult.Error("Le split « ${wrongVersion.displayName} » n'a pas la même version que l'APK de base de « $pkg ».")
        }

        val chosen = ArrayList<ApkEntry>()
        val skipped = ArrayList<ApkEntry>()
        val config = ArrayList<ConfigSplit>()
        val available = names.toSet() + ""
        for (s in splits) {
            val c = classify(s)
            if (c == null) chosen += s else config += c
        }
        for ((forSplit, group) in config.groupBy { it.forSplit }) {
            if (forSplit !in available) {
                skipped += group.map { it.entry } // split de configuration d'un module absent
                continue
            }
            for ((kind, items) in group.groupBy { it.kind }) {
                when (kind) {
                    Kind.ABI -> {
                        val match = device.abis.map(::normalizeAbi).firstNotNullOfOrNull { abi ->
                            items.firstOrNull { it.qualifier == abi }
                        } ?: return GroupResult.Error(
                            "Aucun split compatible avec l'architecture de cet appareil (${device.abis.joinToString()}) " +
                                "pour « $pkg ». Splits présents : ${items.joinToString { it.qualifier }}.",
                        )
                        chosen += match.entry
                        skipped += items.filter { it !== match }.map { it.entry }
                    }
                    Kind.DENSITY -> {
                        val match = items.minWith(
                            compareBy<ConfigSplit> { abs(DENSITIES.getValue(it.qualifier) - device.densityDpi) }
                                .thenByDescending { DENSITIES.getValue(it.qualifier) },
                        )
                        chosen += match.entry
                        skipped += items.filter { it !== match }.map { it.entry }
                    }
                    Kind.LANGUAGE -> {
                        val langs = device.languages.map { it.lowercase() }.toSet()
                        for (item in items) {
                            if (item.qualifier.substringBefore('_').lowercase() in langs) chosen += item.entry
                            else skipped += item.entry
                        }
                    }
                    Kind.UNKNOWN -> skipped += items.map { it.entry }
                }
            }
        }
        val ordered = chosen.sortedBy { it.manifest.splitName }
        return GroupResult.Ok(listOf(AppGroup(pkg, base, ordered)), skipped)
    }

    /** Renvoie null si le split n'est pas un split de configuration (module ou ressource à installer tel quel). */
    private fun classify(entry: ApkEntry): ConfigSplit? {
        val name = entry.manifest.splitName ?: return null
        if (entry.manifest.isFeatureSplit) return null
        val idx = when {
            name.startsWith("config.") -> 0
            else -> name.indexOf(".config.").let { if (it < 0) -1 else it + 1 }
        }
        if (idx < 0) return null
        val forSplit = if (idx == 0) "" else name.substring(0, idx - 1)
        val qualifier = name.substring(idx + "config.".length).replace('-', '_')
        val kind = when {
            normalizeAbi(qualifier) in ABIS -> Kind.ABI
            qualifier.lowercase() in DENSITIES -> Kind.DENSITY
            LANGUAGE.matches(qualifier) -> Kind.LANGUAGE
            else -> Kind.UNKNOWN
        }
        val q = when (kind) {
            Kind.ABI -> normalizeAbi(qualifier)
            Kind.DENSITY -> qualifier.lowercase()
            else -> qualifier
        }
        return ConfigSplit(entry, forSplit, kind, q)
    }
}
