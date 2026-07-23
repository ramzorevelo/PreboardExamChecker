package com.pbec.preboardexamchecker

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pbec.preboardexamchecker.data.repository.IExamRepository
import com.pbec.preboardexamchecker.ui.Screen
import com.pbec.preboardexamchecker.ui.account.AccountScreen
import com.pbec.preboardexamchecker.ui.account.SecurityScreen
import com.pbec.preboardexamchecker.ui.auth.LoginScreen
import com.pbec.preboardexamchecker.ui.exams.ExamContentScreen
import com.pbec.preboardexamchecker.ui.exams.ExamScreen
import com.pbec.preboardexamchecker.ui.exambank.ImportSessionDetailsScreen
import com.pbec.preboardexamchecker.ui.onboarding.OnboardingScreen
import androidx.activity.viewModels
import com.pbec.preboardexamchecker.ui.scanner.ScanSettings
import com.pbec.preboardexamchecker.ui.scanner.ScannerEntryPoint
import com.pbec.preboardexamchecker.ui.scanner.ScannerViewModel
import com.pbec.preboardexamchecker.ui.programs.ProgramsScreen
import com.pbec.preboardexamchecker.ui.students.StudentsScreen
import com.pbec.preboardexamchecker.ui.subjects.SubjectsScreen
import com.pbec.preboardexamchecker.ui.records.RecordsScreen
import com.pbec.preboardexamchecker.ui.records.TrashScreen
import com.pbec.preboardexamchecker.ui.theme.PreBoardExamCheckerTheme
import com.pbec.preboardexamchecker.ui.theme.ThemeMode
import com.pbec.preboardexamchecker.ui.theme.ThemeSettings
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var examRepositoryInterface: IExamRepository

    private val scannerViewModel: ScannerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by ThemeSettings.modeState(this)
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            PreBoardExamCheckerTheme(darkTheme = darkTheme) {
                App(
                    context = this,
                    examRepositoryInterface = examRepositoryInterface,
                    onCaptureTabRetap = { scannerViewModel.requestManualCapture() },
                    captureProgress = scannerViewModel.captureProgress,
                    sessionActive = scannerViewModel.sessionActive
                )
            }
        }
    }
}

// Bottom-nav roots; everything else on the stack is a detail owned by the root below it.
private val TAB_ROUTES = listOf(
    Screen.Programs.route, // start / Home
    Screen.Students.route,
    Screen.Capture.route,
    Screen.Records.route,
    Screen.Account.route,
)

// Tab owning the visible screen: topmost root on the stack, skipping details above it.
private fun NavController.currentTabRoot(): String =
    currentBackStack.value.lastOrNull { it.destination.route?.substringBefore('?') in TAB_ROUTES }
        ?.destination?.route?.substringBefore('?') ?: Screen.Programs.route

private fun NavController.switchTab(targetRoute: String) {
    val currentRoot = currentTabRoot()
    // Re-tap: drop details without saveState, keeping the tab's entry (and its
    // ViewModel-held filters) alive. Home collapses to root.
    if (targetRoute == currentRoot) {
        popBackStack(targetRoute, inclusive = false)
        return
    }
    navigate(targetRoute) {
        // A saved stack is keyed by both the popUpTo target and the deepest popped id.
        // Leaving Home: exclusive pop keys its tree to the start for restore. Leaving
        // any other tab: inclusive pop keys only that tab's root, so the start id never
        // points at a sibling tab (the old Home hijack).
        if (currentRoot == Screen.Programs.route) {
            popUpTo(Screen.Programs.route) { saveState = true }
        } else {
            popUpTo(currentRoot) { inclusive = true; saveState = true }
        }
        launchSingleTop = true
        restoreState = true
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun App(
    context: Context,
    examRepositoryInterface: IExamRepository,
    onCaptureTabRetap: () -> Unit = {},
    captureProgress: StateFlow<Float> = MutableStateFlow(0f),
    sessionActive: StateFlow<Boolean> = MutableStateFlow(false)
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Stability-countdown progress (0f when no scan is stabilising) — drives the arc
    // ring around the Capture nav button during a scan session.
    val captureArcProgress by captureProgress.collectAsState()
    // True while a scan session is active — switches the Capture nav button to the
    // camera-style capture circle (and hides its "Capture" label).
    val inSession by sessionActive.collectAsState()

    // Scanning → "Boost brightness while scanning": force full screen brightness for the
    // duration of a session, restoring the system default on exit. Only touches the window
    // when the toggle is actually on — a no-op otherwise, so it never relayouts mid-session.
    val activity = context as? ComponentActivity
    DisposableEffect(inSession) {
        val window = activity?.window
        val boosted = inSession && window != null && ScanSettings.isBoostBrightness(context)
        if (boosted) {
            window!!.attributes = window.attributes.apply { screenBrightness = 1f }
        }
        onDispose {
            if (boosted) {
                window!!.attributes = window.attributes.apply {
                    screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    val sharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    val onboardingCompleted = sharedPreferences.getBoolean("onboarding_completed", false)

    var isLoggedIn by remember {
        mutableStateOf(sharedPreferences.getString("teacher_id", null)?.isNotBlank() == true)
    }

    val startDestination = if (isLoggedIn && !onboardingCompleted) "onboarding_pager" 
                          else if (isLoggedIn) Screen.Programs.route 
                          else Screen.Login.route

    // Show navbar on all screens except Login and Onboarding
    val showMainNavigationBar = currentRoute != null && 
                                currentRoute != Screen.Login.route && 
                                currentRoute != "onboarding_pager"

    Scaffold(
        bottomBar = {
            if (showMainNavigationBar) {
                NavigationBar(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                    contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                ) {
                    val items = listOf(
                        Screen.Programs,
                        Screen.Students,
                        Screen.Capture,
                        Screen.Records,
                        Screen.Account
                    )

                    // Keeps a tab highlighted while inside its detail screens.
                    val currentTabRoot = remember(navBackStackEntry) { navController.currentTabRoot() }

                    items.forEach { screen ->
                        val isSelected = screen.route == currentTabRoot

                        if (screen == Screen.Capture) {
                            val captureClick = {
                                if (currentTabRoot == Screen.Capture.route) {
                                    onCaptureTabRetap()
                                } else {
                                    navController.switchTab(screen.route)
                                }
                            }
                            NavigationBarItem(
                                icon = {
                                    CaptureNavIcon(
                                        progress = captureArcProgress,
                                        sessionActive = inSession
                                    )
                                },
                                // Hide the "Capture" label during a session so the
                                // camera-style capture circle stands on its own.
                                label = if (inSession) null else {
                                    { Text("Capture", fontSize = 10.sp) }
                                },
                                alwaysShowLabel = !inSession,
                                // Drop the selected-state oval indicator during a session
                                // so the white capture circle isn't wrapped in a pill.
                                colors = if (inSession) {
                                    NavigationBarItemDefaults.colors(
                                        indicatorColor = androidx.compose.ui.graphics.Color.Transparent
                                    )
                                } else {
                                    NavigationBarItemDefaults.colors()
                                },
                                selected = isSelected,
                                onClick = captureClick
                            )
                        } else {
                            NavigationBarItem(
                                icon = { screen.icon() },
                                label = { Text(screen.title, fontSize = 10.sp) },
                                selected = isSelected,
                                onClick = { navController.switchTab(screen.route) }
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("onboarding_pager") {
                OnboardingScreen(navController = navController)
            }
            composable(Screen.Login.route) {
                isLoggedIn = sharedPreferences.getString("teacher_id", null)?.isNotBlank() == true
                LoginScreen(navController = navController, onLogin = { 
                    isLoggedIn = true
                    if (!onboardingCompleted) {
                        navController.navigate("onboarding_pager") { 
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    } else {
                        navController.navigate(Screen.Programs.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    }
                })
            }
            composable(Screen.Programs.route) {
                ProgramsScreen(navController = navController)
            }
            composable(Screen.Students.route) {
                StudentsScreen(navController = navController)
            }
            composable(Screen.Capture.route) {
                ScannerEntryPoint(navController = navController, examRepository = examRepositoryInterface)
            }
            composable(Screen.Records.route) {
                RecordsScreen(navController = navController)
            }
            composable(
                Screen.Trash.route,
                arguments = listOf(navArgument("tab") {
                    type = NavType.StringType; nullable = true; defaultValue = null
                })
            ) { backStackEntry ->
                TrashScreen(
                    navController = navController,
                    initialTab = backStackEntry.arguments?.getString("tab"),
                )
            }
            composable(Screen.Account.route) {
                AccountScreen(navController = navController)
            }
            composable(Screen.Security.route) {
                SecurityScreen(navController = navController)
            }
            composable(Screen.ScanSettings.route) {
                com.pbec.preboardexamchecker.ui.settings.ScanSettingsScreen(navController = navController)
            }
            composable(Screen.Appearance.route) {
                com.pbec.preboardexamchecker.ui.settings.AppearanceScreen(navController = navController)
            }
            composable(Screen.EmailSettings.route) {
                com.pbec.preboardexamchecker.ui.settings.EmailSettingsScreen(navController = navController)
            }
            composable(Screen.Subjects.route) { SubjectsScreen(navController) }
            composable(Screen.Exams.route) { backStackEntry ->
                val subject = backStackEntry.arguments?.getString("subject") ?: "Unknown"
                ExamScreen(navController, subject)
            }
            composable(Screen.ExamContent.route) {
                ExamContentScreen(navController)
            }
            composable(Screen.ImportSessionDetails.route) { backStackEntry ->
                val subject = backStackEntry.arguments?.getString("subject") ?: "Unknown"
                val questionBankId = backStackEntry.arguments?.getString("questionBankId") ?: "manual"
                ImportSessionDetailsScreen(navController, subject, questionBankId)
            }
        }
    }
}

/**
 * The bottom-nav "Capture" button. Two looks:
 *
 *  - **Outside a session** — the default primary circle with a Search icon (the
 *    caller also shows the "Capture" label).
 *  - **During a session** ([sessionActive]) — a camera-style white capture circle
 *    wrapped with a stability-countdown arc that fills clockwise from the top as the
 *    live scan locks onto all four markers. Re-tap still triggers a manual capture
 *    (handled by the caller). [progress] is 0f whenever no scan is stabilising.
 */
@Composable
private fun CaptureNavIcon(progress: Float, sessionActive: Boolean) {
    if (!sessionActive) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Capture",
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
        return
    }

    val animated by animateFloatAsState(progress, tween(80), label = "navArc")
    // Theme-aware so the white face stays visible on the light surface: a faint track in
    // onSurfaceVariant and a primary ring delineate the shutter circle on both themes.
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val faceRing = MaterialTheme.colorScheme.primary
    // Sized to nearly fill the 80dp-tall NavigationBar — this is roughly the ceiling
    // before the circle would be clipped by the nav bar's own bounds.
    Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            // Faint track ring (the camera "shutter" outline).
            drawArc(
                color = trackColor,
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(width = stroke)
            )
            if (animated > 0f) {
                drawArc(
                    color = Color(0xFF34C759),
                    startAngle = -90f, sweepAngle = animated * 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        // White inner circle — the capture-button face, with a primary ring so it reads
        // against the light-theme nav surface (where a bare white circle would vanish).
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color.White, CircleShape)
                .border(2.dp, faceRing, CircleShape)
        )
    }
}