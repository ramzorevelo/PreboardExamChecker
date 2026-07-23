package com.pbec.preboardexamchecker.ui.viewmodels

import android.content.Context
import android.net.Uri
import android.util.Log // Import Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pbec.preboardexamchecker.data.models.Exam
import com.pbec.preboardexamchecker.data.models.Question
import com.pbec.preboardexamchecker.data.repository.ExamRepository
import com.pbec.preboardexamchecker.data.repository.QuestionRepository
import com.pbec.preboardexamchecker.utils.PdfExportUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExamContentViewModel @Inject constructor(
    private val examRepository: ExamRepository,
    private val questionRepository: QuestionRepository,
    private val pdfExportUtil: PdfExportUtil,
    @ApplicationContext private val applicationContext: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val examId: Long = savedStateHandle.get<String>("examId")?.toLongOrNull() ?: -1L

    /** Which set this screen shows ("A" or "B"), taken from the navigation route. */
    val set: String = savedStateHandle.get<String>("set")?.uppercase() ?: "A"

    private val _exam = MutableStateFlow<Exam?>(null)
    val exam: StateFlow<Exam?> = _exam.asStateFlow()

    // FIX: Corrected type from List<List<Question>> to List<Question>
    private val _questionsInSet = MutableStateFlow<List<Question>>(emptyList())
    // FIX: Corrected type from List<List<Question>> to List<Question>
    val questionsInSet: StateFlow<List<Question>> = _questionsInSet.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // Cached so we can build either set's ordered question list without re-querying.
    private var allSubjectQuestions: List<Question> = emptyList()

    init {
        loadExamContent()
    }

    private fun loadExamContent() {
        viewModelScope.launch {
            try {
                val currentExam = examRepository.getExamById(examId)
                _exam.value = currentExam

                currentExam?.let { exam ->
                    // Show the questions for THIS set, in the set's stored (printed) order.
                    val questionIdsToLoad = if (set == "B") exam.setBQuestionIds else exam.setAQuestionIds

                    if (questionIdsToLoad.isEmpty()) {
                        _message.value = "No questions found in Set $set of this exam."
                        return@launch
                    }

                    allSubjectQuestions = questionRepository.getAllQuestionsForSubjectOnce(exam.subject)

                    // FIX: Ensure the assigned value matches the StateFlow type
                    _questionsInSet.value = orderedQuestions(questionIdsToLoad)
                }
            } catch (e: Exception) {
                _message.value = "Error loading exam content: ${e.message}"
                e.printStackTrace()
            }
        }
    }

    /** Builds the question list for [ids] in the given order, skipping any missing ids. */
    private fun orderedQuestions(ids: List<Long>): List<Question> =
        ids.mapNotNull { id -> allSubjectQuestions.firstOrNull { it.id == id } }

    /**
     * Exports the chosen set ("A" or "B") to PDF in its own shuffled order.
     * The PDF converter (pdfExportUtil) is used as-is.
     */
    fun exportSetToPdf(set: String, outputUri: Uri) {
        viewModelScope.launch {
            val currentExam = _exam.value
            if (currentExam == null) {
                _message.value = "No exam content to export."
                return@launch
            }

            val ids = if (set.equals("B", ignoreCase = true))
                currentExam.setBQuestionIds
            else
                currentExam.setAQuestionIds

            val questions = orderedQuestions(ids)
            if (questions.isEmpty()) {
                _message.value = "Set $set has no questions to export."
                return@launch
            }

            _message.value = "Exporting PDF... Please wait."
            try {
                pdfExportUtil.exportExamToPdf(
                    outputUri,
                    "${currentExam.examName} - Set $set",
                    currentExam.subject,
                    questions
                )
                _message.value = "PDF exported successfully!"
            } catch (e: Exception) {
                _message.value = "Error exporting PDF: ${e.message}"
                Log.e("ExamContentViewModel", "Error exporting PDF", e)
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}