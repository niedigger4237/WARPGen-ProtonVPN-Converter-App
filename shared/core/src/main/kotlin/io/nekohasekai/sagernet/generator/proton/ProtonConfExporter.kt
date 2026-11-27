package io.nekohasekai.sagernet.generator.proton

internal object ProtonConfExporter {
    enum class Format { AWG, WIRESOCK, CLASH, XRAY }
    enum class Junk { LIGHT, HEAVY, CUSTOM }
    data class Options(
        val format: Format = Format.AWG,
        val port: Int = 51820,
        val mtu: Int = 1420,
        val keepalive: Int? = null,
        val junk: Junk = Junk.LIGHT,
        val jc: Int = 128,
        val jmin: Int = 1279,
        val jmax: Int = 1280,
        val noJunk: Boolean = false,
        val i: List<String> = List(5) { "" },
        val defaultI1: String = "",
        val wsDomain: String = "apteka.ru",
        val wsIp: String = "QUIC",
        val wsBrowser: String = "Chrome",
        val awg3: Boolean = false,
        val awg31: Boolean = false,
        val ranges: Map<String, String> = emptyMap(),
        val splitAllowedIps: List<String>? = null
    )
    data class Output(val fileName: String, val text: String)
    val ports = listOf(80, 88, 443, 500, 1194, 1224, 4500, 4569, 5060, 51820)
    val rangeKeys = listOf("ContentPaddingAddition", "RekeyAfterTime", "RekeyTimeout",
        "RejectAfterTime", "KeepaliveTimeout", "MaxHandshakeAttempts")

    internal fun single(value: String): String {
        require(value.isNotBlank() && value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid configuration value" }
        return value
    }
    internal fun validate(o: Options) {
        require(o.port in ports && o.mtu in 576..65535)
        require(o.keepalive == null || o.keepalive in 0..65535)
        require(o.jc in 0..128 && o.jmin in 0..65534 && o.jmax in 0..65535)
        require(o.i.size == 5)
        require(o.ranges.keys.all { it in rangeKeys })
        require(o.ranges.values.all { it.isBlank() || it.matches(Regex("[0-9]+(-[0-9]+)?")) })
    }
    internal fun awg(p: ProtonParser.Config, o: Options): Map<String, String> {
        val (jc, min, max) = when (o.junk) {
            Junk.LIGHT -> Triple(3, 1, 3)
            Junk.HEAVY -> Triple(30, 10, 30)
            Junk.CUSTOM -> Triple(o.jc, o.jmin, maxOf(o.jmax, o.jmin + 1))
        }
        val result = linkedMapOf("jc" to jc.toString(), "jmin" to min.toString(),
            "jmax" to max.toString(), "s1" to "0", "s2" to "0",
            "h1" to "1", "h2" to "2", "h3" to "3", "h4" to "4")
        result.keys.toList().forEach { key -> p.awg[key]?.let { result[key] = single(it) } }
        if (o.noJunk) result["jc"] = "0"
        return result
    }
    internal fun endpoint(p: ProtonParser.Config, o: Options): String =
        (if (':' in p.host) "[${single(p.host)}]" else single(p.host)) + ":" + o.port

    internal fun i1(o: Options): String = single(o.i.first().ifBlank { o.defaultI1 })

    fun export(p: ProtonParser.Config, o: Options, index: Int = 0): Output {
        validate(o)
        require(o.format == Format.AWG || o.format == Format.WIRESOCK)
        val a = awg(p, o)
        val lines = mutableListOf("# ${single(p.name)}", "[Interface]",
            "PrivateKey = ${single(p.privateKey)}",
            "Address = ${p.addresses.joinToString(", ", transform = ::single)}")
        if (p.dns.isNotEmpty()) lines += "DNS = ${p.dns.joinToString(", ", transform = ::single)}"
        lines += listOf("MTU = ${o.mtu}", "S1 = 0", "S2 = 0")
        listOf("jc", "jmin", "jmax", "h1", "h2", "h3", "h4").zip(
            listOf("Jc", "Jmin", "Jmax", "H1", "H2", "H3", "H4")).forEach { (key, label) ->
            lines += "$label = ${a.getValue(key)}"
        }
        if (o.format == Format.AWG) {
            lines += "I1 = ${i1(o)}"
            o.i.drop(1).forEachIndexed { n, value -> if (value.isNotBlank()) lines += "I${n + 2} = ${single(value)}" }
        } else {
            lines += listOf("Id = ${single(o.wsDomain)}", "Ip = ${single(o.wsIp)}", "Ib = ${single(o.wsBrowser)}")
        }
        if (o.awg3) rangeKeys.forEach { key ->
            o.ranges[key]?.takeIf { it.isNotBlank() }?.let { lines += "$key = $it" }
        }
        if (o.awg31) lines += "DisableCookies = on"
        lines += listOf("", "[Peer]", "PublicKey = ${single(p.publicKey)}")
        p.presharedKey?.let { lines += "PresharedKey = ${single(it)}" }
        val allowed = o.splitAllowedIps ?: p.allowedIps
        require(allowed.isNotEmpty())
        lines += "AllowedIPs = ${allowed.joinToString(", ", transform = ::single)}"
        lines += "Endpoint = ${endpoint(p, o)}"
        o.keepalive?.let { lines += "PersistentKeepalive = $it" }
        return Output(fileName(p, index, "conf"), lines.joinToString("\n", postfix = "\n"))
    }

    internal fun fileName(p: ProtonParser.Config, index: Int, ext: String): String {
        val base = p.name.replace("-FREE", "").replace(Regex("[^a-zA-Z0-9]+"), "_").trim('_')
        return "${base.ifEmpty { "config_${index + 1}" }}.$ext"
    }
}
