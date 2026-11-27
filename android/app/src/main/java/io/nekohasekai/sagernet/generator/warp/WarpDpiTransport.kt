package io.nekohasekai.sagernet.generator.warp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class WarpDpiTransport(private val context: Context) {
    private companion object {
        val registrationLock = Mutex()
    }

    suspend fun register(
        progress: (Int, Int, DpiStrategySelector.Strategy) -> Unit
    ): WarpRegistration.Account = registrationLock.withLock {
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val calls = RegistrationCalls()
                continuation.invokeOnCancellation { calls.cancel() }
                try {
                    continuation.resume(register(calls, progress))
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    private fun register(
        calls: RegistrationCalls,
        progress: (Int, Int, DpiStrategySelector.Strategy) -> Unit
    ): WarpRegistration.Account {
        val settings = DpiStrategyCatalog.settings(context)
        return DpiStrategySelector.withWorkingStrategy(
            DpiStrategyCatalog.available(context, settings),
            DpiStrategyCatalog.preferred(context, settings),
            { strategy ->
                val engine = NativeByeDpi.open(strategy)
                try {
                    ApiSession(engine, calls, settings.timeout)
                } catch (failure: Throwable) {
                    engine.close()
                    throw failure
                }
            },
            calls::checkCancelled, progress,
            ApiSession::probe
        ) { match, api ->
            val result = WarpRegistration.register { method, endpoint, body, token ->
                val call = api.client.newCall(WarpApi.buildRequest(method, endpoint, body, token))
                calls.execute(call, WarpApi::readResponse)
            }
            calls.checkCancelled()
            if (settings.rememberSuccessful) DpiStrategyCatalog.remember(context, match)
            result
        }
    }

    private class ApiSession(
        private val engine: NativeByeDpi,
        private val calls: RegistrationCalls,
        private val probeTimeout: Int
    ) : AutoCloseable {
        val client: OkHttpClient = WarpApi.clientBuilder()
            .proxy(Proxy.NO_PROXY)
            .socketFactory(WarpDpiSocketFactory(engine))
            .dns(Dns { host ->
                if (host != WarpApi.HOST) throw UnknownHostException(host)
                // OkHttp needs a route address. Native code resolves the only
                // permitted upstream. This address is never used for TCP.
                listOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1)))
            })
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()

        fun probe(): Boolean {
            val seconds = probeTimeout.toLong()
            val probeClient = client.newBuilder()
                .connectTimeout(seconds, TimeUnit.SECONDS)
                .readTimeout(seconds, TimeUnit.SECONDS)
                .writeTimeout(seconds, TimeUnit.SECONDS)
                .callTimeout(seconds, TimeUnit.SECONDS)
                .build()
            val call = probeClient.newCall(WarpApi.buildRequest("GET", "reg", null, null))
            return calls.execute(call) { it.code in 200..499 && it.code != 429 }
        }

        override fun close() {
            try {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            } finally {
                engine.close()
            }
        }
    }

    private class RegistrationCalls {
        @Volatile private var cancelled = false
        private var active: Call? = null

        fun checkCancelled() {
            if (cancelled) throw CancellationException()
        }

        fun cancel() {
            val call = synchronized(this) {
                cancelled = true
                active
            }
            call?.cancel()
        }

        fun <T> execute(call: Call, read: (okhttp3.Response) -> T): T {
            synchronized(this) {
                checkCancelled()
                active = call
            }
            try {
                return call.execute().use(read)
            } finally {
                synchronized(this) { active = null }
                checkCancelled()
            }
        }
    }
}
