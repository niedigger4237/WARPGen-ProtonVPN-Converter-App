package io.nekohasekai.sagernet.generator.common

import java.net.IDN
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Mirrors the original SNI-only synthetic QUIC packet, not a full TLS handshake. */
internal object QuicI1Generator {
    private val random = SecureRandom()
    private fun bytes(vararg n: Int) = ByteArray(n.size) { n[it].toByte() }
    private fun u16(n: Int): ByteArray {
        require(n in 0..65535)
        return bytes(n ushr 8, n)
    }
    private fun str8(b: ByteArray): ByteArray {
        require(b.size <= 255)
        return bytes(b.size) + b
    }
    private fun str16(b: ByteArray) = u16(b.size) + b
    private fun variable(n: Int): ByteArray = when {
        n in 0..63 -> bytes(n)
        n in 64..16383 -> u16(n or 0x4000)
        else -> error("QUIC length outside supported range")
    }
    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }.doFinal(data)
    private fun derive(key: ByteArray, size: Int, label: String) =
        hmac(key, u16(size) + str8(("tls13 " + label).toByteArray(Charsets.US_ASCII)) +
            bytes(0, 1)).copyOf(size)

    fun generate(domain: String): String = "<b 0x${hex(packet(domain,
        ByteArray(1).also(random::nextBytes), ByteArray(32).also(random::nextBytes)))}>"

    internal fun packet(domain: String, dcid: ByteArray, helloRandom: ByteArray): ByteArray {
        require(dcid.size == 1 && helloRandom.size == 32)
        val host = IDN.toASCII(domain.trim(), IDN.USE_STD3_ASCII_RULES)
        require(host.length in 1..253 && host.split('.').all { it.isNotEmpty() })
        val sni = bytes(0) + str16(host.toByteArray(Charsets.US_ASCII))
        val extension = u16(0) + str16(str16(sni))
        val helloBody = bytes(3, 3) + helloRandom + bytes(0, 0, 0, 0) + str16(extension)
        val hello = bytes(1, helloBody.size ushr 16, helloBody.size ushr 8, helloBody.size) + helloBody
        val payload = bytes(6, 0) + variable(hello.size) + hello
        val header = bytes(0xc0, 0, 0, 0, 1) + str8(dcid) + bytes(0, 0) +
            variable(1 + payload.size + 16) + bytes(0)
        val salt = bytes(0x38,0x76,0x2c,0xf7,0xf5,0x59,0x34,0xb3,0x4d,0x17,
            0x9a,0xe6,0xa4,0xc8,0x0c,0xad,0xcc,0xbb,0x7f,0x0a)
        val client = derive(hmac(salt, dcid), 32, "client in")
        val key = derive(client, 16, "quic key")
        val iv = derive(client, 12, "quic iv")
        val hp = derive(client, 16, "quic hp")
        // Packet number is zero, so nonce XOR leaves IV unchanged.
        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(header)
        }.doFinal(payload)
        val mask = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(hp, "AES"))
        }.doFinal(encrypted.copyOfRange(3, 19))
        header[0] = (header[0].toInt() xor (mask[0].toInt() and 15)).toByte()
        header[header.lastIndex] = (header.last().toInt() xor mask[1].toInt()).toByte()
        return header + encrypted
    }
    private fun hex(data: ByteArray): String = buildString(data.size * 2) {
        val digits = "0123456789abcdef"
        for (b in data) {
            val n = b.toInt() and 255
            append(digits[n ushr 4])
            append(digits[n and 15])
        }
    }
}
