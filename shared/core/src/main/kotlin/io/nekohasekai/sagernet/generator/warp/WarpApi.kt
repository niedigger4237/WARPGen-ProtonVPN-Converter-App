package io.nekohasekai.sagernet.generator.warp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Blocking API transport. Call from an IO dispatcher. */
object WarpApi {
    sealed interface Connection {
        object Direct : Connection
        object LocalProxy : Connection
        data class CustomProxy(val host: String, val port: Int, val type: Proxy.Type) : Connection
    }

    internal const val HOST = "api.cloudflareclient.com"

    internal fun clientBuilder() = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)

    private val base = clientBuilder().build()

    fun request(method: String, endpoint: String, body: JSONObject?, token: String?,
                connection: Connection): JSONObject {
        val proxy = when (connection) {
            Connection.Direct -> Proxy.NO_PROXY
            Connection.LocalProxy -> Proxy(Proxy.Type.SOCKS,
                InetSocketAddress("127.0.0.1", 10808))
            is Connection.CustomProxy -> {
                require(connection.type == Proxy.Type.HTTP || connection.type == Proxy.Type.SOCKS)
                require(connection.host.isNotBlank() && connection.port in 1..65535)
                Proxy(connection.type, InetSocketAddress.createUnresolved(connection.host, connection.port))
            }
        }
        val client = base.newBuilder().proxy(proxy).build()
        return client.newCall(buildRequest(method, endpoint, body, token)).execute().use(::readResponse)
    }

    internal fun buildRequest(method: String, endpoint: String, body: JSONObject?, token: String?): Request {
        require(method in setOf("POST", "PATCH", "GET"))
        require(endpoint.matches(Regex("reg(/[A-Za-z0-9_-]+)?")))
        val request = Request.Builder()
            .url("https://$HOST/v0i1909051800/$endpoint")
            .header("User-Agent", "CloudflareWarp/1.0")
            .method(method, if (method == "GET") null else
                (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
        if (!token.isNullOrEmpty()) request.header("Authorization", "Bearer $token")
        return request.build()
    }

    internal fun readResponse(response: okhttp3.Response): JSONObject {
        check(response.isSuccessful) { "Cloudflare API HTTP ${response.code}" }
        return JSONObject(response.body?.string() ?: error("Empty Cloudflare response"))
    }
}
