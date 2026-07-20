package com.ricohgr3.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ricohgr3.app.data.PhotoRepository
import com.ricohgr3.app.gallery.GalleryViewModel
import com.ricohgr3.app.looks.StickyLookStore
import com.ricohgr3.app.nav.AppNavHost
import com.ricohgr3.app.ui.theme.GrTheme
import com.ricohgr3.app.ui.update.UpdateBanner
import com.ricohgr3.app.update.UpdateStatus
import com.ricohgr3.app.wifi.CameraHttpClient
import com.ricohgr3.app.wifi.SessionBoundWifiController

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GrTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: MainViewModel = viewModel()

                    // Wi-Fi photo layer + shared "edit core" ViewModel, built once per session.
                    // The Wi-Fi client talks to the camera AP (http://192.168.0.1/v1/); it is
                    // only reachable once the phone has joined that AP (Phase 4/5 handoff).
                    val appContext = applicationContext
                    // The controller injected into the gallery + live-view screens must route to
                    // the camera AP's *bound* Network — that controller only exists once the Wi-Fi
                    // session reaches Connected, and is rebuilt on every reconnect. So we hand the
                    // screens a SessionBoundWifiController that forwards to the session's live
                    // controller on each call. On API < 29 there's no session (Wi-Fi unsupported);
                    // fall back to a plain client so construction still succeeds.
                    val cameraWifiController = remember(vm.wifiSession) {
                        vm.wifiSession?.let { SessionBoundWifiController(it) } ?: CameraHttpClient()
                    }
                    val photoRepository = remember(cameraWifiController) { PhotoRepository(cameraWifiController) }
                    val photoExporter = remember(appContext) { com.ricohgr3.app.data.PhotoExporter(appContext) }
                    val filmLookLoader = remember(appContext) { com.ricohgr3.app.looks.emulation.FilmLookLoader(appContext) }
                    val stickyLookStore = remember { StickyLookStore(appContext) }
                    val galleryViewModel: GalleryViewModel = viewModel(
                        factory = GalleryViewModel.Factory(photoRepository, stickyLookStore),
                    )

                    var granted by remember { mutableStateOf(hasBlePermissions()) }
                    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { result ->
                        granted = result.values.all { it }
                    }

                    val updateStatus by vm.updateStatus.collectAsStateWithLifecycle()
                    val updateDownload by vm.updateDownload.collectAsStateWithLifecycle()
                    val updateDismissed by vm.updateDismissed.collectAsStateWithLifecycle()

                    Column(modifier = Modifier.fillMaxSize()) {
                        AnimatedVisibility(
                            visible = !updateDismissed && updateStatus is UpdateStatus.Available,
                            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                        ) {
                            UpdateBanner(
                                status = updateStatus,
                                download = updateDownload,
                                onInstall = vm::downloadAndInstallUpdate,
                                onDismiss = vm::dismissUpdate,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            AppNavHost(
                                viewModel = vm,
                                galleryViewModel = galleryViewModel,
                                photoRepository = photoRepository,
                                photoExporter = photoExporter,
                                filmLookLoader = filmLookLoader,
                                cameraWifiController = cameraWifiController,
                                permissionsGranted = granted,
                                onRequestPermissions = { launcher.launch(requiredPermissions()) },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    private fun hasBlePermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
}
