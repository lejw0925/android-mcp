package dev.androidmcp.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.androidmcp.permission.PermissionCenter
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    val permissionCenter: PermissionCenter,
) : ViewModel() {
    val pendingRequest = permissionCenter.pendingRequest
    fun dismissRequest() = permissionCenter.dismissRequest()
}
