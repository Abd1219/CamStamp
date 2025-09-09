package com.abdapps.camstamp

import android.app.Application
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig

class CamStampApplication : Application(), CameraXConfig.Provider {
    override fun getCameraXConfig(): CameraXConfig {
        // Using the builder pattern explicitly
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig()).build()
    }
}
