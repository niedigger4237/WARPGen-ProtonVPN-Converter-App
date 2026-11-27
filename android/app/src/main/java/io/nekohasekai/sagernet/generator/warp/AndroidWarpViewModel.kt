package io.nekohasekai.sagernet.generator.warp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

internal class AndroidWarpViewModel(application: Application) : AndroidViewModel(application) {
    private val dpi = WarpDpiTransport(application)
    val model = WarpViewModel(
        { name -> application.assets.open(name).bufferedReader().use { it.readText() } },
        viewModelScope,
        dpi::register
    )
}
