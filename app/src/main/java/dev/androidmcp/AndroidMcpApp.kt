package dev.androidmcp

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.androidmcp.auth.ApiKeyStore
import dev.androidmcp.server.NotificationHelper
import dev.androidmcp.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AndroidMcpApp : Application() {

    @Inject
    lateinit var apiKeyStore: ApiKeyStore

    @Inject
    lateinit var shizukuManager: ShizukuManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        shizukuManager.init()
        // 首次启动自动创建 default API Key（plaintext 经 ApiKeyStore.pendingNewKey 暴露给 UI 展示一次）
        appScope.launch { apiKeyStore.ensureDefaultKey() }
    }
}
