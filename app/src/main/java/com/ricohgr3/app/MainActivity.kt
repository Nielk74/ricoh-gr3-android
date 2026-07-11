package com.ricohgr3.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ricohgr3.app.data.PhotoRepository
import com.ricohgr3.app.gallery.GalleryViewModel
import com.ricohgr3.app.looks.StickyLookStore
import com.ricohgr3.app.nav.AppNavHost
import com.ricohgr3.app.ui.theme.GrTheme
import com.ricohgr3.app.wifi.CameraHttpClient

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
                    val photoRepository = remember { PhotoRepository(CameraHttpClient()) }
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

                    AppNavHost(
                        viewModel = vm,
                        galleryViewModel = galleryViewModel,
                        photoRepository = photoRepository,
                        permissionsGranted = granted,
                        onRequestPermissions = { launcher.launch(requiredPermissions()) },
                    )
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
