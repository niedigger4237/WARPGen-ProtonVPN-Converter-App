package io.nekohasekai.sagernet.generator.proton

import org.json.JSONObject
import org.json.JSONArray
import io.nekohasekai.sagernet.generator.proton.ProtonConfExporter as Conf

internal object ProtonStructuredExporter {
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply {
        pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
    private fun arr(values: Collection<*>) = JSONArray(values)
    private fun quote(s: String) = JSONObject.quote(s)
    private fun yamlKey(s: String) = s.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase(java.util.Locale.ROOT)
    private fun numeric(s: String): String {
        require(s.matches(Regex("[0-9]+(-[0-9]+)?"))) { "Invalid AWG parameter" }
        return s
    }

    fun clash(configs: List<ProtonParser.Config>, o: Conf.Options): Conf.Output {
        Conf.validate(o)
        require(configs.isNotEmpty())
        val lines = mutableListOf("proxies:")
        val used = mutableSetOf<String>()
        val proton = mutableListOf<String>()
        val other = mutableListOf<String>()
        val flags = mapOf("JP" to "🇯🇵", "US" to "🇺🇸", "NL" to "🇳🇱", "DE" to "🇩🇪", "FR" to "🇫🇷", "GB" to "🇬🇧", "CA" to "🇨🇦", "AU" to "🇦🇺", "RO" to "🇷🇴", "MX" to "🇲🇽", "NO" to "🇳🇴", "SG" to "🇸🇬", "CH" to "🇨🇭", "PL" to "🇵🇱")
        configs.forEach { p ->
            val base = p.name.replace(Regex("FREE#?"), "").trimEnd('-').ifBlank { "Proton" }
            val country = Regex("^([A-Z]{2})[-_]").find(base)?.groupValues?.get(1)
            val label = flags[country]?.let { "$it $base" } ?: base
            var name = label
            var n = 2
            while (!used.add(name)) name = "$label ${n++}"
            if (Regex("(^|[_-])([A-Z]{2})([-_]FREE)?([#_-]|$)", RegexOption.IGNORE_CASE).containsMatchIn(p.name)) proton += name else other += name
            lines += listOf("- name: ${quote(name)}", "  type: wireguard", "  server: ${quote(p.host)}", "  port: ${o.port}")
            p.addresses.firstOrNull { ':' !in it }?.let { lines += "  ip: ${quote(it)}" }
            p.addresses.firstOrNull { ':' in it }?.let { lines += "  ipv6: ${quote(it)}" }
            lines += listOf("  private-key: ${quote(p.privateKey)}", "  public-key: ${quote(p.publicKey)}",
                "  allowed-ips: [${p.allowedIps.joinToString(", ", transform = ::quote)}]")
            p.presharedKey?.let { lines += "  pre-shared-key: ${quote(it)}" }
            o.keepalive?.let { lines += "  persistent-keepalive: $it" }
            lines += listOf("  udp: true", "  mtu: ${o.mtu}", "  remote-dns-resolve: true",
                "  dns: [${p.dns.joinToString(", ", transform = ::quote)}]", "  amnezia-wg-option:", "    version: 3")
            Conf.awg(p, o).forEach { (key, value) -> lines += "    $key: ${numeric(value)}" }
            lines += "    i1: ${quote(Conf.i1(o))}"
            o.i.drop(1).forEachIndexed { i, v -> if (v.isNotBlank()) lines += "    i${i + 2}: ${quote(v)}" }
            if (o.awg3) Conf.rangeKeys.forEach { key ->
                o.ranges[key]?.takeIf { it.isNotBlank() }?.let { lines += "    ${yamlKey(key)}: ${numeric(it)}" }
            }
            if (o.awg31) lines += "    disable-cookies: true"
            lines += ""
        }
        lines += "proxy-groups:"
        fun group(name: String, members: List<String>) {
            if (members.isEmpty()) return
            lines += listOf("- name: $name", "  type: select", "  proxies:")
            members.forEach { lines += "    - ${quote(it)}" }
            lines += listOf("  url: 'http://speed.cloudflare.com/'", "  unified-delay: true", "  interval: 300")
        }
        group("Proton", proton)
        group("Other", other)
        return Conf.Output("clash-config.yaml", lines.joinToString("\n", postfix = "\n"))
    }

    fun xray(p: ProtonParser.Config, o: Conf.Options, index: Int): Conf.Output {
        Conf.validate(o)
        val a = Conf.awg(p, o)
        val hex = Regex("0x([0-9a-fA-F]+)").find(Conf.i1(o))?.groupValues?.get(1).orEmpty()
        require(hex.isNotEmpty() && hex.length % 2 == 0) { "Xray requires a hexadecimal I1 packet" }
        val count = a.getValue("jc").toIntOrNull() ?: error("Invalid Jc")
        require(count in 0..128) { "Jc out of range" }
        val min = a.getValue("jmin").toIntOrNull() ?: error("Invalid Jmin")
        val max = a.getValue("jmax").toIntOrNull() ?: error("Invalid Jmax")
        require(min in 0..65535 && max in min..65535)
        val noises = JSONArray().put(obj("delay" to "1-2", "packet" to hex, "type" to "hex"))
        repeat(count) { noises.put(obj("delay" to "1-3", "packet" to "$min-$max", "type" to "rand")) }
        val peer = obj("allowedIPs" to arr(p.allowedIps), "endpoint" to Conf.endpoint(p, o), "publicKey" to p.publicKey)
        p.presharedKey?.let { peer.put("preSharedKey", it) }
        o.keepalive?.let { peer.put("keepAlive", it) }
        val sniff = obj("destOverride" to arr(listOf("http", "tls")), "enabled" to true)
        val inbounds = arr(listOf(
            obj("listen" to "127.0.0.1", "port" to 10808, "protocol" to "socks", "settings" to obj("auth" to "noauth", "udp" to true), "sniffing" to sniff, "tag" to "socks-in"),
            obj("listen" to "127.0.0.1", "port" to 10809, "protocol" to "http", "settings" to obj(), "sniffing" to sniff, "tag" to "http-in")))
        val outbounds = arr(listOf(
            obj("protocol" to "wireguard", "settings" to obj("address" to arr(p.addresses), "mtu" to o.mtu, "peers" to arr(listOf(peer)), "secretKey" to p.privateKey),
                "streamSettings" to obj("sockopt" to obj("dialerProxy" to "noise-out")), "tag" to "proton"),
            obj("protocol" to "freedom", "settings" to obj("domainStrategy" to "AsIs", "noises" to noises), "tag" to "noise-out")))
        val result = obj("dns" to obj("servers" to arr(p.dns.ifEmpty { listOf("1.1.1.1", "1.0.0.1") })),
            "inbounds" to inbounds, "log" to obj("loglevel" to "warning"), "meta" to null,
            "outbounds" to outbounds, "remarks" to p.name.replace("-FREE", "").replace(Regex("[^a-zA-Z0-9]"), "_"),
            "routing" to obj("domainStrategy" to "AsIs", "rules" to arr(listOf(obj("network" to "tcp,udp", "outboundTag" to "proton", "type" to "field")))))
        return Conf.Output(Conf.fileName(p, index, "json"), result.toString(4) + "\n")
    }

    data class Batch(val outputs: List<Conf.Output>, val errors: List<String>)
    fun convert(sources: List<ProtonParser.Source>, o: Conf.Options): Batch {
        Conf.validate(o)
        val errors = mutableListOf<String>()
        val configs = sources.mapNotNull { source ->
            try { ProtonParser.parse(source) } catch (e: IllegalArgumentException) {
                errors += "${source.name}: ${e.message}"
                null
            }
        }
        if (configs.isEmpty()) return Batch(emptyList(), errors)
        if (o.format == Conf.Format.CLASH) return Batch(listOf(clash(configs, o)), errors)
        val used = mutableSetOf<String>()
        val outputs = configs.mapIndexedNotNull { index, p ->
            try {
                val output = if (o.format == Conf.Format.XRAY) xray(p, o, index) else Conf.export(p, o, index)
                var name = output.fileName
                var suffix = 2
                while (!used.add(name)) name = output.fileName.substringBeforeLast('.') + "_${suffix++}." + output.fileName.substringAfterLast('.')
                output.copy(fileName = name)
            } catch (e: IllegalArgumentException) {
                errors += "${p.name}: ${e.message}"
                null
            }
        }
        return Batch(outputs, errors)
    }
}
