package com.pbec.preboardexamchecker.data.models

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp

data class Student(
    @DocumentId
    val id: String = "",
    val name: String = "",
    val studentId: String = "",
    val program: String = "",
    val yearLevel: String = "",
    val block: String = "",
    // Roster-only fields. block is looked up by scanned Student ID (never on the answer sheet);
    // schoolYear distinguishes rosters imported across batches and is a Students/Records filter.
    val gender: String = "",
    // Optional contact email, used to send the student their result slip directly.
    val email: String = "",
    val schoolYear: String = "",
    val instructor: String = "",
    // Owner uid; the scanner's StudentRepository filters by it, so imports/adds must set it.
    val uploadedByUid: String = "",
    // Import batch: lets the user delete a whole roster at once; importLabel is a friendly group name.
    val importId: Long = 0,
    val importLabel: String = "",
    // Soft-delete (30-day trash), mirroring papers/exams. null = active.
    val deletedAt: Long? = null,
    // Set to the importId when trashed as a whole import; null when deleted individually. Splits the
    // Trash > Rosters tab into "Imports" vs "Individual students".
    val deletedBatch: Long? = null,
    @ServerTimestamp
    val createdAt: Timestamp? = null
)
