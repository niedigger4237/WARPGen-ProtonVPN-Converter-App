package io.nekohasekai.sagernet.generator.warp

import org.json.JSONArray
import org.json.JSONObject

internal object WarpStructuredExporter {
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply {
        pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
    private fun arr(vararg values: Any) = JSONArray(values.toList())
    private fun quote(value: String) = JSONObject.quote(value)
    private fun dns(o: WarpConfExporter.Options) = o.dns.filter { o.ipv6 || ':' !in it }
    private val ranges = linkedMapOf(
        "ContentPaddingAddition" to "content-padding-addition",
        "RekeyAfterTime" to "rekey-after-time",
        "RekeyTimeout" to "rekey-timeout",
        "RejectAfterTime" to "reject-after-time",
        "KeepaliveTimeout" to "keepalive-timeout",
        "MaxHandshakeAttempts" to "max-handshake-attempts"
    )

    fun clash(a: WarpRegistration.Account, o: WarpConfExporter.Options,
              number: Int, presets: List<List<String>>): WarpConfExporter.Output {
        require(number in 10..99 && presets.size == 3)
        require(o.awg3.keys.all { it in ranges })
        val names = presets.indices.map { "Стандартный ${it + 1}" }
        val text = buildString {
            appendLine("warp-common: &warp-common")
            appendLine("  type: wireguard")
            appendLine("  ip: ${quote(a.ipv4)}")
            if (o.ipv6) appendLine("  ipv6: ${quote(a.ipv6)}")
            appendLine("  private-key: ${quote(a.privateKey)}")
            appendLine("  public-key: ${quote(a.peerPublicKey)}")
            appendLine("  allowed-ips: ['0.0.0.0/0']")
            appendLine("  udp: true")
            appendLine("  mtu: ${o.mtu}")
            o.keepalive?.let { appendLine("  persistent-keepalive: $it") }
            appendLine("  remote-dns-resolve: true")
            appendLine("  dns: [${dns(o).joinToString(", ", transform = ::quote)}]")
            appendLine()
            appendLine("proxies:")
            presets.forEachIndexed { index, packets ->
                appendLine("- name: ${quote(names[index])}")
                appendLine("  <<: *warp-common")
                appendLine("  server: 162.159.192.1")
                appendLine("  port: 4500")
                appendLine("  amnezia-wg-option:")
                appendLine("   version: 3")
                appendLine("   jc: ${if (o.noJunk) 0 else 4}")
                appendLine("   jmin: 40")
                appendLine("   jmax: 70")
                packets.forEachIndexed { k, packet -> appendLine("   i${k + 1}: ${quote(packet)}") }
                ranges.forEach { (key, yaml) ->
                    val value = o.awg3[key]?.trim()
                    if (!value.isNullOrEmpty() && value != "0") {
                        require(value.matches(Regex("[0-9]+(-[0-9]+)?")))
                        appendLine("   $yaml: $value")
                    }
                }
                if (o.randomTrailers) appendLine("   random-trailers: true")
                if (o.disableCookies) appendLine("   disable-cookies: true")
            }
            appendLine()
            appendLine("proxy-groups:")
            appendLine("- name: WARP")
            appendLine("  type: select")
            appendLine("  proxies:")
            names.forEach { appendLine("    - ${quote(it)}") }
            appendLine("  url: 'http://speed.cloudflare.com/'")
            appendLine("  interval: 300")
            appendLine("rules:")
            appendLine("- MATCH,WARP")
        }
        return WarpConfExporter.Output("ClashWARP_$number.yaml", text)
    }

    fun xray(a: WarpRegistration.Account, o: WarpConfExporter.Options,
             number: Int, hex: String): WarpConfExporter.Output {
        require(number in 10..99 && hex.isNotEmpty() && hex.length % 2 == 0 &&
            hex.matches(Regex("[0-9a-fA-F]+")))
        val allowed = if (o.ipv6) arr("0.0.0.0/0", "::/0") else arr("0.0.0.0/0")
        val peer = obj("allowedIPs" to allowed, "endpoint" to o.endpoint, "publicKey" to a.peerPublicKey)
        o.keepalive?.let { peer.put("keepAlive", it) }
        val addresses = arr(a.ipv4.substringBefore('/') + "/32")
        if (o.ipv6) addresses.put(a.ipv6.substringBefore('/') + "/128")
        val noises = arr(obj("delay" to "1-2", "packet" to hex, "type" to "hex"))
        if (!o.noJunk) repeat(8) { noises.put(obj("delay" to "1-3", "packet" to "23-911", "type" to "rand")) }
        fun inbound(protocol: String, port: Int, settings: JSONObject) = obj(
            "listen" to "127.0.0.1", "port" to port, "protocol" to protocol,
            "settings" to settings, "sniffing" to obj("destOverride" to arr("http", "tls"), "enabled" to true),
            "tag" to "$protocol-in")
        val config = obj(
            "dns" to obj("servers" to JSONArray(dns(o))),
            "inbounds" to arr(inbound("socks", 10808, obj("auth" to "noauth", "udp" to true)),
                inbound("http", 10809, obj())),
            "log" to obj("loglevel" to "warning"), "meta" to null,
            "outbounds" to arr(
                obj("protocol" to "wireguard", "settings" to obj("address" to addresses,
                    "mtu" to o.mtu, "peers" to arr(peer), "secretKey" to a.privateKey),
                    "streamSettings" to obj("sockopt" to obj("dialerProxy" to "noise-out")), "tag" to "warp"),
                obj("protocol" to "freedom", "settings" to obj("domainStrategy" to "AsIs", "noises" to noises),
                    "tag" to "noise-out")),
            "remarks" to "WARP",
            "routing" to obj("domainStrategy" to "IPIfNonMatch", "rules" to arr(
                obj("outboundTag" to "noise-out", "port" to "53", "type" to "field"),
                obj("network" to "tcp,udp", "outboundTag" to "warp", "type" to "field"))))
        return WarpConfExporter.Output("XrayWARP_$number.json", config.toString(4) + "\n")
    }
}
