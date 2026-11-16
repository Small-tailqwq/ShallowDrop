package io.github.smalltailqwq.shallowdrop.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.core.model.LocationMode
import io.github.smalltailqwq.shallowdrop.core.model.LocationPreference
import io.github.smalltailqwq.shallowdrop.core.model.locationForReminder
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AndroidLocationProvider(private val context: Context, private val ipApi: IpLocationApi = IpLocationApi()) {
    suspend fun locate(preference: LocationPreference, locale: Locale): String = when (preference.mode) {
        LocationMode.Off -> ""
        LocationMode.Custom -> locationForReminder(preference.custom).ifBlank { throw problem(R.string.location_custom_empty) }
        LocationMode.Gps -> gpsPlace(locale)
        LocationMode.Network -> try {
            locationForReminder(ipApi.locate(locale))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw problem(R.string.location_network_failed)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun gpsPlace(locale: Locale): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw problem(R.string.location_permission_required)
        }
        val manager = context.getSystemService(LocationManager::class.java)
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) throw problem(R.string.location_gps_disabled)
        val location = withContext(Dispatchers.Main) {
            withTimeoutOrNull(20_000) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    LocationManagerCompat.getCurrentLocation(manager, LocationManager.GPS_PROVIDER, signal, ContextCompat.getMainExecutor(context)) {
                        if (continuation.isActive) continuation.resume(it)
                    }
                }
            }
        } ?: throw problem(R.string.location_gps_timeout)
        val address = address(location, locale)
        val place = address?.let { listOfNotNull(it.countryName, it.adminArea, it.locality ?: it.subAdminArea).distinct().joinToString(" ") }
        // 无系统地址解析服务的设备仍可提供 GPS 位置；坐标约取到公里精度，不提供街道地址。
        return locationForReminder(place?.takeIf { it.isNotBlank() }
            ?: String.format(Locale.ROOT, "latitude %.2f longitude %.2f", location.latitude, location.longitude))
    }

    @Suppress("DEPRECATION")
    private suspend fun address(location: Location, locale: Locale): Address? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, locale)
        return try {
            withTimeoutOrNull(5_000) {
                if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                        }
                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    })
                } else runInterruptible(Dispatchers.IO) { geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull() }
            }
        } catch (_: IOException) { null }
    }

    private fun problem(id: Int) = IOException(context.getString(id))
}
