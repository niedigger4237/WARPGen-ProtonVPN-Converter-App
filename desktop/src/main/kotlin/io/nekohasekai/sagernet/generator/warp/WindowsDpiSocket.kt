package io.nekohasekai.sagernet.generator.warp

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import javax.net.SocketFactory

/** A normal JVM TCP socket backed by a single-use loopback bridge.
 * The native listener is closed immediately after matching our source port.
 * OkHttp still layers TLS for the real WARP hostname.
 */
internal class WindowsDpiSocketFactory(private val engine: WindowsByeDpi) : SocketFactory() {
    override fun createSocket(): Socket = PrivateSocket(engine)
    override fun createSocket(host: String, port: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = unsupported()
    private class PrivateSocket(private val engine: WindowsByeDpi) : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            if (endpoint !is InetSocketAddress || endpoint.hostString != WarpApi.HOST || endpoint.port != 443)
                unsupported()
            try {
                val port = engine.prepare()
                super.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), timeout)
                engine.connect(localPort)
            } catch (e: Exception) {
                close()
                throw e
            }
        }
    }
    private companion object {
        fun unsupported(): Nothing = throw SocketException("Unsupported private WARP socket operation")
    }
}
