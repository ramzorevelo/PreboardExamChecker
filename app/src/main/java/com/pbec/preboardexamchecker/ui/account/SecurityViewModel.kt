package com.pbec.preboardexamchecker.ui.account

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val firestore: FirebaseFirestore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _isUpdatingPassword = MutableStateFlow(false)
    val isUpdatingPassword: StateFlow<Boolean> = _isUpdatingPassword.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    fun changePassword(currentPassword: String, newPassword: String, confirmPassword: String, onSuccess: () -> Unit) {
        val teacherId = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getString("teacher_id", null)
            ?.trim()
            ?.uppercase()

        if (teacherId.isNullOrBlank()) {
            _uiEvent.tryEmit(UiEvent.ShowSnackbar("Teacher account not found. Please login again."))
            return
        }
        if (currentPassword.isBlank() || newPassword.isBlank() || confirmPassword.isBlank()) {
            _uiEvent.tryEmit(UiEvent.ShowSnackbar("All password fields are required."))
            return
        }
        if (newPassword != confirmPassword) {
            _uiEvent.tryEmit(UiEvent.ShowSnackbar("New password and confirmation do not match."))
            return
        }
        if (currentPassword == newPassword) {
            _uiEvent.tryEmit(UiEvent.ShowSnackbar("New password must be different from current password."))
            return
        }

        _isUpdatingPassword.value = true
        lookupTeacherDocument(teacherId) { teacherDocument, errorMessage ->
            if (teacherDocument == null) {
                _isUpdatingPassword.value = false
                _uiEvent.tryEmit(UiEvent.ShowSnackbar(errorMessage ?: "Teacher account not found."))
                return@lookupTeacherDocument
            }

            val storedHash = teacherDocument.getString("passwordHash")
            val currentHash = sha256(currentPassword.trim())
            if (storedHash.isNullOrBlank() || !storedHash.equals(currentHash, ignoreCase = true)) {
                _isUpdatingPassword.value = false
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("Current password is incorrect."))
                return@lookupTeacherDocument
            }

            teacherDocument.reference
                .update(
                    mapOf(
                        "passwordHash" to sha256(newPassword.trim()),
                        "updatedAt" to Timestamp.now()
                    )
                )
                .addOnSuccessListener {
                    _isUpdatingPassword.value = false
                    _uiEvent.tryEmit(UiEvent.ShowSnackbar("Password updated successfully."))
                    onSuccess()
                }
                .addOnFailureListener { error ->
                    _isUpdatingPassword.value = false
                    _uiEvent.tryEmit(UiEvent.ShowSnackbar("Failed to update password: ${error.message}"))
                }
        }
    }

    private fun lookupTeacherDocument(teacherId: String, onComplete: (DocumentSnapshot?, String?) -> Unit) {
        firestore.collection("users")
            .whereEqualTo("teacherId", teacherId)
            .limit(1)
            .get()
            .addOnSuccessListener { teacherSnapshot ->
                if (!teacherSnapshot.isEmpty) {
                    onComplete(teacherSnapshot.documents.firstOrNull(), null)
                    return@addOnSuccessListener
                }

                firestore.collection("users")
                    .whereEqualTo("instructorId", teacherId)
                    .limit(1)
                    .get()
                    .addOnSuccessListener { instructorSnapshot ->
                        onComplete(
                            instructorSnapshot.documents.firstOrNull(),
                            if (instructorSnapshot.isEmpty) "Teacher account not found." else null
                        )
                    }
                    .addOnFailureListener { error ->
                        onComplete(null, "Failed to verify teacher account: ${error.message}")
                    }
            }
            .addOnFailureListener { error ->
                onComplete(null, "Failed to verify teacher account: ${error.message}")
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

    sealed class UiEvent {
        data class ShowSnackbar(val message: String) : UiEvent()
    }
}
