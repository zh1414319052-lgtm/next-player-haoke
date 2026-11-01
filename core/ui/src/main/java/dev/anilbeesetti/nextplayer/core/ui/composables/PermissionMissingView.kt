package dev.anilbeesetti.nextplayer.core.ui.composables

import androidx.compose.runtime.Composable
import android.Manifest
import androidx.compose.ui.res.stringResource
import dev.anilbeesetti.nextplayer.core.ui.R

@Composable
fun PermissionMissingView(
    isGranted: Boolean,
    showRationale: Boolean,
    permission: String,
    launchPermissionRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (isGranted) {
        content()
    } else {
        val mediaPermission = permission == Manifest.permission.READ_MEDIA_VIDEO || permission == Manifest.permission.READ_EXTERNAL_STORAGE
        val networkPermission = permission == "android.permission.ACCESS_LOCAL_NETWORK"
        // Keep the permission gate. Recomposition, return and configuration changes
        // must not reopen a modal or request access without an explicit user action.
        PermissionDetailView(
            title = stringResource(if (mediaPermission) R.string.cinema_permission_media_title else if (networkPermission) R.string.cinema_permission_network_title else R.string.permission_request),
            text = stringResource(if (mediaPermission) R.string.cinema_permission_media_help else if (networkPermission) R.string.cinema_permission_network_help else R.string.cinema_permission_generic_help),
            onRequestPermission = launchPermissionRequest,
        )
    }
}
