package io.nekohasekai.sagernet.generator

import io.nekohasekai.sagernet.generator.proton.ProtonParser
import io.nekohasekai.sagernet.generator.proton.ProtonConfExporter
import io.nekohasekai.sagernet.generator.proton.ProtonPresets
import io.nekohasekai.sagernet.generator.warp.WarpGenerator
import io.nekohasekai.sagernet.generator.warp.WarpPresets
import io.nekohasekai.sagernet.generator.warp.WarpRegistration
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class GeneratorRegressionTest {
    private fun asset(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "Missing test asset: $name" }
            .bufferedReader().use { it.readText() }

    @Test fun warpAwgUsesCanonicalPresetsAndUserNetworkOptions() {
        val generator = WarpGenerator(::asset)
        val settings = WarpGenerator.Settings(
            endpointMode = WarpPresets.EndpointMode.CUSTOM,
            customEndpoint = "192.0.2.1:2408",
            ipv6 = false, mtu = 1300, keepalive = 25, noJunk = true
        )
        val account = WarpRegistration.Account("test-private", "test-public", "192.0.2.2/32", "2001:db8::2/128")
        val out = generator.render(account, generator.presets.newSeed(Random(42)), settings)
        assertTrue(out.fileName.endsWith(".conf"))
        assertTrue(out.text.contains("PrivateKey = test-private"))
        assertTrue(out.text.contains("Endpoint = 192.0.2.1:2408"))
        assertTrue(out.text.contains("MTU = 1300"))
        assertTrue(out.text.contains("PersistentKeepalive = 25"))
        assertTrue(out.text.contains("Jc = 0"))
        assertTrue(out.text.contains("I1 = "))
        assertFalse(out.text.contains("2001:db8::2"))
        assertFalse(out.text.contains("::/0"))
    }

    @Test fun protonPreservesKeysAndHandlesIpv6Endpoint() {
        val source = ProtonParser.Source("sample.conf", """
            [Interface]
            PrivateKey = test-private
            Address = 192.0.2.2/32
            DNS = 192.0.2.53
            [Peer]
            PublicKey = test-public
            PresharedKey = test-preshared
            AllowedIPs = 0.0.0.0/0
            Endpoint = [2001:db8::1]:51820
        """.trimIndent())
        val config = ProtonParser.parse(source)
        val options = ProtonPresets(::asset).defaults().copy(port = 443)
        val output = ProtonConfExporter.export(config, options)
        assertEquals("sample.conf", output.fileName)
        assertTrue(output.text.contains("PrivateKey = test-private"))
        assertTrue(output.text.contains("PublicKey = test-public"))
        assertTrue(output.text.contains("PresharedKey = test-preshared"))
        assertTrue(output.text.contains("Endpoint = [2001:db8::1]:443"))
        assertTrue(output.text.contains("I1 = "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingPeer() {
        ProtonParser.parse(ProtonParser.Source("invalid.conf", "[Interface]\nPrivateKey = test"))
    }
}
