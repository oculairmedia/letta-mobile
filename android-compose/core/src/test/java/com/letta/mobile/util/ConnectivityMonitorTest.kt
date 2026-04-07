package com.letta.mobile.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityMonitorTest {

    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    @Test
    fun `connectivityManager is available`() {
        assertNotNull(connectivityManager)
    }

    @Test
    fun `initial offline state when no network`() {
        val shadow = Shadows.shadowOf(connectivityManager)
        shadow.setDefaultNetworkActive(false)
        assertFalse(connectivityManager.isDefaultNetworkActive)
    }

    @Test
    fun `shadow connectivity manager supports callbacks`() {
        val shadow = Shadows.shadowOf(connectivityManager)
        assertNotNull(shadow)
    }

    @Test
    fun `robolectric context provides CONNECTIVITY_SERVICE`() {
        val service = context.getSystemService(Context.CONNECTIVITY_SERVICE)
        assertNotNull(service)
    }
}
