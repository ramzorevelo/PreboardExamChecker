package com.pbec.preboardexamchecker.ui.account

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.firebase.auth.FirebaseAuth
import com.pbec.preboardexamchecker.ui.Screen
import com.pbec.preboardexamchecker.ui.theme.BrandTopAppBar
import androidx.compose.ui.platform.LocalContext

@Composable
fun AccountScreen(navController: NavController) {
    val auth = FirebaseAuth.getInstance()
    val user = auth.currentUser
    val context = LocalContext.current
    val sharedPreferences = remember(context) { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val teacherName = remember(sharedPreferences) { sharedPreferences.getString("teacher_name", "Teacher") ?: "Teacher" }
    val teacherEmail = remember(sharedPreferences) { sharedPreferences.getString("teacher_email", "") ?: "" }
    val teacherId = remember(sharedPreferences) { sharedPreferences.getString("teacher_id", "N/A") ?: "N/A" }
    val teacherDepartment = remember(sharedPreferences) { sharedPreferences.getString("teacher_department", "") ?: "" }
    val teacherSchool = remember(sharedPreferences) { sharedPreferences.getString("teacher_school", "") ?: "" }
    val teacherPosition = remember(sharedPreferences) { sharedPreferences.getString("teacher_position", "") ?: "" }
    val teacherRole = remember(sharedPreferences) { sharedPreferences.getString("teacher_role", "teacher") ?: "teacher" }
    val scrollState = rememberScrollState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            BrandTopAppBar(title = "Settings")
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Profile Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Profile",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = teacherName,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = teacherEmail.ifBlank { user?.email ?: "No email available" },
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "ID: $teacherId",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (teacherSchool.isNotBlank() || teacherPosition.isNotBlank() || teacherDepartment.isNotBlank() || teacherRole.isNotBlank()) {
                            Text(
                                text = listOf(
                                    teacherSchool,
                                    teacherPosition.ifBlank { teacherDepartment },
                                    teacherRole.replaceFirstChar { it.uppercase() }
                                )
                                    .filter { it.isNotBlank() }
                                    .joinToString(" • "),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader("Account")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                AccountMenuItem(
                    icon = Icons.Default.Shield,
                    title = "Security",
                    subtitle = "Password and authentication",
                    onClick = { navController.navigate(Screen.Security.route) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader("App")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column {
                    AccountMenuItem(
                        icon = Icons.Default.DocumentScanner,
                        title = "Scanning",
                        subtitle = "Scan mode, raw image saving, auto-advance",
                        onClick = { navController.navigate(Screen.ScanSettings.route) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    AccountMenuItem(
                        icon = Icons.Default.Palette,
                        title = "Appearance",
                        subtitle = "Theme and font size",
                        onClick = { navController.navigate(Screen.Appearance.route) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    AccountMenuItem(
                        icon = Icons.Default.Email,
                        title = "Email",
                        subtitle = "Send result slips to students by email",
                        onClick = { navController.navigate(Screen.EmailSettings.route) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader("Support")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column {
                    AccountMenuItem(
                        icon = Icons.AutoMirrored.Filled.HelpOutline,
                        title = "Help & Tutorial",
                        subtitle = "View app guide and onboarding"
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    AccountMenuItem(
                        icon = Icons.Default.Description,
                        title = "Terms & Privacy",
                        subtitle = "Read our policies"
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    AccountMenuItem(
                        icon = Icons.Default.Info,
                        title = "About",
                        subtitle = "Version 1.0.0",
                        showArrow = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    auth.signOut()
                    sharedPreferences.edit()
                        .remove("teacher_id")
                        .remove("teacher_name")
                        .remove("teacher_email")
                        .remove("teacher_department")
                        .remove("teacher_school")
                        .remove("teacher_position")
                        .remove("teacher_role")
                        .apply()
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Logout, contentDescription = "Logout")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Logout", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
    )
}

@Composable
fun AccountMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit = {},
    showArrow: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showArrow) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
