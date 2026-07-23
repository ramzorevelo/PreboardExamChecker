package com.pbec.preboardexamchecker.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.hilt.navigation.compose.hiltViewModel
import com.pbec.preboardexamchecker.ui.Screen
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState

@Composable
fun LoginScreen(
    navController: NavController,
    onLogin: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val fullName = remember { mutableStateOf("") }
    val school = remember { mutableStateOf("") }
    val position = remember { mutableStateOf("") }
    val email = remember { mutableStateOf("") }
    val password = remember { mutableStateOf("") }
    val instructorId = remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isRegisterMode by remember { mutableStateOf(false) }
    var registerAttempted by remember { mutableStateOf(false) }
    var registrationDialogTitle by remember { mutableStateOf<String?>(null) }
    var registrationDialogMessage by remember { mutableStateOf<String?>(null) }
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    LaunchedEffect(isRegisterMode) {
        viewModel.clearError()
        registrationDialogTitle = null
        registrationDialogMessage = null
    }

    LaunchedEffect(errorMessage, registerAttempted) {
        if (registerAttempted && !errorMessage.isNullOrBlank()) {
            registrationDialogTitle = "Registration Failed"
            registrationDialogMessage = errorMessage
            registerAttempted = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Pre-Board Exam Checker",
            style = MaterialTheme.typography.headlineMedium, 
            color = MaterialTheme.colorScheme.primary 
        )
        Spacer(modifier = Modifier.height(32.dp))
        if (isRegisterMode) {
            OutlinedTextField(
                value = fullName.value,
                onValueChange = { fullName.value = it },
                label = { Text("Full Name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = school.value,
                onValueChange = { school.value = it },
                label = { Text("School") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = position.value,
                onValueChange = { position.value = it },
                label = { Text("Position") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = email.value,
                onValueChange = { email.value = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            OutlinedTextField(
                value = instructorId.value,
                onValueChange = { instructorId.value = it },
                label = { Text("Teacher ID Number") },
                placeholder = { Text("T2026-001") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (!isRegisterMode) {
            OutlinedTextField(
                value = password.value,
                onValueChange = { password.value = it },
                label = { Text("Password") },
                placeholder = { Text("T26001") },
                visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        errorMessage?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                if (isRegisterMode) {
                    registerAttempted = true
                    viewModel.register(
                        fullName = fullName.value,
                        school = school.value,
                        position = position.value,
                        email = email.value
                    ) { generatedTeacherId, generatedPassword ->
                        registrationDialogTitle = "Registration Successful"
                        registrationDialogMessage = "Teacher ID: $generatedTeacherId\nDefault password: $generatedPassword\n\nAccount is inactive for up to 7 days or until admin activation."
                        registerAttempted = false
                        instructorId.value = generatedTeacherId
                        password.value = ""
                        fullName.value = ""
                        school.value = ""
                        position.value = ""
                        email.value = ""
                    }
                } else {
                    viewModel.login(instructorId.value, password.value) {
                        // Let App-level navigation decide whether to show onboarding or programs.
                        onLogin()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.height(18.dp)
                )
            } else {
                Text(if (isRegisterMode) "Register" else "Login")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = {
                isRegisterMode = !isRegisterMode
            },
            enabled = !isLoading
        ) {
            Text(
                if (isRegisterMode) {
                    "Already have an account? Login"
                } else {
                    "No account yet? Register"
                }
            )
        }
    }

    if (!registrationDialogTitle.isNullOrBlank() && !registrationDialogMessage.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = {
                registrationDialogTitle = null
                registrationDialogMessage = null
                isRegisterMode = false
                navController.navigate(Screen.Login.route) {
                    popUpTo(Screen.Login.route) { inclusive = true }
                }
            },
            title = { Text(registrationDialogTitle ?: "") },
            text = { Text(registrationDialogMessage ?: "") },
            confirmButton = {
                Button(onClick = {
                    registrationDialogTitle = null
                    registrationDialogMessage = null
                    isRegisterMode = false
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }) {
                    Text("OK")
                }
            }
        )
    }
}