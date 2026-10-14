package com.apkinstaller.app.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apkinstaller.app.ApkInstallerApp
import com.apkinstaller.app.install.AppGroup
import com.apkinstaller.app.install.AppInfo
import com.apkinstaller.app.install.ArchiveExtractor
import com.apkinstaller.app.install.ArchiveLimits
import com.apkinstaller.app.install.GroupResult
import com.apkinstaller.app.install.InstallEvents
import com.apkinstaller.app.install.InstallerException
import com.apkinstaller.app.install.PackageInstallManager
import com.apkinstaller.app.install.SplitSelector
import com.apkinstaller.app.install.ZipAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Stage(val label: String) {
    ANALYSE("Analyse du fichier"),
    EXTRACTION("Extraction"),
    PREPARATION("Préparation de l'installation"),
}

data class InstallPlan(
    val sourceName: String,
    val files: List<File>,
    val info: AppInfo,
    val splitNames: List<String>,
    val notes: List<String>,
    val totalSize: Long,
)

sealed interface InstallUiState {
    data object Idle : InstallUiState
    data class Working(val stage: Stage) : InstallUiState
    /** ZIP contenant plusieurs applications indépendantes. */
    data class ChooseApp(val sourceName: String, val groups: List<AppGroup>, val notes: List<String>) : InstallUiState
    data class Ready(val plan: InstallPlan, val installAllowed: Boolean) : InstallUiState
    data class AwaitingConfirmation(val plan: InstallPlan) : InstallUiState
    data class Success(val plan: InstallPlan) : InstallUiState
    data class Cancelled(val plan: InstallPlan?) : InstallUiState
    data class Failed(val message: String, val plan: InstallPlan?) : InstallUiState
}

class InstallationViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as ApkInstallerApp).container
    private val limits = ArchiveLimits()

    private val _state = MutableStateFlow<InstallUiState>(InstallUiState.Idle)
    val state: StateFlow<InstallUiState> = _state.asStateFlow()

    private val _effects = Channel<Intent>(Channel.BUFFERED)
    /** Intentions à lancer depuis l'activité (confirmation Android). */
    val effects = _effects.receiveAsFlow()

    private var job: Job? = null
    private var sessionDir: File? = null
    private var archiveFile: File? = null
    private var sourceName: String = ""
    private var archiveGroups: List<AppGroup> = emptyList()
    private var archiveNotes: List<String> = emptyList()
    private var currentSessionId = -1
    private var currentPlan: InstallPlan? = null

    /** Nettoyage initial : toute opération attend qu'il soit terminé. */
    private val startup: Job = viewModelScope.launch(Dispatchers.IO) {
        if (c.settings.current().autoCleanCache) c.cache.clear()
    }

    init {
        viewModelScope.launch { InstallEvents.events.collect(::onInstallEvent) }
    }

    fun onFileSelected(uri: Uri) {
        startJob {
            _state.value = InstallUiState.Working(Stage.ANALYSE)
            abandonSession()
            currentPlan = null
            val meta = c.files.queryMeta(uri)
            sourceName = meta.name
            val dir = withContext(Dispatchers.IO) { c.cache.newSession() }
            sessionDir = dir
            val source = File(dir, "source")
            withContext(Dispatchers.IO) { c.files.copyToCache(uri, source, meta.size, limits.maxInputBytes) }

            val zip = ZipAnalyzer.open(source)
            zip.use {
                if (ZipAnalyzer.isApk(zip)) {
                    // APK direct (le contenu prime sur l'extension).
                    val info = analyzeApk(source)
                    publishReady(listOf(source), info, emptyList(), emptyList())
                } else {
                    analyzeArchive(source)
                }
            }
        }
    }

    private suspend fun analyzeArchive(source: File) {
        archiveFile = source
        val (groups, notes) = withContext(Dispatchers.IO) {
            val zip = ZipAnalyzer.open(source)
            zip.use {
                val check = { ensureActive() }
                val scan = ZipAnalyzer.analyze(zip, limits, File(source.parentFile, "probe"), check)
                if (scan.apks.isEmpty()) {
                    throw InstallerException("Aucun APK n'a été trouvé dans cette archive.")
                }
                val notes = ArrayList<String>()
                if (scan.invalid.isNotEmpty()) {
                    notes += "${scan.invalid.size} fichier(s) .apk ignoré(s) car invalides ou non conformes."
                }
                when (val r = SplitSelector.group(scan.apks, c.device)) {
                    is GroupResult.Error -> throw InstallerException(r.message)
                    is GroupResult.Ok -> {
                        if (r.skipped.isNotEmpty()) notes += "${r.skipped.size} split(s) non nécessaire(s) pour cet appareil ignoré(s)."
                        r.groups to notes
                    }
                }
            }
        }
        archiveGroups = groups
        archiveNotes = notes
        if (groups.size == 1) prepareGroup(groups.single()) else {
            _state.value = InstallUiState.ChooseApp(sourceName, groups, notes)
        }
    }

    fun chooseGroup(group: AppGroup) {
        startJob { prepareGroup(group) }
    }

    private suspend fun prepareGroup(group: AppGroup) {
        val archive = archiveFile ?: throw InstallerException("Archive introuvable. Sélectionnez à nouveau le fichier.")
        val dir = sessionDir ?: throw InstallerException("Dossier temporaire introuvable.")
        _state.value = InstallUiState.Working(Stage.EXTRACTION)
        val extracted = withContext(Dispatchers.IO) {
            val outDir = File(dir, "extracted_${group.packageName}")
            outDir.deleteRecursively()
            ZipAnalyzer.open(archive).use { zip ->
                ArchiveExtractor.extract(zip, group.entries, outDir, limits) { ensureActive() }
            }
        }
        val info = analyzeApk(extracted.first())
        if (info.packageName != group.packageName) {
            throw InstallerException("Le paquet de l'APK extrait ne correspond pas à l'archive. Installation refusée.")
        }
        publishReady(extracted, info, group.splits.mapNotNull { it.manifest.splitName }, archiveNotes)
    }

    private suspend fun analyzeApk(file: File): AppInfo = withContext(Dispatchers.IO) {
        c.analyzer.analyze(file) ?: throw InstallerException("Ce fichier n'est pas un APK exploitable (corrompu, incomplet ou format invalide).")
    }

    private fun publishReady(files: List<File>, info: AppInfo, splits: List<String>, extraNotes: List<String>) {
        val notes = ArrayList(extraNotes)
        if (files.size > 1) notes += "Application à APK fractionnés : ${files.size} fichiers seront installés dans une même session."
        val plan = InstallPlan(sourceName, files, info, splits, notes, files.sumOf { it.length() })
        currentPlan = plan
        _state.value = InstallUiState.Ready(plan, c.installer.canRequestInstalls())
    }

    /** À appeler au retour de l'écran de réglages Android. */
    fun refreshInstallPermission() {
        val s = _state.value
        if (s is InstallUiState.Ready) _state.value = s.copy(installAllowed = c.installer.canRequestInstalls())
    }

    fun install() {
        val plan = currentPlan ?: return
        if (!c.installer.canRequestInstalls()) {
            refreshInstallPermission()
            return
        }
        startJob(plan) {
            _state.value = InstallUiState.Working(Stage.PREPARATION)
            abandonSession()
            val id = c.installer.commit(plan.files, plan.info.packageName)
            currentSessionId = id
            // L'interface passe en « Confirmation Android nécessaire » quand le système le demande,
            // ou directement en succès/échec : jamais de succès supposé.
            if (_state.value is InstallUiState.Working) _state.value = InstallUiState.AwaitingConfirmation(plan)
        }
    }

    private fun onInstallEvent(e: com.apkinstaller.app.install.InstallEvent) {
        if (e.sessionId != currentSessionId) return
        val plan = currentPlan ?: return
        when (e.status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                _state.value = InstallUiState.AwaitingConfirmation(plan)
                e.confirmIntent?.let { _effects.trySend(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                currentSessionId = -1
                _state.value = InstallUiState.Success(plan)
                cleanupAfterFinish()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                currentSessionId = -1
                _state.value = InstallUiState.Cancelled(plan)
            }
            else -> {
                currentSessionId = -1
                _state.value = InstallUiState.Failed(PackageInstallManager.describeFailure(e.status, e.message), plan)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        abandonSession()
        val plan = currentPlan
        if (plan != null && _state.value is InstallUiState.Working) {
            _state.value = InstallUiState.Ready(plan, c.installer.canRequestInstalls())
        } else if (plan == null) {
            reset()
        }
    }

    /** Retour à l'état initial et suppression des fichiers temporaires. */
    fun reset() {
        job?.cancel()
        abandonSession()
        _state.value = InstallUiState.Idle
        currentPlan = null
        archiveFile = null
        archiveGroups = emptyList()
        val dir = sessionDir
        sessionDir = null
        viewModelScope.launch(Dispatchers.IO) { c.cache.delete(dir) }
    }

    /** Revient à l'écran « prêt » après annulation ou échec, si les fichiers existent toujours. */
    fun backToReady() {
        val plan = currentPlan
        if (plan != null && plan.files.all { it.isFile }) {
            _state.value = InstallUiState.Ready(plan, c.installer.canRequestInstalls())
        } else {
            reset()
        }
    }

    private fun cleanupAfterFinish() {
        viewModelScope.launch {
            if (c.settings.current().autoCleanCache) {
                val dir = sessionDir
                sessionDir = null
                archiveFile = null
                withContext(Dispatchers.IO) { c.cache.delete(dir) }
            }
        }
    }

    private fun abandonSession() {
        if (currentSessionId >= 0) c.installer.abandon(currentSessionId)
        currentSessionId = -1
    }

    private fun startJob(plan: InstallPlan? = null, block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            startup.join()
            try {
                block()
                ensureActive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: InstallerException) {
                _state.value = InstallUiState.Failed(e.message ?: "Erreur inconnue.", plan ?: currentPlan)
            } catch (e: Exception) {
                _state.value = InstallUiState.Failed("Erreur inattendue : ${e.message ?: e.javaClass.simpleName}", plan ?: currentPlan)
            }
        }
    }

    override fun onCleared() {
        // Les fichiers temporaires restent supprimés au prochain lancement si le nettoyage automatique est actif.
        super.onCleared()
    }
}
