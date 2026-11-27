package io.nekohasekai.sagernet.generator.warp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class WarpViewModel(application: Application) : AndroidViewModel(application) {
    enum class Transport { DIRECT, LOCAL_PROXY, BYEDPI }
    data class State(
        val settings: WarpGenerator.Settings = WarpGenerator.Settings(),
        val transport: Transport = Transport.DIRECT,
        val accountAddress: String? = null,
        val output: WarpConfExporter.Output? = null,
        val busy: Boolean = false,
        val strategyProgress: String? = null,
        val error: String? = null
    )
    private val generator = WarpGenerator(application)
    private val dpiTransport = WarpDpiTransport(application)
    val presets get() = generator.presets
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var account: WarpRegistration.Account? = null
    private var seed: WarpPresets.Seed? = null
    private var job: Job? = null
    private var revision = 0L

    // UI entry points must be called on the main thread.
    fun settings(value: WarpGenerator.Settings) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(settings = value, error = null, output = null)
        if (account != null && seed != null) render(false)
    }

    fun transport(value: Transport) {
        if (!mutableState.value.busy)
            mutableState.value = mutableState.value.copy(transport = value)
    }

    fun generate() {
        if (!mutableState.value.busy) render(true)
    }

    fun cancel() {
        job?.cancel()
        if (mutableState.value.transport != Transport.BYEDPI) {
            revision++
            job = null
            mutableState.value = mutableState.value.copy(busy = false)
        }
    }

    fun resetAccount() {
        if (mutableState.value.busy) return
        account = null
        seed = null
        mutableState.value = mutableState.value.copy(accountAddress = null, output = null, error = null)
    }

    private fun render(generate: Boolean) {
        val id = ++revision
        val snapshot = mutableState.value
        val existing = account
        val previousSeed = seed
        mutableState.value = snapshot.copy(busy = true, error = null, output = null)
        job = viewModelScope.launch {
            try {
                val current = existing ?: if (snapshot.transport == Transport.BYEDPI) {
                    dpiTransport.register { index, count, strategy ->
                        mutableState.update { state ->
                            state.copy(strategyProgress = "ByeDPI strategy $index/$count · ${strategy.name}")
                        }
                    }
                } else withContext(Dispatchers.IO) {
                    val connection = if (snapshot.transport == Transport.LOCAL_PROXY)
                        WarpApi.Connection.LocalProxy else WarpApi.Connection.Direct
                    WarpRegistration.register { method, endpoint, body, token ->
                        WarpApi.request(method, endpoint, body, token, connection)
                    }
                }
                if (id != revision) return@launch
                account = current
                mutableState.value = mutableState.value.copy(accountAddress = current.ipv4, strategyProgress = null)
                val currentSeed = if (generate || previousSeed == null) presets.newSeed() else previousSeed
                seed = currentSeed
                val output = withContext(Dispatchers.Default) {
                    generator.render(current, currentSeed, snapshot.settings)
                }
                if (id == revision) mutableState.value = mutableState.value.copy(output = output)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (id == revision) mutableState.value = mutableState.value.copy(
                    error = e.message ?: "WARP generation failed", output = null)
            } finally {
                if (id == revision) mutableState.value = mutableState.value.copy(busy = false, strategyProgress = null)
            }
        }
    }
}
