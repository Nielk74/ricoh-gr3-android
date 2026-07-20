package com.ricohgr3.app.nav

/**
 * Type-safe navigation routes for the app.
 *
 * Each entry owns its `route` string used with `NavHost`/`composable`. Routes that
 * take arguments expose a builder that produces a concrete path (see [Screen.Viewer]).
 */
sealed class Screen(val route: String) {

    /** Home: the existing BLE connect / shutter flow. */
    data object Connect : Screen("connect")

    /** Photo gallery (contact sheet). Real screen lands in a later phase. */
    data object Gallery : Screen("gallery")

    /** Single-photo viewer. Takes a [PHOTO_ID_ARG] path argument. */
    data object Viewer : Screen("viewer/{$PHOTO_ID_ARG}") {
        /**
         * Build a concrete viewer route from an **already-encoded** single-segment id
         * (see `PhotoId.toRouteArg`). The value must not contain a raw `/`, or the
         * `viewer/{photoId}` route will fail to match.
         */
        fun buildRoute(photoId: String): String = "viewer/$photoId"
    }

    /** Live view (MJPEG). Placeholder route. */
    data object LiveView : Screen("liveview")

    /** Manual application update check, reachable directly from the home screen. */
    data object AppUpdate : Screen("app-update")

    /** Camera settings. Placeholder route. */
    data object Settings : Screen("settings")

    companion object {
        /** Nav-argument key for the viewer's photo id. */
        const val PHOTO_ID_ARG = "photoId"
    }
}
