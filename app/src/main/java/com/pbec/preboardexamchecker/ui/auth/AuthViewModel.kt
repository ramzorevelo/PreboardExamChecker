package com.pbec.preboardexamchecker.ui.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val firestore: FirebaseFirestore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val teacherIdRegex = Regex("^T\\d{4}-\\d{3}$")

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    fun login(instructorId: String, password: String, onSuccess: () -> Unit) {
        val normalizedInstructorId = instructorId.trim().uppercase()
        if (normalizedInstructorId.isBlank() || password.isBlank()) {
            _errorMessage.value = "Teacher ID and password are required."
            return
        }
        if (!teacherIdRegex.matches(normalizedInstructorId)) {
            _errorMessage.value = "Teacher ID format must be like T2026-001."
            return
        }

        _isLoading.value = true
        val hashedInputPassword = sha256(password.trim())

        ensureFirebaseSession {
            firestore.collection("users")
                .whereEqualTo("teacherId", normalizedInstructorId)
                .limit(1)
                .get()
                .addOnSuccessListener { teacherSnapshot ->
                    if (!teacherSnapshot.isEmpty) {
                        validatePasswordAndStartSession(
                            teacherDocument = teacherSnapshot.documents.firstOrNull(),
                            hashedInputPassword = hashedInputPassword,
                            onSuccess = onSuccess
                        )
                        return@addOnSuccessListener
                    }

                    // Backward compatibility for older records that used instructorId key.
                    firestore.collection("users")
                        .whereEqualTo("instructorId", normalizedInstructorId)
                        .limit(1)
                        .get()
                        .addOnSuccessListener { instructorSnapshot ->
                            if (instructorSnapshot.isEmpty) {
                                _isLoading.value = false
                                _errorMessage.value = "Teacher ID not found."
                                return@addOnSuccessListener
                            }

                            validatePasswordAndStartSession(
                                teacherDocument = instructorSnapshot.documents.firstOrNull(),
                                hashedInputPassword = hashedInputPassword,
                                onSuccess = onSuccess
                            )
                        }
                        .addOnFailureListener { error ->
                            _isLoading.value = false
                            _errorMessage.value = "Cannot access teacher records: ${error.message}"
                        }
                }
                .addOnFailureListener { error ->
                    _isLoading.value = false
                    _errorMessage.value = "Cannot access teacher records: ${error.message}"
                }
        }
    }

    fun register(
        fullName: String,
        school: String,
        position: String,
        email: String,
        onSuccess: (String, String) -> Unit
    ) {
        if (fullName.isBlank() || school.isBlank() || position.isBlank() || email.isBlank()) {
            _errorMessage.value = "Full name, school, position, and email are required."
            return
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
            _errorMessage.value = "Please enter a valid email address."
            return
        }

        _isLoading.value = true

        ensureFirebaseSession {
            firestore.collection("users")
                .whereEqualTo("email", email.trim())
                .limit(1)
                .get()
                .addOnSuccessListener { snapshot ->
                    if (!snapshot.isEmpty) {
                        _isLoading.value = false
                        _errorMessage.value = "Email is already registered."
                        return@addOnSuccessListener
                    }

                    generateTeacherId { generatedTeacherId ->
                        val defaultPassword = deriveDefaultPasswordFromTeacherId(generatedTeacherId)
                        val hashedPassword = sha256(defaultPassword)
                        val now = Timestamp.now()
                        val activationDeadline = Timestamp(java.util.Date(now.toDate().time + 7L * 24 * 60 * 60 * 1000))
                        val uid = auth.currentUser?.uid
                        if (uid.isNullOrBlank()) {
                            _isLoading.value = false
                            _errorMessage.value = "Session missing. Please try again."
                            return@generateTeacherId
                        }
                        firestore.collection("users")
                            .document(uid)
                            .set(
                                mapOf(
                                    "teacherId" to generatedTeacherId,
                                    "name" to fullName.trim(),
                                    "school" to school.trim(),
                                    "position" to position.trim(),
                                    "department" to position.trim(),
                                    "email" to email.trim(),
                                    "passwordHash" to hashedPassword,
                                    "role" to "teacher",
                                    "isActive" to false,
                                    "status" to "inactive",
                                    "activationDeadline" to activationDeadline,
                                    "createdAt" to now,
                                    "updatedAt" to now
                                )
                            )
                            .addOnSuccessListener {
                                _isLoading.value = false
                                _errorMessage.value = null
                                onSuccess(generatedTeacherId, defaultPassword)
                            }
                            .addOnFailureListener { error ->
                                _isLoading.value = false
                                _errorMessage.value = "Cannot create teacher account: ${error.message}"
                            }
                    }
                }
                .addOnFailureListener { error ->
                    _isLoading.value = false
                    _errorMessage.value = "Cannot access teacher records: ${error.message}"
                }
        }
    }

    private fun validatePasswordAndStartSession(
        teacherDocument: DocumentSnapshot?,
        hashedInputPassword: String,
        onSuccess: () -> Unit
    ) {
        val snapshotHash = teacherDocument?.getString("passwordHash")
        if (snapshotHash.isNullOrBlank()) {
            _isLoading.value = false
            _errorMessage.value = "Teacher account has no password hash."
            return
        }

        if (!snapshotHash.equals(hashedInputPassword, ignoreCase = true)) {
            _isLoading.value = false
            _errorMessage.value = "Invalid login credentials."
            return
        }
        val isActive = teacherDocument.getBoolean("isActive") ?: true
        if (!isActive) {
            _isLoading.value = false
            val activationDeadline = teacherDocument.getTimestamp("activationDeadline")
            val deadlineMessage = activationDeadline?.toDate()?.let { " Activation deadline: $it." } ?: ""
            _errorMessage.value = "Account is inactive and pending admin activation.$deadlineMessage"
            return
        }
        teacherDocument?.let { persistTeacherSession(it) }
        _isLoading.value = false
        _errorMessage.value = null
        onSuccess()
    }

    private fun ensureFirebaseSession(onReady: () -> Unit) {
        val existingUser = auth.currentUser
        if (existingUser != null) {
            onReady()
            return
        }

        auth.signInAnonymously()
            .addOnSuccessListener {
                onReady()
            }
            .addOnFailureListener { error ->
                _isLoading.value = false
                _errorMessage.value = "Firebase auth failed: ${error.message}. Enable Anonymous auth in Firebase."
            }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        val hexBuilder = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            hexBuilder.append("%02x".format(byte))
        }
        return hexBuilder.toString()
    }

    private fun persistTeacherSession(teacherDocument: DocumentSnapshot) {
        val teacherId = teacherDocument.getString("teacherId")
            ?: teacherDocument.getString("instructorId")
            ?: ""
        val teacherName = teacherDocument.getString("name") ?: "Teacher"
        val teacherEmail = teacherDocument.getString("email") ?: ""
        val teacherDepartment = teacherDocument.getString("department") ?: ""
        val teacherSchool = teacherDocument.getString("school") ?: ""
        val teacherPosition = teacherDocument.getString("position") ?: teacherDepartment
        val teacherRole = teacherDocument.getString("role") ?: "teacher"

        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("teacher_id", teacherId)
            .putString("teacher_name", teacherName)
            .putString("teacher_email", teacherEmail)
            .putString("teacher_department", teacherDepartment)
            .putString("teacher_school", teacherSchool)
            .putString("teacher_position", teacherPosition)
            .putString("teacher_role", teacherRole)
            .apply()
    }

    private fun generateTeacherId(onReady: (String) -> Unit) {
        val year = Calendar.getInstance().get(Calendar.YEAR).toString()
        val prefix = "T$year-"
        firestore.collection("users")
            .whereGreaterThanOrEqualTo("teacherId", "${prefix}000")
            .whereLessThanOrEqualTo("teacherId", "${prefix}999")
            .orderBy("teacherId", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .addOnSuccessListener { snapshot ->
                val lastTeacherId = snapshot.documents.firstOrNull()?.getString("teacherId")
                val lastSeries = lastTeacherId
                    ?.substringAfter("-")
                    ?.toIntOrNull()
                    ?: 0
                val nextSeries = (lastSeries + 1).coerceAtLeast(1)
                onReady("$prefix${nextSeries.toString().padStart(3, '0')}")
            }
            .addOnFailureListener { error ->
                _isLoading.value = false
                _errorMessage.value = "Failed to generate Teacher ID: ${error.message}"
            }
    }

    private fun deriveDefaultPasswordFromTeacherId(teacherId: String): String {
        val yearPart = teacherId.removePrefix("T").substringBefore("-")
        val shortYear = yearPart.takeLast(2)
        val series = teacherId.substringAfter("-")
        return "T$shortYear$series"
    }
}
