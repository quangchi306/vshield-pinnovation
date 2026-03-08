package com.trustnet.vshield.ui.parenting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun ParentAuthDialog(
    title: String,
    message: String,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onVerify: (password: String) -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }


    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(message)
                Spacer(modifier = androidx.compose.ui.Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Mật khẩu phụ huynh") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    )
                )

                if (!errorMessage.isNullOrBlank()) {
                    Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onVerify(password) },
                enabled = password.isNotBlank()
            ) { Text("Xác minh") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy") }
        }
    )
}

@Composable
fun ParentSetPasswordDialog(
    isChange: Boolean,
    minLength: Int,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (newPassword: String) -> Unit
) {
    var p1 by rememberSaveable { mutableStateOf("") }
    var p2 by rememberSaveable { mutableStateOf("") }
    val localError = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(p1, p2, errorMessage) {

        localError.value = null
    }

    val title = if (isChange) "Đổi mật khẩu phụ huynh" else "Đặt mật khẩu phụ huynh"
    val canSubmit = p1.length >= minLength && p1 == p2

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Mật khẩu dùng để xác minh khi thay đổi cài đặt quan trọng của V‑Shield.")
                Spacer(modifier = androidx.compose.ui.Modifier.height(12.dp))

                OutlinedTextField(
                    value = p1,
                    onValueChange = { p1 = it },
                    label = { Text("Mật khẩu mới") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    )
                )

                Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))

                OutlinedTextField(
                    value = p2,
                    onValueChange = { p2 = it },
                    label = { Text("Nhập lại mật khẩu") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    )
                )

                Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))

                val msg = localError.value ?: errorMessage
                if (!msg.isNullOrBlank()) {
                    Text(text = msg, color = MaterialTheme.colorScheme.error)
                } else if (p1.isNotBlank() && p1.length < minLength) {
                    Text(
                        text = "Tối thiểu $minLength ký tự.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (p2.isNotBlank() && p1 != p2) {
                    Text(
                        text = "Mật khẩu nhập lại không khớp.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        p1.length < minLength -> localError.value = "Mật khẩu quá ngắn (>= $minLength)."
                        p1 != p2 -> localError.value = "Mật khẩu nhập lại không khớp."
                        else -> onSubmit(p1)
                    }
                },
                enabled = canSubmit
            ) { Text("Lưu") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy") }
        }
    )
}