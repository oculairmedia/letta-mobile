package com.letta.mobile.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.letta.mobile.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val httpClient: HttpClient,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isServerReachable = MutableStateFlow(false)
    val isServerReachable: StateFlow<Boolean> = _isServerReachable.asStateFlow()

    private var isMonitoring = false
    private var lastPingTime = 0L

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isOnline.value = true
            checkServerReachability()
        }

        override fun onLost(network: Network) {
            _isOnline.value = false
            _isServerReachable.value = false
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            _isOnline.value = hasInternet
            if (hasInternet) {
                checkServerReachability()
            }
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        startMonitoring()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (!isMonitoring) {
            startMonitoring()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        stopMonitoring()
    }

    private fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)

        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        _isOnline.value = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        scope.launch {
            settingsRepository.activeConfig.collectLatest { config ->
                if (config != null && _isOnline.value) {
                    checkServerReachability()
                }
            }
        }
    }

    private fun stopMonitoring() {
        if (!isMonitoring) return
        isMonitoring = false
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    private fun checkServerReachability() {
        val now = System.currentTimeMillis()
        if (now - lastPingTime < 5000) return
        lastPingTime = now

        scope.launch {
            delay(200)
            
            val config = settingsRepository.activeConfig.value ?: return@launch
            val url = "${config.serverUrl}/v1/agents?limit=1"

            try {
                val response: HttpResponse = httpClient.get(url)
                _isServerReachable.value = response.status.value in 200..299
            } catch (e: Exception) {
                _isServerReachable.value = false
            }
        }
    }
}
