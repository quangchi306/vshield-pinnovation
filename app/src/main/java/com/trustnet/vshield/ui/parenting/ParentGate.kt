package com.trustnet.vshield.ui.parenting

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trustnet.vshield.parenting.ParentAction
import com.trustnet.vshield.parenting.ParentingViewModel
import kotlinx.coroutines.launch

interface ParentGate {
    fun protect(action: ParentAction, block: () -> Unit)
}

private object NoOpParentGate : ParentGate {
    override fun protect(action: ParentAction, block: () -> Unit) = block()
}

val LocalParentGate = staticCompositionLocalOf<ParentGate> { NoOpParentGate }

@Composable
fun ParentGateHost(
    viewModel: ParentingViewModel = viewModel(),
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()

    var showAuthDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingType by remember { mutableStateOf(ParentAction.Generic) }
    var authError by remember { mutableStateOf<String?>(null) }

    val gate = remember(viewModel) {
        object : ParentGate {
            override fun protect(action: ParentAction, block: () -> Unit) {
                if (!viewModel.shouldRequireAuthFor(action)) {
                    block()
                    return
                }
                pendingAction = block
                pendingType = action
                authError = null
                showAuthDialog = true
            }
        }
    }

    CompositionLocalProvider(LocalParentGate provides gate) {
        content()
    }

    if (showAuthDialog) {
        val (title, message) = authTextFor(pendingType)
        ParentAuthDialog(
            title = title,
            message = message,
            errorMessage = authError,
            onDismiss = {
                showAuthDialog = false
                pendingAction = null
                authError = null
            },
            onVerify = { password ->
                scope.launch {
                    val ok = viewModel.verifyPassword(password)
                    if (ok) {
                        showAuthDialog = false
                        authError = null

                        if (ParentingViewModel.ENABLE_UNLOCK_SESSION) {
                            viewModel.unlockForWindow()
                        }

                        val toRun = pendingAction
                        pendingAction = null
                        toRun?.invoke()
                    } else {
                        authError = "Sai mật khẩu phụ huynh."
                    }
                }
            }
        )
    }
}

private fun authTextFor(action: ParentAction): Pair<String, String> {
    return when (action) {
        ParentAction.ToggleProtectionOff ->
            "Xác minh phụ huynh" to "Nhập mật khẩu để tắt V‑Shield Protection."
        ParentAction.ToggleProtectionOn ->
            "Xác minh phụ huynh" to "Nhập mật khẩu để bật V‑Shield Protection."
        ParentAction.ChangeDns ->
            "Xác minh phụ huynh" to "Nhập mật khẩu để thay đổi DNS Server."
        ParentAction.ChangeFilterConfig ->
            "Xác minh phụ huynh" to "Nhập mật khẩu để thay đổi cấu hình lọc."
        ParentAction.UpdateBlocklist ->
            "Xác minh phụ huynh" to "Nhập mật khẩu để cập nhật blocklist."
        ParentAction.ToggleParentingMode ->
            "Xác minh phụ huynh" to "Nhập mật khẩu để bật/tắt Parenting Mode."
        ParentAction.ChangeParentPassword ->
            "Xác minh phụ huynh" to "Nhập mật khẩu để đổi mật khẩu phụ huynh."
        ParentAction.UnlockSession ->
            "Xác minh phụ huynh" to "Nhập mật khẩu để mở khóa tạm thời (5 phút)."
        ParentAction.Generic ->
            "Xác minh phụ huynh" to "Nhập mật khẩu phụ huynh để tiếp tục."
    }
}