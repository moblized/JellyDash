package com.moblized.jellydash.safety

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat

/**
 * Feeds [MotionGuard] with speed: the truck's own, when Android Auto shares it, and the phone's GPS.
 */
class SpeedMonitor(private val context: Context, private val guard: MotionGuard) {
    private var carContext: CarContext? = null
    private var carListener: OnCarDataAvailableListener<Speed>? = null
    private var gpsListener: LocationListener? = null

    /** Whether the phone's GPS is being read. False until location is allowed on the phone. */
    var gpsActive = false
        private set

    /** Whether the truck has sent its speed at least once. */
    var carSpeedSeen = false
        private set

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start(car: CarContext?) {
        startGps()
        if (car != null && carListener == null) startCar(car)
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (gpsListener != null || !hasLocationPermission()) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = read(location)
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}

            @Deprecated("Needed below Android 10")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
            gpsListener = listener
            gpsActive = true
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
            // No GPS on this device.
        }
    }

    private fun read(location: Location) {
        if (!location.hasSpeed()) return
        val accuracy = if (Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null
        guard.onSpeed(location.speed, MotionGuard.Source.Gps, accuracy)
    }

    private fun startCar(car: CarContext) {
        val level = runCatching { car.carAppApiLevel }.getOrDefault(0)
        if (level < 3) return
        val listener = OnCarDataAvailableListener<Speed> { speed ->
            val raw = speed.rawSpeedMetersPerSecond
            val value = raw.value
            if (raw.status == CarValue.STATUS_SUCCESS && value != null) {
                carSpeedSeen = true
                guard.onSpeed(value, MotionGuard.Source.Car)
            }
        }
        try {
            car.getCarService(CarHardwareManager::class.java).carInfo
                .addSpeedListener(ContextCompat.getMainExecutor(context), listener)
            carListener = listener
            carContext = car
        } catch (_: Exception) {
            // The head unit or Android Auto version doesn't share speed. GPS still works.
        }
    }

    fun stop() {
        gpsListener?.let { l ->
            runCatching { context.getSystemService(LocationManager::class.java)?.removeUpdates(l) }
        }
        gpsListener = null
        gpsActive = false
        val car = carContext
        val l = carListener
        if (car != null && l != null) {
            runCatching { car.getCarService(CarHardwareManager::class.java).carInfo.removeSpeedListener(l) }
        }
        carListener = null
        carContext = null
    }
}
