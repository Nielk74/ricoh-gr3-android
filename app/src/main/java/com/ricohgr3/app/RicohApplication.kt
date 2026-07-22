package com.ricohgr3.app

import android.app.Application
import com.ricohgr3.app.gallery.AutoImportManager
import com.ricohgr3.app.wifi.CameraWifiSession

/** Owns long-lived camera/import state that must outlive any one activity instance. */
class RicohApplication : Application() {
    val wifiSession: CameraWifiSession? by lazy {
        CameraWifiSession.createOrNull(applicationContext)
    }

    internal val autoImportManager: AutoImportManager by lazy {
        AutoImportManager(this)
    }
}
