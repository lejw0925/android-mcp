package dev.androidmcp.ui.permissions

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.androidmcp.permission.PermissionCenter
import javax.inject.Inject

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    val permissionCenter: PermissionCenter,
) : ViewModel()
