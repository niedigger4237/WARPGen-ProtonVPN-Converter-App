package io.nekohasekai.sagernet.generator.proton

import android.content.Context
import io.nekohasekai.sagernet.generator.common.QuicI1Generator
import io.nekohasekai.sagernet.generator.warp.WarpPresets
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

internal class ProtonPresets(context: Context) {
    private val assets = context.applicationContext.assets
    private val packets = assets.open("generator/proton_i1.json").bufferedReader().use {
        val a = JSONArray(it.readText())
        List(a.length()) { n -> a.getString(n) }
    }
    private val domains = assets.open("generator/warp_data.json").bufferedReader().use {
        val a = JSONObject(it.readText()).getJSONArray("domains")
        List(a.length()) { n -> a.getString(n) }
    }
    private val network = WarpPresets(context.applicationContext)

    fun defaults() = ProtonConfExporter.Options(
        defaultI1 = packets.first(), wsIp = "quic", wsBrowser = "curl",
        ranges = mapOf("ContentPaddingAddition" to "27-97", "MaxHandshakeAttempts" to "18-26",
            "KeepaliveTimeout" to "9-16", "RejectAfterTime" to "171-196",
            "RekeyAfterTime" to "116-131", "RekeyTimeout" to "6-9")
    )

    fun selectFormat(o: ProtonConfExporter.Options, format: ProtonConfExporter.Format) =
        if (format == ProtonConfExporter.Format.XRAY && o.format != format)
            o.copy(format = format, junk = ProtonConfExporter.Junk.CUSTOM, jc = 5, jmin = 40, jmax = 70)
        else o.copy(format = format)

    fun randomJunk(o: ProtonConfExporter.Options, random: Random = Random.Default): ProtonConfExporter.Options {
        val jc = random.nextInt(1, 101)
        val min = random.nextInt(1, 201)
        return o.copy(junk = ProtonConfExporter.Junk.CUSTOM, jc = jc, jmin = min,
            jmax = random.nextInt(min + 1, 202))
    }

    fun randomI1(o: ProtonConfExporter.Options, random: Random = Random.Default) =
        o.copy(i = listOf(packets.random(random), "", "", "", ""))

    fun domainI1(o: ProtonConfExporter.Options, domain: String) =
        o.copy(i = listOf(QuicI1Generator.generate(domain.trim()), "", "", "", ""))

    fun randomWireSock(o: ProtonConfExporter.Options, random: Random = Random.Default) =
        o.copy(wsDomain = domains.random(random))

    fun randomAwg3(o: ProtonConfExporter.Options, random: Random = Random.Default): ProtonConfExporter.Options {
        fun range(a: Int, b: Int, c: Int, d: Int) =
            "${random.nextInt(a, b + 1)}-${random.nextInt(c, d + 1)}"
        return o.copy(ranges = mapOf(
            "ContentPaddingAddition" to range(5, 49, 50, 110),
            "MaxHandshakeAttempts" to range(5, 24, 25, 40),
            "KeepaliveTimeout" to range(5, 10, 11, 25),
            "RejectAfterTime" to range(50, 99, 100, 200),
            "RekeyAfterTime" to range(50, 99, 100, 150),
            "RekeyTimeout" to range(3, 9, 10, 15)))
    }

    fun excludeLan(o: ProtonConfExporter.Options, enabled: Boolean) =
        o.copy(splitAllowedIps = if (enabled) network.allowedIps(true, true, emptySet()) else null)
}
