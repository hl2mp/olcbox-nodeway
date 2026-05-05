import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import org.olcbox.app.data.datasource.JvmLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.JvmLogExporter
import org.olcbox.app.data.importer.JvmConfigImporter
import org.olcbox.app.ui.OlcboxAppContent
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.navigation.AppScreen
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.vpn.DesktopVpnManager

private class DesktopAppDependencies {
    private val locationsDataSource = JvmLocationsDataSourceImpl()
    val locationsRepository = LocationsRepositoryImpl(locationsDataSource)
    val vpnManager = DesktopVpnManager(locationsRepository)

    val homeViewModel = HomeScreenViewModel(
        vpnManager = vpnManager,
        locationsRepository = locationsRepository,
        configImporter = JvmConfigImporter(),
        logExporter = JvmLogExporter()
    )

    val locationViewModel = LocationViewModel(locationsRepository)

    fun close() {
        vpnManager.close()
    }
}

fun main() = application {
    val dependencies = remember { DesktopAppDependencies() }
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }

    Window(
        title = "olcbox",
        state = rememberWindowState(width = 430.dp, height = 780.dp),
        onCloseRequest = {
            dependencies.close()
            exitApplication()
        },
    ) {
        window.minimumSize = Dimension(350, 600)

        DisposableEffect(Unit) {
            onDispose { dependencies.close() }
        }

        AppTheme {
            OlcboxAppContent(
                homeViewModel = dependencies.homeViewModel,
                locationViewModel = dependencies.locationViewModel,
                currentScreen = currentScreen,
                onNavigate = { screen ->
                    currentScreen = screen
                },
                onToggleClick = {
                    dependencies.homeViewModel.ToggleVpn()
                },
                onImportFileRequested = {
                    chooseConfigFile(window)?.let { file ->
                        dependencies.homeViewModel.onFileSelected(file) {
                            dependencies.locationViewModel.loadLocations()
                            dependencies.homeViewModel.loadCurrentConfig()
                        }
                    }
                },
                onImportFromClipboardRequested = {
                    dependencies.homeViewModel.onPasteFromClipboard {
                        dependencies.locationViewModel.loadLocations()
                        dependencies.homeViewModel.loadCurrentConfig()
                    }
                },
                onCopyConfigRequested = {
                    dependencies.homeViewModel.onCopyFullConfigClicked()
                },
                onSaveLogsRequested = { onSaved, onError ->
                    chooseSaveFile(
                        owner = window,
                        defaultName = dependencies.homeViewModel.suggestedLogsFileName()
                    )?.let { file ->
                        dependencies.homeViewModel.onSaveLogsToFile(
                            target = file,
                            onSaved = onSaved,
                            onError = onError
                        )
                    }
                },
                showAppSettingsButton = false,
                onAppSettingsClick = {}
            )
        }
    }
}

private fun chooseConfigFile(owner: Frame): File? {
    val dialog = FileDialog(owner, "Import Olcbox Config", FileDialog.LOAD)
    dialog.isVisible = true
    return dialog.files.firstOrNull()
}

private fun chooseSaveFile(owner: Frame, defaultName: String): File? {
    val dialog = FileDialog(owner, "Save Olcbox Logs", FileDialog.SAVE)
    dialog.file = defaultName
    dialog.isVisible = true

    val fileName = dialog.file ?: return null
    val directory = dialog.directory ?: return File(fileName)
    return File(directory, fileName)
}