package io.nekohasekai.sagernet.generator.warp

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.IOException

internal class NativeByeDpi private constructor(private var handle: Long) : Closeable {
    companion object {
        private val loaded by lazy {
            System.loadLibrary("warp_dpi")
            true
        }

        private fun ensureLoaded() {
            try {
                check(loaded)
            } catch (e: UnsatisfiedLinkError) {
                throw IllegalStateException("Native ByeDPI library unavailable", e)
            }
        }

        fun validate(arguments: List<String>): String? {
            ensureLoaded()
            if (arguments.isEmpty()) return "Enter at least one argument."
            return nativeValidate(arguments.toTypedArray())
        }

        fun open(strategy: DpiStrategySelector.Strategy): NativeByeDpi {
            ensureLoaded()
            val handle = nativeStart(strategy.arguments.toTypedArray())
            if (handle == 0L) throw IOException("Native ByeDPI initialization failed")
            return NativeByeDpi(handle)
        }

        @JvmStatic private external fun nativeValidate(arguments: Array<String>): String?
        @JvmStatic private external fun nativeStart(arguments: Array<String>): Long
        @JvmStatic private external fun nativeOpenWarpStream(handle: Long): Int
        @JvmStatic private external fun nativeStop(handle: Long)
        @JvmStatic private external fun nativeSetReadTimeout(fd: Int, timeoutMillis: Int): Boolean

        fun setReadTimeout(stream: ParcelFileDescriptor, timeoutMillis: Int) {
            if (!nativeSetReadTimeout(stream.fd, timeoutMillis)) {
                throw IOException("Unable to set private WARP stream timeout")
            }
        }
    }

    /** Returns an owned descriptor for a private connected stream, not a listener. */
    @Synchronized
    fun openWarpStream(): ParcelFileDescriptor {
        check(handle != 0L) { "Native ByeDPI session closed" }
        val fd = nativeOpenWarpStream(handle)
        if (fd < 0) throw IOException("Unable to open WARP stream")
        return ParcelFileDescriptor.adoptFd(fd)
    }

    @Synchronized
    override fun close() {
        val current = handle
        if (current == 0L) return
        handle = 0
        nativeStop(current)
    }
}
