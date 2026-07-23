package com.pbec.preboardexamchecker.ui.records

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot bridge from the Students screen to Records: "show only these students".
 *
 * Passed out-of-band (not as a nav argument) so opening Records is a normal bottom-nav tab switch.
 * A nav arg would push Records onto the Students back stack, and the tab's saveState/restoreState
 * would then resurrect Records whenever the user re-taps Students.
 */
@Singleton
class StudentRecordsRequest @Inject constructor() {
    private val _ids = MutableStateFlow<List<String>?>(null)
    val ids: StateFlow<List<String>?> = _ids.asStateFlow()

    fun request(studentIds: List<String>) { _ids.value = studentIds }
    fun consume() { _ids.value = null }
}
