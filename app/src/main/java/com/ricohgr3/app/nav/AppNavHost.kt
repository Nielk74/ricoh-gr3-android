package com.ricohgr3.app.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ricohgr3.app.MainViewModel
import com.ricohgr3.app.ui.CameraScreen
import com.ricohgr3.app.ui.theme.GrTheme

/**
 * App navigation graph. [Screen.Connect] is the start destination and hosts the
 * existing [CameraScreen]; the remaining routes are placeholders that later phases
 * replace with real screens.
 */
@Composable
fun AppNavHost(
    viewModel: MainViewModel,
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
            GalleryPlaceholder(
                onOpenPhoto = { photoId ->
                    navController.navigate(Screen.Viewer.buildRoute(photoId))
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Screen.Viewer.route,
            arguments = listOf(navArgument(Screen.PHOTO_ID_ARG) { type = NavType.StringType }),
        ) { backStackEntry ->
            val photoId = backStackEntry.arguments?.getString(Screen.PHOTO_ID_ARG).orEmpty()
            ViewerPlaceholder(photoId = photoId, onBack = { navController.popBackStack() })
        }
        composable(Screen.LiveView.route) {
            SimplePlaceholder(title = "Live View", onBack = { navController.popBackStack() })
        }
        composable(Screen.Settings.route) {
            SimplePlaceholder(title = "Settings", onBack = { navController.popBackStack() })
        }
    }
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

/**
 * Temporary gallery stand-in — replaced by the real contact sheet in Phase 6d.
 * Tapping it opens the viewer with a sample id.
 */
@Composable
private fun GalleryPlaceholder(
    onOpenPhoto: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable { onOpenPhoto("sample-001") }
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Gallery", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = GrTheme.colors.ink)
        Spacer(Modifier.height(8.dp))
        Text("Tap anywhere to open a sample photo", fontSize = 13.sp, color = GrTheme.colors.inkSoft)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}

/**
 * Temporary single-photo viewer — replaced by the real viewer in Phase 6e.
 */
@Composable
private fun ViewerPlaceholder(
    photoId: String,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Viewer", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = GrTheme.colors.ink)
        Spacer(Modifier.height(8.dp))
        Text("photoId: $photoId", fontSize = 13.sp, color = GrTheme.colors.inkSoft)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

/**
 * Minimal named placeholder for routes not yet built — replaced by Phase 6d/6e.
 */
@Composable
private fun SimplePlaceholder(
    title: String,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = GrTheme.colors.ink)
        Spacer(Modifier.height(4.dp))
        Text("Placeholder — replaced by Phase 6d/6e", fontSize = 13.sp, color = GrTheme.colors.inkSoft)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}
