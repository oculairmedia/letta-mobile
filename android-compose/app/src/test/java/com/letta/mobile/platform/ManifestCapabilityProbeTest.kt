package com.letta.mobile.platform

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.jupiter.api.Tag

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@Tag("integration")
class ManifestCapabilityProbeTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager

    @Before
    fun setUp() {
        context = mockk()
        packageManager = mockk()
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.letta.mobile"
    }

    // --- hasDeclaredPermission Tests ---

    @Test
    @Config(sdk = [32])
    fun `hasDeclaredPermission returns true when permission is requested (pre-Tiramisu)`() {
        val packageInfo = PackageInfo().apply {
            requestedPermissions = arrayOf("android.permission.INTERNET", "android.permission.CAMERA")
        }

        every {
            packageManager.getPackageInfo("com.letta.mobile", PackageManager.GET_PERMISSIONS)
        } returns packageInfo

        val result = ManifestCapabilityProbe.hasDeclaredPermission(context, "android.permission.INTERNET")
        assertTrue(result)
    }

    @Test
    @Config(sdk = [33])
    fun `hasDeclaredPermission returns true when permission is requested (Tiramisu+)`() {
        val packageInfo = PackageInfo().apply {
            requestedPermissions = arrayOf("android.permission.INTERNET", "android.permission.CAMERA")
        }

        every {
            packageManager.getPackageInfo(
                "com.letta.mobile",
                any<PackageManager.PackageInfoFlags>()
            )
        } returns packageInfo

        val result = ManifestCapabilityProbe.hasDeclaredPermission(context, "android.permission.INTERNET")
        assertTrue(result)
    }

    @Test
    @Config(sdk = [33])
    fun `hasDeclaredPermission returns false when permission is not requested`() {
        val packageInfo = PackageInfo().apply {
            requestedPermissions = arrayOf("android.permission.CAMERA")
        }

        every {
            packageManager.getPackageInfo(
                "com.letta.mobile",
                any<PackageManager.PackageInfoFlags>()
            )
        } returns packageInfo

        val result = ManifestCapabilityProbe.hasDeclaredPermission(context, "android.permission.INTERNET")
        assertFalse(result)
    }

    @Test
    @Config(sdk = [33])
    fun `hasDeclaredPermission returns false when requestedPermissions is null`() {
        val packageInfo = PackageInfo().apply {
            requestedPermissions = null
        }

        every {
            packageManager.getPackageInfo(
                "com.letta.mobile",
                any<PackageManager.PackageInfoFlags>()
            )
        } returns packageInfo

        val result = ManifestCapabilityProbe.hasDeclaredPermission(context, "android.permission.INTERNET")
        assertFalse(result)
    }

    // --- hasDeclaredService Tests ---

    @Test
    @Config(sdk = [32])
    fun `hasDeclaredService with Class returns true when service info is found (pre-Tiramisu)`() {
        val serviceClass = String::class.java
        val serviceInfo = ServiceInfo()

        every {
            packageManager.getServiceInfo(any(), PackageManager.GET_META_DATA)
        } returns serviceInfo

        val result = ManifestCapabilityProbe.hasDeclaredService(context, serviceClass)
        assertTrue(result)
    }

    @Test
    @Config(sdk = [33])
    fun `hasDeclaredService with Class returns true when service info is found (Tiramisu+)`() {
        val serviceClass = String::class.java
        val serviceInfo = ServiceInfo()

        every {
            packageManager.getServiceInfo(
                any(),
                any<PackageManager.ComponentInfoFlags>()
            )
        } returns serviceInfo

        val result = ManifestCapabilityProbe.hasDeclaredService(context, serviceClass)
        assertTrue(result)
    }

    @Test
    @Config(sdk = [33])
    fun `hasDeclaredService with Class returns false when getServiceInfo throws NameNotFoundException`() {
        val serviceClass = String::class.java

        every {
            packageManager.getServiceInfo(
                any(),
                any<PackageManager.ComponentInfoFlags>()
            )
        } throws PackageManager.NameNotFoundException()

        val result = ManifestCapabilityProbe.hasDeclaredService(context, serviceClass)
        assertFalse(result)
    }

    @Test
    @Config(sdk = [33])
    fun `hasDeclaredService with String returns true when service info is found`() {
        val serviceClassName = "com.letta.mobile.MyService"
        val serviceInfo = ServiceInfo()

        every {
            packageManager.getServiceInfo(
                any(),
                any<PackageManager.ComponentInfoFlags>()
            )
        } returns serviceInfo

        val result = ManifestCapabilityProbe.hasDeclaredService(context, serviceClassName)
        assertTrue(result)
    }

    // --- hasSystemFeature Tests ---

    @Test
    @Config(sdk = [33])
    fun `hasSystemFeature returns true when feature exists`() {
        every { packageManager.hasSystemFeature("android.hardware.camera") } returns true

        val result = ManifestCapabilityProbe.hasSystemFeature(context, "android.hardware.camera")
        assertTrue(result)
    }

    @Test
    @Config(sdk = [33])
    fun `hasSystemFeature returns false when feature does not exist`() {
        every { packageManager.hasSystemFeature("android.hardware.nfc") } returns false

        val result = ManifestCapabilityProbe.hasSystemFeature(context, "android.hardware.nfc")
        assertFalse(result)
    }
}
