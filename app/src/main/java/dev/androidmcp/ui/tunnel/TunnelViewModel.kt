package dev.androidmcp.ui.tunnel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.androidmcp.data.SettingsRepository
import dev.androidmcp.tunnel.Binaries
import dev.androidmcp.tunnel.BinaryInstaller
import dev.androidmcp.tunnel.CloudflaredManager
import dev.androidmcp.tunnel.FrpcManager
import dev.androidmcp.tunnel.TunnelRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TunnelViewModel @Inject constructor(
    settings: SettingsRepository,
    private val repo: TunnelRepository,
    val cloudflared: CloudflaredManager,
    val frpc: FrpcManager,
    installer: BinaryInstaller,
) : ViewModel() {

    /** 本机 MCP 服务端口（只读，来自全局设置）。 */
    val port = settings.port.stateIn(viewModelScope, SharingStarted.Eagerly, 8080)

    val cfConfig = repo.cloudflaredConfig.stateIn(viewModelScope, SharingStarted.Eagerly, dev.androidmcp.tunnel.CloudflaredConfig())
    val frpcConfig = repo.frpcConfig.stateIn(viewModelScope, SharingStarted.Eagerly, dev.androidmcp.tunnel.FrpcConfig())

    val cfBinState = installer.stateOf(Binaries.CLOUDFLARED)
    val frpcBinState = installer.stateOf(Binaries.FRPC)

    fun setCfMode(value: String) = viewModelScope.launch { repo.setCloudflaredMode(value) }
    fun setCfToken(value: String) = viewModelScope.launch { repo.setCloudflaredToken(value) }

    fun setFrpcAddr(value: String) = viewModelScope.launch { repo.setFrpcServerAddr(value) }
    fun setFrpcPort(value: Int) = viewModelScope.launch { repo.setFrpcServerPort(value) }
    fun setFrpcToken(value: String) = viewModelScope.launch { repo.setFrpcToken(value) }
    fun setFrpcRemote(value: Int) = viewModelScope.launch { repo.setFrpcRemotePort(value) }

    /** 开启 cloudflared（调用前 UI 已弹过安全确认）。 */
    fun enableCloudflared() = viewModelScope.launch {
        repo.setCloudflaredEnabled(true)
        cloudflared.start()
    }

    fun disableCloudflared() = viewModelScope.launch {
        repo.setCloudflaredEnabled(false)
        cloudflared.stop()
    }

    fun enableFrpc() = viewModelScope.launch {
        repo.setFrpcEnabled(true)
        frpc.start()
    }

    fun disableFrpc() = viewModelScope.launch {
        repo.setFrpcEnabled(false)
        frpc.stop()
    }
}
