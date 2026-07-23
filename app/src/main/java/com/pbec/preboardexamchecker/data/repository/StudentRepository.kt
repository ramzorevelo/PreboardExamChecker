package com.pbec.preboardexamchecker.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.pbec.preboardexamchecker.data.models.Student
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StudentRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) : IStudentRepository {
    override suspend fun getAllStudents(): List<Student> {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return emptyList()
        return try {
            firestore.collection("students")
                .whereEqualTo("uploadedByUid", uid)
                .get()
                .await()
                .toObjects(Student::class.java)
                .filter { it.deletedAt == null }  // trashed rosters must not resolve while scanning
        } catch (e: Exception) {
            emptyList()
        }
    }
}
