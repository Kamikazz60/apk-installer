package com.apkinstaller.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.apkinstaller.app.data.AppSettings
import com.apkinstaller.app.data.ThemeMode
import com.apkinstaller.app.ui.ApkInstallerTheme
import com.apkinstaller.app.ui.DownloadsInitialUri
import com.apkinstaller.app.ui.FilesScreen
import com.apkinstaller.app.ui.FilesViewModel
import com.apkinstaller.app.ui.HomeScreen
import com.apkinstaller.app.ui.InstallationViewModel
import com.apkinstaller.app.ui.SettingsScreen
import com.apkinstaller.app.ui.SettingsViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val installVm: InstallationViewModel by viewModels()
    private val filesVm: FilesViewModel by viewModels()
    private val settingsVm: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Lancement des confirmations Android demandées par PackageInstaller.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                installVm.effects.collect { intent -> runCatching { startActivity(intent) } }
            }
        }

        // Une seule prise en compte de l'intention de lancement (pas de rejeu lors d'une rotation).
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val settings by settingsVm.settings.collectAsStateWithLifecycle()
            ApkInstallerTheme(settings.theme) {
                Surface { AppRoot(settings) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        installVm.refreshInstallPermission()
    }

    /** ACTION_VIEW / ACTION_SEND : l'URI est un content:// à lire via ContentResolver, jamais un chemin supposé. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            else -> null
        }
        if (uri != null) installVm.onFileSelected(uri)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppRoot(settings: AppSettings) {
        val nav = rememberNavController()
        val filesState by filesVm.state.collectAsStateWithLifecycle()
        val current = nav.currentBackStackEntryAsState().value?.destination?.route

        val pickFile = rememberLauncherForActivityResult(object : ActivityResultContracts.OpenDocument() {
            override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
                super.createIntent(context, input).putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, DownloadsInitialUri)
        }) { uri ->
            if (uri != null) {
                installVm.onFileSelected(uri)
                nav.navigateTop("home")
            }
        }
        val pickMimes = arrayOf(
            "application/vnd.android.package-archive",
            "application/zip",
            "application/x-zip-compressed",
            "application/octet-stream",
        )

        // Ouverture depuis l'extérieur : on affiche l'accueil.
        val installState by installVm.state.collectAsStateWithLifecycle()
        androidx.compose.runtime.LaunchedEffect(installState::class) {
            if (installState !is com.apkinstaller.app.ui.InstallUiState.Idle && current != "home") nav.navigateTop("home")
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row {
                            Text("APK Installer")
                            if (filesState.scanning) {
                                CircularProgressIndicator(Modifier.padding(start = 12.dp).size(16.dp), strokeWidth = 2.dp)
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { nav.navigateTop("settings") }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Paramètres")
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    Tab("home", "Accueil", Icons.Filled.Home, current, nav)
                    Tab("files", "Fichiers", Icons.AutoMirrored.Filled.List, current, nav)
                    Tab("settings", "Paramètres", Icons.Filled.Settings, current, nav)
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
                composable("home") {
                    HomeScreen(
                        install = installVm,
                        files = filesVm,
                        onPickFile = { pickFile.launch(pickMimes) },
                        onOpenInstallSettings = { startActivity((application as ApkInstallerApp).container.installer.installPermissionSettingsIntent()) },
                        onSeeAllFiles = { nav.navigateTop("files") },
                    )
                }
                composable("files") {
                    FilesScreen(filesVm) { uri ->
                        installVm.onFileSelected(uri)
                        nav.navigateTop("home")
                    }
                }
                composable("settings") { SettingsScreen(settingsVm) }
            }
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.RowScope.Tab(
        route: String,
        label: String,
        icon: ImageVector,
        current: String?,
        nav: NavHostController,
    ) {
        NavigationBarItem(
            selected = current == route,
            onClick = { nav.navigateTop(route) },
            icon = { Icon(icon, contentDescription = null) },
            label = { Text(label) },
        )
    }
}

private fun NavHostController.navigateTop(route: String) {
    navigate(route) {
        popUpTo("home") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
