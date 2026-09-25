package com.surveyme.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.surveyme.service.LocationTrackingService

/**
 * "Where I stand" for the create-note entry points (item 7): the tracking service's last fix
 * when it is running, else the platform's last known GPS or network fix. Null without location
 * permission or before any fix; the caller says so.
 */
object LastKnownLocation {
    fun get(context: Context): Location? {
        LocationTrackingService.lastLocationFlow.value?.let { return it }
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }
    }
}
