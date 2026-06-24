package io.legado.app.help.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import io.legado.app.constant.AppLog
import splitties.init.appCtx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object NetworkQualityInterceptor {

    enum class NetworkType { WIFI, CELLULAR, NONE }

    data class NetworkState(
        val type: NetworkType = NetworkType.NONE,
        val isCharging: Boolean = false,
        val canExecuteBatch: Boolean = false,
        val downloadSpeedLimit: Long = 0L // 0 = unlimited, bytes/sec
    )

    private val _state = MutableStateFlow(NetworkState())
    val state: kotlinx.coroutines.flow.StateFlow<NetworkState> get() = _state

    private val connectivityManager = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var isRegistered = false

    fun startMonitoring() {
        if (isRegistered) return
        isRegistered = true
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { updateState() }
            override fun onLost(network: Network) { updateState() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { updateState() }
        })
        updateState()
    }

    fun stopMonitoring() {
        if (!isRegistered) return
        connectivityManager.unregisterNetworkCallback(networkCallback)
        isRegistered = false
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { updateState() }
        override fun onLost(network: Network) { updateState() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { updateState() }
    }

    fun updateState() {
        try {
            val caps = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            val type = when {
                caps == null -> NetworkType.NONE
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
                else -> NetworkType.NONE
            }
            val bm = appCtx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val isCharging = bm.isCharging

            val canExecuteBatch = type == NetworkType.WIFI && isCharging
            val speedLimit = if (type == NetworkType.CELLULAR) 1024 * 1024L else 0L // 1MB/s on cellular

            _state.value = NetworkState(type, isCharging, canExecuteBatch, speedLimit)
        } catch (e: Exception) {
            AppLog.put("NetworkQuality update error", e)
        }
    }

    fun canExecute(taskPriority: Int): Boolean {
        val state = _state.value
        return when {
            state.type == NetworkType.NONE -> false
            taskPriority > 0 -> true // High priority (foreground) always allowed
            state.canExecuteBatch -> true
            else -> false // Low priority blocked on non-WiFi
        }
    }

    /**
     * Returns the current network state (type, charging, batch eligibility, speed
     * limit). Callers use this to decide whether to poll low-priority tasks.
     */
    fun currentNetworkInfo(): NetworkState {
        return _state.value
    }
}
