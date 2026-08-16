package dev.androidmcp

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dagger.hilt.android.AndroidEntryPoint
import dev.androidmcp.permission.PermissionCenter
import dev.androidmcp.ui.AppNav
import dev.androidmcp.ui.theme.AndroidMcpTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var permissionCenter: PermissionCenter

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionCenter.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            // Android 16+ Live Update 晋升胶囊需要运行时授权（字面量，低版本系统忽略）
            if (Build.VERSION.SDK_INT >= 36) add("android.permission.POST_PROMOTED_NOTIFICATIONS")
        }
        if (permissions.isNotEmpty()) permissionsLauncher.launch(permissions.toTypedArray())
        setContent {
            AndroidMcpTheme {
                AppNav()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::permissionCenter.isInitialized) permissionCenter.refresh()
    }
}
