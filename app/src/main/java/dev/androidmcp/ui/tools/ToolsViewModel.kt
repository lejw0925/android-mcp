package dev.androidmcp.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.androidmcp.data.SettingsRepository
import dev.androidmcp.permission.PermissionCenter
import dev.androidmcp.tools.McpTool
import dev.androidmcp.tools.ToolRegistry
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val registry: ToolRegistry,
    private val settings: SettingsRepository,
    private val permissionCenter: PermissionCenter,
) : ViewModel() {

    val tools: List<McpTool> = registry.all()
    val permissionState: StateFlow<Long> = permissionCenter.stateVersion

    fun enabledFlow(tool: McpTool) = settings.toolEnabledFlow(tool.name, tool.defaultEnabled)

    fun setEnabled(tool: McpTool, enabled: Boolean) = viewModelScope.launch {
        settings.setToolEnabled(tool.name, tool.defaultEnabled, enabled)
    }

    fun refreshPermissions() = permissionCenter.refresh()
}
