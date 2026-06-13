package com.letta.mobile.desktop

import com.letta.mobile.desktop.data.defaultDesktopStateDirectory
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class DesktopIpcCommand(val path: String) {
    ShowUserThatAppIsRunning("/commands/show-user-that-app-is-running"),
}

internal sealed interface DesktopSingleInstance {
    class Primary internal constructor(
        private val lockChannel: FileChannel,
        private val lock: FileLock,
        private val server: DesktopInstanceIpcServer,
        private val portFile: Path,
    ) : DesktopSingleInstance, AutoCloseable {
        val port: Int = server.port
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { server.close() }
            runCatching { Files.deleteIfExists(portFile) }
            runCatching { lock.release() }
            runCatching { lockChannel.close() }
        }
    }

    class Secondary internal constructor(
        private val portFile: Path,
        private val timeoutMillis: Int = DEFAULT_IPC_TIMEOUT_MILLIS,
    ) : DesktopSingleInstance {
        fun notifyPrimary(): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
            do {
                val port = runCatching {
                    Files.readString(portFile).trim().toInt()
                }.getOrNull()

                if (port != null && postShowCommand(port)) return true
                try {
                    Thread.sleep(IPC_RETRY_DELAY_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            } while (System.nanoTime() < deadline)
            return false
        }

        private fun postShowCommand(port: Int): Boolean =
            runCatching {
                val url = URI(
                    "http",
                    null,
                    LOOPBACK_HOST,
                    port,
                    DesktopIpcCommand.ShowUserThatAppIsRunning.path,
                    null,
                    null,
                ).toURL()
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = timeoutMillis
                    connection.readTimeout = timeoutMillis
                    connection.responseCode in 200..299
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
    }

    companion object {
        fun acquire(
            stateDirectory: Path = defaultDesktopStateDirectory(),
            onCommand: (DesktopIpcCommand) -> Unit,
        ): DesktopSingleInstance {
            Files.createDirectories(stateDirectory)
            val lockFile = stateDirectory.resolve(LOCK_FILE_NAME)
            val portFile = stateDirectory.resolve(PORT_FILE_NAME)
            val lockChannel = FileChannel.open(lockFile, CREATE, WRITE)
            val lock = try {
                lockChannel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }

            if (lock == null) {
                lockChannel.close()
                return Secondary(portFile)
            }

            try {
                val server = DesktopInstanceIpcServer.start(onCommand)
                Files.writeString(
                    portFile,
                    server.port.toString(),
                    CREATE,
                    TRUNCATE_EXISTING,
                    WRITE,
                )
                return Primary(
                    lockChannel = lockChannel,
                    lock = lock,
                    server = server,
                    portFile = portFile,
                )
            } catch (throwable: Throwable) {
                runCatching { lock.release() }
                runCatching { lockChannel.close() }
                throw throwable
            }
        }
    }
}

internal class DesktopWindowActivationHandler {
    private val windowRef = AtomicReference<Window?>()

    fun attach(window: Window) {
        windowRef.set(window)
    }

    fun showUserThatAppIsRunning() {
        EventQueue.invokeLater {
            val window = windowRef.get() ?: return@invokeLater
            window.isVisible = true
            if (window is Frame) {
                window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
            }
            window.toFront()
            window.requestFocus()
        }
    }
}

internal class DesktopInstanceIpcServer private constructor(
    private val serverSocket: ServerSocket,
    private val onCommand: (DesktopIpcCommand) -> Unit,
    private val executor: ExecutorService,
) : AutoCloseable {
    val port: Int = serverSocket.localPort

    private fun serve() {
        while (!serverSocket.isClosed) {
            try {
                serverSocket.accept().use(::handle)
            } catch (_: IOException) {
                if (serverSocket.isClosed) return
                // Ignore malformed local clients and keep accepting the fixed IPC command.
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = DEFAULT_IPC_TIMEOUT_MILLIS
        val reader = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
        val requestLine = reader.readLine().orEmpty()
        while (reader.readLine()?.isNotEmpty() == true) {
            // Headers are not needed for the fixed command surface.
        }

        val command = DesktopIpcCommand.entries.firstOrNull { command ->
            requestLine == "POST ${command.path} HTTP/1.1" ||
                requestLine == "POST ${command.path} HTTP/1.0"
        }
        if (command != null) {
            onCommand(command)
            socket.writeResponse("204 No Content")
        } else {
            socket.writeResponse("404 Not Found")
        }
    }

    override fun close() {
        runCatching { serverSocket.close() }
        executor.shutdownNow()
    }

    private fun Socket.writeResponse(status: String) {
        val response = "HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        getOutputStream().write(response.toByteArray(StandardCharsets.US_ASCII))
    }

    companion object {
        fun start(onCommand: (DesktopIpcCommand) -> Unit): DesktopInstanceIpcServer {
            val socket = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_HOST))
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "LettaDesktopIpc").apply { isDaemon = true }
            }
            val server = DesktopInstanceIpcServer(socket, onCommand, executor)
            try {
                executor.execute(server::serve)
                return server
            } catch (throwable: Throwable) {
                server.close()
                throw throwable
            }
        }
    }
}

private const val LOOPBACK_HOST = "127.0.0.1"
private const val LOCK_FILE_NAME = "app.lock"
private const val PORT_FILE_NAME = "app.port"
private const val DEFAULT_IPC_TIMEOUT_MILLIS = 1_000
private const val IPC_RETRY_DELAY_MILLIS = 50L
