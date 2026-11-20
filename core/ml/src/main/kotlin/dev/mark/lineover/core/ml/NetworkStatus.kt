package dev.mark.lineover.core.ml

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Whether the device can download now, and over what (Stage 16). */
interface NetworkStatus {
    fun isOnline(): Boolean

    /** Wi-Fi, Ethernet or another network that isn't metered. */
    fun isUnmetered(): Boolean
}

/** [NetworkStatus] from the active network's capabilities. */
class AndroidNetworkStatus(
    context: Context,
) : NetworkStatus {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private fun capabilities(): NetworkCapabilities? = connectivity?.let { it.getNetworkCapabilities(it.activeNetwork) }

    override fun isOnline(): Boolean = capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    override fun isUnmetered(): Boolean = capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
}
