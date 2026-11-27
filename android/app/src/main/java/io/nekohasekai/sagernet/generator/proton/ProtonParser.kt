package io.nekohasekai.sagernet.generator.proton

import java.util.Locale

internal object ProtonParser {
    data class Source(val name: String, val text: String)
    data class Config(
        val name: String,
        val privateKey: String,
        val publicKey: String,
        val presharedKey: String?,
        val addresses: List<String>,
        val dns: List<String>,
        val allowedIps: List<String>,
        val host: String,
        val awg: Map<String, String>
    )
    private val awgKeys = listOf("jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4")

    fun parse(source: Source): Config {
        val iface = linkedMapOf<String, String>()
        val peers = mutableListOf<MutableMap<String, String>>()
        var section = ""
        var peerName: String? = null
        var firstPeerHeader = false
        for (raw in source.text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).lowercase(Locale.ROOT)
                if (section == "peer") peers.add(linkedMapOf())
                firstPeerHeader = section == "peer" && peers.size == 1
                continue
            }
            if (line.startsWith("#")) {
                if (firstPeerHeader) peerName = line.removePrefix("#").trim().takeIf { it.isNotEmpty() }
                firstPeerHeader = false
                continue
            }
            firstPeerHeader = false
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq).trim().lowercase(Locale.ROOT)
            val value = line.substring(eq + 1).trim()
            when (section) {
                "interface" -> iface[key] = value
                "peer" -> peers.lastOrNull()?.set(key, value)
            }
        }
        val peer = peers.firstOrNull() ?: throw IllegalArgumentException("Missing [Peer] section")
        fun required(map: Map<String, String>, key: String, label: String): String =
            map[key]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Missing $label")
        fun split(value: String) = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val endpoint = required(peer, "endpoint", "Endpoint")
        val host = if (endpoint.startsWith("[")) {
            val end = endpoint.indexOf(']')
            require(end > 1 && endpoint.getOrNull(end + 1) == ':') { "Invalid Endpoint" }
            endpoint.substring(1, end)
        } else {
            require(endpoint.count { it == ':' } == 1) { "Invalid Endpoint" }
            endpoint.substringBeforeLast(':')
        }
        require(host.isNotBlank() && endpoint.substringAfterLast(':').toIntOrNull() in 1..65535) { "Invalid Endpoint" }
        val addresses = split(required(iface, "address", "Address"))
        val allowed = split(required(peer, "allowedips", "AllowedIPs"))
        require(addresses.isNotEmpty() && allowed.isNotEmpty()) { "Empty Address or AllowedIPs" }
        val name = peerName ?: Regex("#\\s*([A-Z]{2}-FREE#?\\d+)").find(source.text)?.groupValues?.get(1)
            ?: source.name.replace(Regex("\\.conf$", RegexOption.IGNORE_CASE), "")
        return Config(name, required(iface, "privatekey", "PrivateKey"),
            required(peer, "publickey", "PublicKey"), peer["presharedkey"]?.takeIf { it.isNotBlank() },
            addresses, split(iface["dns"].orEmpty()), allowed, host,
            awgKeys.mapNotNull { key -> (iface[key]?.takeIf { it.isNotBlank() } ?: peer[key]?.takeIf { it.isNotBlank() })?.let { key to it } }.toMap())
    }
}
