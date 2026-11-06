package com.moblized.jellydash.car

import android.content.Intent
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.moblized.jellydash.Graph
import com.moblized.jellydash.safety.SpeedMonitor

/** Android Auto's way in. One session per connection to the car. */
class JellyDashCarService : CarAppService() {
    // A sideloaded app for your own use: any Android Auto host may connect.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = JellyDashSession()
}

class JellyDashSession : Session() {
    private var speed: SpeedMonitor? = null
    private var surface: CarVideoSurface? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val guard = Graph.guard()
        // A new drive starts with a clean slate: video waits for the first "Play" or "I'm parked".
        guard.reset()
        val monitor = SpeedMonitor(carContext.applicationContext, guard).also { speed = it }
        monitor.start(carContext)

        val s = CarVideoSurface(carContext).also { surface = it }
        runCatching { carContext.getCarService(AppManager::class.java).setSurfaceCallback(s) }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Location may have been allowed on the phone since the car connected.
                speed?.start(carContext)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                Graph.playback(carContext).stop()
                speed?.stop()
                surface?.release()
                if (CarVideoSurface.current === surface) CarVideoSurface.current = null
            }
        })
        return HomeScreen(carContext)
    }
}
