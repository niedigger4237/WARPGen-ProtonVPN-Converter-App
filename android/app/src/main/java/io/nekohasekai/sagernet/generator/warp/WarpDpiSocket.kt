package io.nekohasekai.sagernet.generator.warp

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketImpl
import java.net.SocketOptions
import javax.net.SocketFactory

/** Exposes the private AF_UNIX descriptor to Android's platform TLS socket. */
internal class WarpDpiSocketFactory(private val engine: NativeByeDpi) : SocketFactory() {
    override fun createSocket(): Socket = PrivateSocket(PrivateSocketImpl(engine))
    override fun createSocket(host: String, port: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = unsupported()

    private class PrivateSocket(impl: SocketImpl) : Socket(impl)

    private class PrivateSocketImpl(private val engine: NativeByeDpi) : SocketImpl() {
        private var descriptor: ParcelFileDescriptor? = null
        private var timeout = 0

        override fun create(stream: Boolean) {
            if (!stream) unsupported()
        }

        override fun connect(host: String, port: Int) = unsupported()
        override fun connect(host: InetAddress, port: Int) = unsupported()

        @Synchronized
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            if (endpoint !is InetSocketAddress || endpoint.port != 443 ||
                endpoint.hostString != WarpApi.HOST || descriptor != null) unsupported()
            val stream = engine.openWarpStream()
            descriptor = stream
            fd = stream.fileDescriptor
            address = endpoint.address
            port = endpoint.port
            try {
                applyTimeout()
            } catch (e: IOException) {
                close()
                throw e
            }
        }

        override fun bind(host: InetAddress, port: Int) = unsupported()
        override fun listen(backlog: Int) = unsupported()
        override fun accept(socket: SocketImpl) = unsupported()
        override fun sendUrgentData(data: Int) = unsupported()
        override fun available() = 0

        override fun getInputStream() = ParcelFileDescriptor.AutoCloseInputStream(stream())
        override fun getOutputStream() = ParcelFileDescriptor.AutoCloseOutputStream(stream())

        @Synchronized
        private fun stream() = descriptor ?: throw SocketException("Private WARP stream is closed")

        @Synchronized
        override fun close() {
            val current = descriptor
            descriptor = null
            if (current != null) {
                // Wake a TLS read on another thread before invalidating the descriptor.
                try {
                    Os.shutdown(current.fileDescriptor, OsConstants.SHUT_RDWR)
                } catch (_: ErrnoException) {
                    // The peer can already have exited.
                } finally {
                    current.close()
                }
            }
        }

        @Synchronized
        override fun setOption(option: Int, value: Any) {
            when (option) {
                SocketOptions.SO_TIMEOUT -> {
                    timeout = value as Int
                    applyTimeout()
                }
                // Applied to the remote TCP socket by ByeDPI.
                SocketOptions.TCP_NODELAY -> Unit
                else -> unsupported()
            }
        }

        @Synchronized
        override fun getOption(option: Int): Any = when (option) {
            SocketOptions.SO_TIMEOUT -> timeout
            SocketOptions.TCP_NODELAY -> true
            else -> unsupported()
        }

        private fun applyTimeout() {
            descriptor?.let { NativeByeDpi.setReadTimeout(it, timeout) }
        }
    }

    private companion object {
        fun unsupported(): Nothing = throw SocketException("Unsupported private WARP socket operation")
    }
}
