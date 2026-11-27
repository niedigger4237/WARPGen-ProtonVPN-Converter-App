package io.nekohasekai.sagernet.generator.warp

internal object WarpConfExporter {
    enum class Variant(val number: Int) { QUIC(1), DOMAIN(2), SIP(3) }
    class Output(val fileName: String, val text: String)
    data class Options(
        val endpoint: String,
        val dns: List<String>,
        val allowedIps: List<String>,
        val ipv6: Boolean = true,
        val mtu: Int = 1280,
        val keepalive: Int? = null,
        val noJunk: Boolean = false,
        val awg3: Map<String, String> = emptyMap(),
        val randomTrailers: Boolean = false,
        val disableCookies: Boolean = false
    )
    private val rangeKeys = listOf("ContentPaddingAddition", "RekeyAfterTime",
        "RekeyTimeout", "RejectAfterTime", "KeepaliveTimeout", "MaxHandshakeAttempts")
    private fun single(value: String): String {
        require(value.isNotBlank() && value.none { it == '\r' || it == '\n' || it == '\u0000' })
        return value
    }
    private fun common(account: WarpRegistration.Account, o: Options): MutableList<String> {
        require(o.mtu in 576..65535 && (o.keepalive == null || o.keepalive in 0..65535))
        val addresses = listOf(account.ipv4) + if (o.ipv6) listOf(account.ipv6) else emptyList()
        val dns = o.dns.filter { o.ipv6 || ':' !in it }
        require(dns.isNotEmpty())
        return mutableListOf("[Interface]", "PrivateKey = ${single(account.privateKey)}",
            "Address = ${addresses.joinToString(", ", transform = ::single)}",
            "DNS = ${dns.joinToString(", ", transform = ::single)}", "MTU = ${o.mtu}",
            "S1 = 0", "S2 = 0", "S3 = 0", "S4 = 0",
            "Jc = ${if (o.noJunk) 0 else 4}", "Jmin = 40", "Jmax = 70",
            "H1 = 1", "H2 = 2", "H3 = 3", "H4 = 4")
    }
    private fun peer(lines: MutableList<String>, account: WarpRegistration.Account, o: Options) {
        require(o.allowedIps.isNotEmpty())
        lines += listOf("", "[Peer]", "PublicKey = ${single(account.peerPublicKey)}",
            "AllowedIPs = ${o.allowedIps.joinToString(", ", transform = ::single)}",
            "Endpoint = ${single(o.endpoint)}")
        o.keepalive?.let { lines += "PersistentKeepalive = $it" }
    }
    fun awg(account: WarpRegistration.Account, o: Options, variant: Variant,
            number: Int, i1: String, i2: String? = null): Output {
        require(number in 10..99)
        val lines = common(account, o)
        lines += "I1 = ${single(i1)}"
        i2?.let { lines += "I2 = ${single(it)}" }
        require(o.awg3.keys.all { it in rangeKeys })
        for (key in rangeKeys) {
            val value = o.awg3[key]?.trim() ?: continue
            if (value.isEmpty() || value == "0") continue
            require(value.matches(Regex("[0-9]+(-[0-9]+)?")))
            lines += "$key = $value"
        }
        if (o.randomTrailers) lines += "RandomTrailers = on"
        if (o.disableCookies) lines += "DisableCookies = on"
        peer(lines, account, o)
        return Output("WARPv${variant.number}_$number.conf", lines.joinToString("\n", postfix = "\n"))
    }
    fun wireSock(account: WarpRegistration.Account, o: Options, number: Int,
                 domain: String, ip: String, ib: String): Output {
        require(number in 10..99)
        val lines = common(account, o)
        lines += listOf("", "# Protocol masking", "Id = ${single(domain)}",
            "Ip = ${single(ip)}", "Ib = ${single(ib)}")
        peer(lines, account, o)
        return Output("WARPw_$number.conf", lines.joinToString("\n", postfix = "\n"))
    }
}
