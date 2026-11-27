package io.nekohasekai.sagernet.generator.warp

import org.bouncycastle.util.encoders.Base64
import org.json.JSONObject
import java.security.SecureRandom
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object WarpRegistration {
    // Do not use a data class: its generated toString would expose the private key.
    class Account(val privateKey: String, val peerPublicKey: String,
                  val ipv4: String, val ipv6: String)

    fun register(request: (String, String, JSONObject?, String?) -> JSONObject): Account {
        val key = X25519PrivateKeyParameters(SecureRandom())
        val secret = key.encoded
        try {
            val pub = Base64.toBase64String(key.generatePublicKey().encoded)
            val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())
            val registration = request("POST", "reg", JSONObject()
                .put("install_id", "").put("tos", tos).put("key", pub)
                .put("fcm_token", "").put("type", "ios").put("locale", "en_US"), null)
                .getJSONObject("result")
            val id = registration.getString("id")
            val token = registration.getString("token")
            require(id.matches(Regex("[A-Za-z0-9_-]+")) && token.isNotBlank())
            val config = request("PATCH", "reg/$id", JSONObject().put("warp_enabled", true), token)
                .getJSONObject("result").getJSONObject("config")
            val peer = config.getJSONArray("peers").getJSONObject(0).getString("public_key")
            require(Base64.decode(peer).size == 32)
            val addresses = config.getJSONObject("interface").getJSONObject("addresses")
            return Account(Base64.toBase64String(secret), peer,
                addresses.getString("v4"), addresses.getString("v6"))
        } finally {
            secret.fill(0)
        }
    }
}
