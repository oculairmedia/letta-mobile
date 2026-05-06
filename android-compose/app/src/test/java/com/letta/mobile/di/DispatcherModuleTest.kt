package com.letta.mobile.di

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class DispatcherModuleTest {

    private val module = DispatcherModule

    @Test
    fun `IoDispatcher provides Dispatchers_IO`() {
        assertSame(Dispatchers.IO, module.provideIoDispatcher())
    }

    @Test
    fun `MainDispatcher provides Dispatchers_Main`() {
        assertSame(Dispatchers.Main, module.provideMainDispatcher())
    }

    @Test
    fun `DefaultDispatcher provides Dispatchers_Default`() {
        assertSame(Dispatchers.Default, module.provideDefaultDispatcher())
    }

    @Test
    fun `dispatchers are thread-safe singletons`() {
        val io1 = module.provideIoDispatcher()
        val io2 = module.provideIoDispatcher()
        assertSame(io1, io2)
    }
}
