package com.letta.mobile.runtime.screen

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@Singleton
class AndroidScreenCaptureProvider @Inject constructor(
    application: Application,
) : ScreenCaptureProvider {
    private val activityTracker = ForegroundActivityTracker()

    init {
        application.registerActivityLifecycleCallbacks(activityTracker)
    }

    override fun captureOwnWindow(maxDimension: Int): ScreenCaptureResult = runBlocking {
        val boundedMaxDimension = maxDimension.coerceIn(MIN_MAX_DIMENSION, MAX_MAX_DIMENSION)
        val bitmap = withContext(Dispatchers.Main.immediate) {
            val activity = activityTracker.currentActivity()
                ?: error("No resumed activity is available for own-window capture.")
            val root = activity.window.decorView.rootView
            captureRootView(root, boundedMaxDimension)
        }
        try {
            withContext(Dispatchers.Default) { encodeBitmap(bitmap) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun captureRootView(root: View, maxDimension: Int): Bitmap {
        val width = root.width
        val height = root.height
        require(width > 0 && height > 0) { "Root view has no drawable bounds." }
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(width, height).toFloat())
        val outputWidth = (width * scale).toInt().coerceAtLeast(1)
        val outputHeight = (height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        root.draw(canvas)
        return bitmap
    }

    private fun encodeBitmap(bitmap: Bitmap): ScreenCaptureResult {
        val stream = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, stream)) { "Bitmap PNG encoding failed." }
        return ScreenCaptureResult(
            imageBase64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP),
            width = bitmap.width,
            height = bitmap.height,
            mimeType = PNG_MIME_TYPE,
        )
    }

    private class ForegroundActivityTracker : Application.ActivityLifecycleCallbacks {
        private val current = AtomicReference<Activity?>()

        override fun onActivityResumed(activity: Activity) {
            current.set(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            current.compareAndSet(activity, null)
        }

        override fun onActivityDestroyed(activity: Activity) {
            current.compareAndSet(activity, null)
        }

        fun currentActivity(): Activity? {
            if (Looper.myLooper() == Looper.getMainLooper()) return current.get()
            val result = AtomicReference<Activity?>()
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                result.set(current.get())
                latch.countDown()
            }
            latch.await(MAIN_THREAD_WAIT_MS, TimeUnit.MILLISECONDS)
            return result.get()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    companion object {
        const val DEFAULT_MAX_DIMENSION = 1280
        private const val MIN_MAX_DIMENSION = 320
        private const val MAX_MAX_DIMENSION = 1920
        private const val MAIN_THREAD_WAIT_MS = 1_000L
        private const val PNG_QUALITY = 100
        private const val PNG_MIME_TYPE = "image/png"
    }
}
