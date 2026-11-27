package io.nekohasekai.sagernet.generator.warp

import java.io.Closeable
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Files

internal class WindowsByeDpi private constructor(private var handle: Long) : Closeable {
    companion object {
        private val loaded by lazy {
            val path = System.getProperty("warp.dpi.library")?.let { Path.of(it) }
                ?: System.getProperty("jpackage.app-path")?.let { Path.of(it).toAbsolutePath().parent.resolve("app/warp_dpi_windows.dll") }
                ?: Path.of("build/native/warp_dpi_windows.dll")
            check(Files.isRegularFile(path)) { "Native ByeDPI DLL not found: $path" }
            try { System.load(path.toAbsolutePath().toString()) }
            catch (e: UnsatisfiedLinkError) { throw IllegalStateException("Native ByeDPI DLL could not be loaded", e) }
            true
        }
        fun ensureLoaded() { check(loaded) }
        fun validate(arguments: List<String>): String? {
            ensureLoaded()
            if (arguments.isEmpty()) return "Enter at least one argument."
            return nativeValidate(arguments.toTypedArray())
        }
        fun open(strategy: DpiStrategySelector.Strategy): WindowsByeDpi {
            ensureLoaded()
            val handle = nativeStart(strategy.arguments.toTypedArray())
            if (handle == 0L) throw IOException("Native ByeDPI initialization failed")
            return WindowsByeDpi(handle)
        }
        @JvmStatic private external fun nativeValidate(arguments: Array<String>): String?
        @JvmStatic private external fun nativeStart(arguments: Array<String>): Long
        @JvmStatic private external fun nativePrepare(handle: Long): Int
        @JvmStatic private external fun nativeConnect(handle: Long, port: Int): Boolean
        @JvmStatic private external fun nativeStop(handle: Long)
    }
    @Synchronized fun prepare(): Int {
        check(handle != 0L) { "Native ByeDPI session closed" }
        return nativePrepare(handle).also { if (it !in 1..65535) throw IOException("Unable to prepare private WARP stream") }
    }
    @Synchronized fun connect(clientPort: Int) {
        check(handle != 0L) { "Native ByeDPI session closed" }
        if (!nativeConnect(handle, clientPort)) throw IOException("Unable to establish private WARP stream")
    }
    @Synchronized override fun close() {
        val current = handle
        if (current == 0L) return
        handle = 0
        nativeStop(current)
    }
}
