package io.nekohasekai.sagernet.generator.warp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

internal class WarpPresets(context: Context) {
    private val data = context.assets.open("generator/warp_data.json").bufferedReader().use { JSONObject(it.readText()) }
    data class Dns(val id: String, val label: String, val addresses: List<String>)
    data class Seed(val port: Int, val prefix: String, val last: Int, val number: Int, val domain: String, val xray: String)
    enum class EndpointMode { RANDOM, ENGAGE, CUSTOM }
    val dns = listOf(
        Dns("cf", "Cloudflare 1.1.1.1", "1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001".split(", ")),
        Dns("google", "Google 8.8.8.8", "8.8.8.8, 8.8.4.4, 2001:4860:4860::8888, 2001:4860:4860::8844".split(", ")),
        Dns("malw", "dns.malw.link", "95.216.204.218, 80.253.249.40, 2a01:4f9:c014:6dac::1, 2a12:bec4:1460:5b7::2".split(", ")),
        Dns("xbox", "xbox-dns.ru", "111.88.96.54, 111.88.96.55, 2a00:ab00:1233:26::50, 2a00:ab00:1233:26::51".split(", ")),
        Dns("geohide", "geohide.ru", "45.155.204.190, 37.230.192.51, 159.194.200.33, 193.233.112.67, 193.233.112.88".split(", ")),
        Dns("comss", "dns.comss.one", "83.220.169.155, 212.109.195.93, 195.133.25.16, 2a01:230:4:915::2, 2a01:230:4:306::2".split(", "))
    )
    private val prefixes = listOf("162.159.192.", "162.159.195.", "engage.cloudflareclient.com", "8.6.112.", "8.34.70.", "8.34.146.", "8.35.211.", "8.39.125.", "8.39.204.", "8.39.214.", "8.47.69.", "188.114.96.", "188.114.97.", "188.114.98.", "188.114.99.")
    private val labels = mapOf("youtube" to "YouTube", "discord" to "Discord", "whatsapp" to "WhatsApp", "telegram" to "Telegram", "twitter" to "Twitter", "instagram" to "Instagram", "facebook" to "Facebook", "viber" to "Viber", "itch" to "Itch.io", "rutracker" to "RuTracker", "rutor" to "Rutor", "nnmclub" to "NNMClub", "bestchange" to "BestChange", "animego" to "AnimeGo", "hdrezka" to "HDrezka")
    private val sites = data.getJSONObject("sites")
    val siteNames = sites.keys().asSequence().associateWith { labels[it] ?: it }
    private fun strings(a: JSONArray) = List(a.length()) { a.getString(it) }
    val clash = data.getJSONArray("clash").let { a -> List(a.length()) { strings(a.getJSONArray(it)) } }
    fun awgPackets(variant: WarpConfExporter.Variant): Map<String, String> {
        require(variant != WarpConfExporter.Variant.DOMAIN)
        return data.getJSONObject("awgVariants").getString(if (variant == WarpConfExporter.Variant.QUIC) "quic" else "sip")
            .lineSequence().filter { it.contains('=') }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    }
    fun newSeed(random: Random = Random.Default): Seed {
        val ports = data.getJSONArray("ports")
        return Seed(ports.getInt(random.nextInt(ports.length())), prefixes.random(random),
            random.nextInt(1, 11), random.nextInt(10, 100),
            strings(data.getJSONArray("domains")).random(random), strings(data.getJSONArray("xray")).random(random))
    }
    fun endpoint(mode: EndpointMode, seed: Seed, custom: String = "") = when (mode) {
        EndpointMode.ENGAGE -> "engage.cloudflareclient.com:2408"
        EndpointMode.CUSTOM -> custom.trim().ifEmpty { "162.159.192.1:2408" }
        EndpointMode.RANDOM -> if (seed.prefix == "engage.cloudflareclient.com") "${seed.prefix}:${seed.port}" else "${seed.prefix}${seed.last}:${seed.port}"
    }
    fun allowedIps(ipv6: Boolean, excludeLan: Boolean, selected: Set<String>): List<String> {
        val raw = when {
            excludeLan -> data.getString("nolan")
            selected.isEmpty() -> "0.0.0.0/0, ::/0"
            else -> siteNames.keys.filter { it in selected }.joinToString(", ") { sites.getString(it) }
        }
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() && (ipv6 || ':' !in it) }.distinct()
    }
}
