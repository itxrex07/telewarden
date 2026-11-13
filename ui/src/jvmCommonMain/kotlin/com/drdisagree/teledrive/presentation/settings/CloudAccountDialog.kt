package com.drdisagree.teledrive.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CloudAccountDialog(
    initialUsername: String = "",
    initialServerUrl: String = "",
    working: Boolean = false,
    onSignIn: (username: String, password: String, serverUrl: String) -> Unit,
    onRegister: (username: String, password: String, serverUrl: String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(if (initialUsername.isBlank()) 1 else 0) }
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var serverUrl by rememberSaveable { mutableStateOf(initialServerUrl) }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    val isRegister = selectedTab == 1
    val passwordsMatch = !isRegister || password == confirmPassword
    val canSubmit = username.trim().length >= 2 &&
            password.length >= 4 &&
            passwordsMatch &&
            !working

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        icon = {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "Encrypted Cloud Connection",
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(text = "Cloud Connection")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { if (!working) selectedTab = 0 },
                        text = { Text("Sign In") },
                        modifier = Modifier.testTag("cloud_tab_signin")
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { if (!working) selectedTab = 1 },
                        text = { Text("Register") },
                        modifier = Modifier.testTag("cloud_tab_register")
                    )
                }

                Text(
                    text = if (isRegister) {
                        "Register a cloud account to encrypt and save your session and app settings userdata (AES-256-GCM + PBKDF2/Scrypt)."
                    } else {
                        "Sign in to your cloud connection to restore or update your encrypted session and settings userdata."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    placeholder = { Text("Enter username") },
                    singleLine = true,
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cloud_username_input")
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    placeholder = { Text("At least 4 characters") },
                    singleLine = true,
                    enabled = !working,
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (showPassword) "Hide password" else "Show password"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (isRegister) ImeAction.Next else ImeAction.Done
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cloud_password_input")
                )

                if (isRegister) {
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("Confirm Password") },
                        placeholder = { Text("Re-enter password") },
                        singleLine = true,
                        enabled = !working,
                        isError = confirmPassword.isNotEmpty() && !passwordsMatch,
                        supportingText = if (confirmPassword.isNotEmpty() && !passwordsMatch) {
                            { Text("Passwords do not match") }
                        } else null,
                        visualTransformation = if (showPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cloud_confirm_password_input")
                    )
                }

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Backend Server URL (Optional)") },
                    placeholder = { Text("http://10.0.2.2:8787") },
                    singleLine = true,
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cloud_server_url_input")
                )
                Text(
                    text = "Enter your server.ts backend URL, or leave blank to store encrypted session & settings in your private Telegram vault.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isRegister) {
                        onRegister(username.trim(), password, serverUrl.trim())
                    } else {
                        onSignIn(username.trim(), password, serverUrl.trim())
                    }
                },
                enabled = canSubmit,
                modifier = Modifier.testTag("cloud_submit_button")
            ) {
                if (working) {
                    LoadingIndicator(modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isRegister) "Registering…" else "Signing In…")
                } else {
                    Text(if (isRegister) "Register & Save" else "Sign In")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !working,
                modifier = Modifier.testTag("cloud_cancel_button")
            ) {
                Text("Cancel")
            }
        }
    )
}
