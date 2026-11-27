package io.nekohasekai.sagernet.generator.warp

import android.content.Context
import io.nekohasekai.sagernet.generator.common.QuicI1Generator

/** Stateless configuration rendering; account and seed are owned by the caller. */
internal class WarpGenerator(context: Context) {
    val presets = WarpPresets(context.applicationContext)
    enum class Format { AWG, WIRESOCK, CLASH, XRAY }
    data class Settings(
        val format: Format = Format.AWG,
        val variant: WarpConfExporter.Variant = WarpConfExporter.Variant.QUIC,
        val endpointMode: WarpPresets.EndpointMode = WarpPresets.EndpointMode.RANDOM,
        val customEndpoint: String = "",
        val domain: String = "",
        val dnsId: String = "cf",
        val ipv6: Boolean = true,
        val mtu: Int = 1280,
        val keepalive: Int? = null,
        val noJunk: Boolean = false,
        val excludeLan: Boolean = false,
        val sites: Set<String> = emptySet(),
        val awg3Enabled: Boolean = false,
        val awg31Enabled: Boolean = false,
        val awg3: Map<String, String> = mapOf("ContentPaddingAddition" to "27-97", "MaxHandshakeAttempts" to "18-26", "KeepaliveTimeout" to "9-16", "RejectAfterTime" to "171-196", "RekeyAfterTime" to "116-131", "RekeyTimeout" to "6-9"),
        val randomTrailers: Boolean = true,
        val disableCookies: Boolean = true,
        val wireSockIp: String = "QUIC",
        val wireSockIb: String = "Chrome"
    )

    fun render(account: WarpRegistration.Account, seed: WarpPresets.Seed,
               settings: Settings): WarpConfExporter.Output {
        val s = settings
        val domain = s.domain.trim().ifEmpty { seed.domain }
        val options = WarpConfExporter.Options(
            endpoint = presets.endpoint(s.endpointMode, seed, s.customEndpoint),
            dns = presets.dns.first { it.id == s.dnsId }.addresses,
            allowedIps = presets.allowedIps(s.ipv6, s.excludeLan, s.sites),
            ipv6 = s.ipv6, mtu = s.mtu, keepalive = s.keepalive,
            noJunk = s.noJunk, awg3 = if (s.awg3Enabled) s.awg3 else emptyMap(),
            randomTrailers = s.awg31Enabled && s.randomTrailers, disableCookies = s.awg31Enabled && s.disableCookies
        )
        return when (s.format) {
            Format.AWG -> {
                val packets = if (s.variant == WarpConfExporter.Variant.DOMAIN)
                    mapOf("I1" to QuicI1Generator.generate(domain))
                else presets.awgPackets(s.variant)
                WarpConfExporter.awg(account, options, s.variant, seed.number,
                    packets.getValue("I1"), packets["I2"])
            }
            Format.WIRESOCK -> WarpConfExporter.wireSock(account, options, seed.number,
                domain, s.wireSockIp, s.wireSockIb)
            Format.CLASH -> WarpStructuredExporter.clash(account, options, seed.number, presets.clash)
            Format.XRAY -> WarpStructuredExporter.xray(account, options, seed.number,
                if (s.domain.isBlank()) seed.xray
                else QuicI1Generator.generate(domain).removePrefix("<b 0x").removeSuffix(">"))
        }
    }
}
