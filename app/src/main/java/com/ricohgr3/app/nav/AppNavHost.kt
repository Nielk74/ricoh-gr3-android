package com.ricohgr3.app.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ricohgr3.app.MainViewModel
import com.ricohgr3.app.data.PhotoId
import com.ricohgr3.app.data.PhotoRepository
import com.ricohgr3.app.gallery.GalleryScreen
import com.ricohgr3.app.gallery.GalleryViewModel
import com.ricohgr3.app.gallery.ViewerScreen
import com.ricohgr3.app.liveview.LiveViewScreen
import com.ricohgr3.app.liveview.LiveViewViewModel
import com.ricohgr3.app.ui.CameraScreen
import com.ricohgr3.app.wifi.CameraWifiController
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * App navigation graph. [Screen.Connect] is the start destination and hosts the existing
 * [CameraScreen]; [Screen.Gallery] and [Screen.Viewer] host the real Phase 6 screens, sharing
 * one [GalleryViewModel] (the "edit core") so a look applied in the viewer shows instantly as
 * an edited mark back in the gallery.
 */
@Composable
fun AppNavHost(
    viewModel: MainViewModel,
    galleryViewModel: GalleryViewModel,
    photoRepository: PhotoRepository,
    // MVP-3: Wi-Fi controller for the live-view + shutter screen. Threaded from MainActivity
    // (same CameraHttpClient instance that backs the PhotoRepository).
    cameraWifiController: CameraWifiController,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Screen.Connect.route) {
        composable(Screen.Connect.route) {
            ConnectScreen(
                viewModel = viewModel,
                permissionsGranted = permissionsGranted,
                onRequestPermissions = onRequestPermissions,
                onOpenGallery = { navController.navigate(Screen.Gallery.route) },
            )
        }

        composable(Screen.Gallery.route) {
            val state by galleryViewModel.state.collectAsStateWithLifecycle()
            // Load the roll the first time the gallery is shown.
            LaunchedEffect(Unit) {
                if (state.photos.isEmpty() && !state.isLoading) galleryViewModel.refresh()
            }
            GalleryScreen(
                state = state,
                repository = photoRepository,
                onOpenPhoto = { id -> navController.navigate(Screen.Viewer.buildRoute(id.toString())) },
                onToggleSelect = galleryViewModel::toggleSelect,
                onClearSelection = galleryViewModel::clearSelection,
                onApplyLookToSelection = { look ->
                    galleryViewModel.applyLookToSelection(look)
                    galleryViewModel.clearSelection()
                },
                onStickyLookChange = galleryViewModel::setStickyLook,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.Viewer.route,
            arguments = listOf(navArgument(Screen.PHOTO_ID_ARG) { type = NavType.StringType }),
        ) { backStackEntry ->
            val raw = backStackEntry.arguments?.getString(Screen.PHOTO_ID_ARG).orEmpty()
            val photoId = parsePhotoId(raw)
            val state by galleryViewModel.state.collectAsStateWithLifecycle()

            if (photoId == null) {
                ViewerError(onBack = { navController.popBackStack() })
            } else {
                ViewerScreen(
                    id = photoId,
                    repository = photoRepository,
                    appliedLook = state.lookFor(photoId),
                    stickyLook = state.stickyLook,
                    onApplyLook = { look -> galleryViewModel.applyLook(photoId, look) },
                    onResetLook = { galleryViewModel.resetLook(photoId) },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        // MVP-3: real live-view + Wi-Fi shutter screen, replacing the placeholder. Its
        // ViewModel is scoped to this destination via LiveViewViewModel.Factory.
        composable(Screen.LiveView.route) {
            val liveViewModel: LiveViewViewModel = viewModel(
                factory = LiveViewViewModel.Factory(cameraWifiController),
            )
            LiveViewScreen(
                viewModel = liveViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Screen.Settings.route) {
            SimplePlaceholder(title = "Settings", onBack = { navController.popBackStack() })
        }
    }
}

/** Reconstruct a [PhotoId] from its `folder/file` route argument (see [PhotoId.toString]). */
private fun parsePhotoId(raw: String): PhotoId? {
    val slash = raw.indexOf('/')
    if (slash <= 0 || slash == raw.length - 1) return null
    return PhotoId(folder = raw.substring(0, slash), file = raw.substring(slash + 1))
}

/** Connect route content: the existing [CameraScreen] plus a hook to open the gallery. */
@Composable
private fun ConnectScreen(
    viewModel: MainViewModel,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        CameraScreen(
            viewModel = viewModel,
            permissionsGranted = permissionsGranted,
            onRequestPermissions = onRequestPermissions,
        )
        OutlinedButton(
            onClick = onOpenGallery,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            Text("Open gallery")
        }
    }
}

@Composable
private fun ViewerError(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Couldn't open that photo.")
        OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Back") }
    }
}

/** Minimal named placeholder for routes not yet built (live view, settings). */
@Composable
private fun SimplePlaceholder(
    title: String,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
    ) {
        Text(title)
        OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Back") }
    }
}
