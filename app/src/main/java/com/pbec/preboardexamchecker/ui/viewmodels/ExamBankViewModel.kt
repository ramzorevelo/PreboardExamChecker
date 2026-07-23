package com.pbec.preboardexamchecker.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pbec.preboardexamchecker.data.models.Question
import com.pbec.preboardexamchecker.data.repository.ExamRepository
import com.pbec.preboardexamchecker.data.repository.QuestionRepository
import com.pbec.preboardexamchecker.data.repository.TransactionLogRepository
import com.pbec.preboardexamchecker.utils.ExcelParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.OutputStream
import javax.inject.Inject
import kotlinx.coroutines.flow.stateIn
import com.pbec.preboardexamchecker.data.models.ValidationResult
import java.util.Locale
import java.util.UUID

@HiltViewModel
class ExamBankViewModel @Inject constructor(
    private val questionRepository: QuestionRepository,
    private val examRepository: ExamRepository,
    private val transactionLogRepository: TransactionLogRepository,
    private val excelParser: ExcelParser,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private fun isManualBankId(bankId: String): Boolean {
        return bankId == "manual" || bankId.startsWith("manual_")
    }

    val subject: String = savedStateHandle.get<String>("subject") ?: "Unknown"

    private val _totalQuestionCount = MutableStateFlow(0)
    val totalQuestionCount: StateFlow<Int> = _totalQuestionCount.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _pendingSaveQuestion = MutableStateFlow<Question?>(null)
    val pendingSaveQuestion: StateFlow<Question?> = _pendingSaveQuestion.asStateFlow()

    private val _nextQuestionToEdit = MutableStateFlow<Long?>(null)
    val nextQuestionToEdit: StateFlow<Long?> = _nextQuestionToEdit.asStateFlow()

    private val _manualQuestionCount = MutableStateFlow(0)
    val manualQuestionCount: StateFlow<Int> = _manualQuestionCount.asStateFlow()

    val questionsByImportSession: StateFlow<Map<String, List<Question>>> =
        questionRepository.getQuestionsBySubject(subject)
            .map { questions ->
                _manualQuestionCount.value = questions.count { isManualBankId(it.questionBankId) }
                questions.groupBy { it.questionBankId }
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    val generatedExamCountsByImportSession: StateFlow<Map<String, Int>> =
        combine(
            questionRepository.getQuestionsBySubject(subject),
            examRepository.getExamsBySubject(subject)
        ) { questions, exams ->
            val questionIdsBySession = questions
                .groupBy { it.questionBankId }
                .mapValues { (_, groupedQuestions) -> groupedQuestions.map { it.id }.toSet() }

            questionIdsBySession.mapValues { (_, sessionQuestionIds) ->
                exams.count { exam ->
                    val examQuestionIds = exam.questionIds.toSet()
                    examQuestionIds.any(sessionQuestionIds::contains)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    init {
        viewModelScope.launch {
            questionRepository.getQuestionsBySubject(subject).collect { questions ->
                _totalQuestionCount.value = questions.size
            }
        }
    }

    fun importQuestionsFromFile(uri: Uri, subject: String, fileName: String) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val importSessionId = System.currentTimeMillis()
                val questionBankId = "bank_${UUID.randomUUID()}"
                val extension = fileName.substringAfterLast(".").lowercase(Locale.ROOT)
                
                val parsedQuestions = if (extension == "csv") {
                    excelParser.readQuestionsFromCsv(context, uri, subject, fileName)
                } else {
                    excelParser.readQuestionsFromExcel(context, uri, subject, fileName)
                }

                if (parsedQuestions.isNotEmpty()) {
                    val questionsToInsert = parsedQuestions.map { question ->
                        question.copy(
                            // Question docs are keyed by id.toString(): a collision overwrites another question.
                            id = com.pbec.preboardexamchecker.utils.IdGenerator.newId(),
                            questionBankId = questionBankId,
                            importSessionId = importSessionId
                        )
                    }
                    questionRepository.insertQuestions(questionsToInsert)
                    logTransaction(
                        action = "IMPORT_QUESTION_BANK",
                        details = "Imported ${questionsToInsert.size} questions from '$fileName' (sessionId=$importSessionId)."
                    )
                    _message.value = "Successfully imported ${questionsToInsert.size} questions from $fileName."
                } else {
                    logTransaction(
                        action = "IMPORT_QUESTION_BANK_EMPTY",
                        details = "Import file '$fileName' had no valid questions."
                    )
                    _message.value = "No valid questions found in $fileName."
                }
            } catch (e: Exception) {
                logTransaction(
                    action = "IMPORT_QUESTION_BANK_FAILED",
                    details = "Failed to import '$fileName': ${e.message}"
                )
                _message.value = "Error importing file: ${e.message}"
                e.printStackTrace()
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun exportExamTemplate(uri: Uri) {
        viewModelScope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    excelParser.writeExcelTemplate(outputStream)
                    _message.value = "Exam template exported successfully!"
                } ?: run {
                    _message.value = "Failed to open output stream for template."
                }
            } catch (e: Exception) {
                _message.value = "Error exporting template: ${e.message}"
                e.printStackTrace()
            }
        }
    }

    fun setPendingSaveQuestion(question: Question?) {
        _pendingSaveQuestion.value = question
    }

    fun saveQuestion(question: Question) {
        viewModelScope.launch {
            val validationResult = validateQuestion(question)
            if (!validationResult.isValid) {
                _message.value = validationResult.errorMessage
                return@launch
            }
            try {
                val isNewQuestion = question.id == 0L
                questionRepository.insertQuestions(listOf(question))
                logTransaction(
                    action = if (isNewQuestion) "ADD_QUESTION" else "UPDATE_QUESTION",
                    details = if (isNewQuestion) {
                        "Added question #${question.questionNumber} (${question.fileName})."
                    } else {
                        "Updated question #${question.questionNumber} (${question.fileName})."
                    }
                )
                _message.value = if (isNewQuestion) "Question added successfully." else "Changes saved for question ${question.questionNumber}."
                _pendingSaveQuestion.value = null
            } catch (e: Exception) {
                _message.value = "Error saving changes: ${e.message}"
                e.printStackTrace()
            }
        }
    }

    fun discardPendingQuestionChanges() {
        _pendingSaveQuestion.value = null
        _message.value = "Changes discarded."
    }

    fun deleteQuestion(question: Question) {
        if (!isManualBankId(question.questionBankId)) {
            deleteQuestionsForImportSession(question.questionBankId)
            return
        }
        viewModelScope.launch {
            _isDeleting.value = true
            try {
                val deletedRows = questionRepository.deleteQuestion(question)
                if (deletedRows > 0) {
                    logTransaction(
                        action = "DELETE_QUESTION",
                        details = "Deleted question #${question.questionNumber} from '${question.fileName}'."
                    )
                    _message.value = "Question ${question.questionNumber} deleted."
                } else {
                    _message.value = "Failed to delete question ${question.questionNumber}."
                }
            } catch (e: Exception) {
                _message.value = "Error deleting question: ${e.message}"
                e.printStackTrace()
            } finally {
                _isDeleting.value = false
            }
        }
    }

    fun deleteQuestionsForImportSession(questionBankId: String) {
        viewModelScope.launch {
            _isDeleting.value = true
            try {
                val questionsInSession = questionsByImportSession.value[questionBankId].orEmpty()
                val questionIdsInSession = questionsInSession.map { it.id }.toSet()
                // Capture all of the subject's questions BEFORE deleting this bank, so the archived
                // snapshots stay complete even for exams that drew questions from several banks.
                val subjectQuestionsBeforeDelete = questionRepository.getAllQuestionsForSubjectOnce(subject)
                val deletedExams = examRepository.deleteExamsLinkedToQuestionBank(
                    subject = subject,
                    questionBankId = questionBankId,
                    questionIdsInBank = questionIdsInSession
                )
                // Archive each removed exam so its Exam Stats survive the bank deletion.
                deletedExams.forEach { exam ->
                    runCatching { examRepository.archiveExam(exam, subjectQuestionsBeforeDelete) }
                }
                val deletedExamCount = deletedExams.size
                // Soft-delete the bank itself (questions are kept so the bank can be restored intact).
                questionRepository.softDeleteQuestionBank(questionBankId)
                val questionCount = questionsInSession.size

                logTransaction(
                    action = "TRASH_QUESTION_BANK",
                    details = "Moved bank=$questionBankId ($questionCount questions) and $deletedExamCount generated exam(s) to Trash."
                )
                _message.value = if (deletedExamCount > 0) {
                    "Moved bank to Trash ($questionCount questions, $deletedExamCount exam(s)). Restore within 30 days."
                } else {
                    "Moved bank to Trash ($questionCount questions). Restore within 30 days."
                }
            } catch (e: Exception) {
                _message.value = "Error deleting session: ${e.message}"
                e.printStackTrace()
            } finally {
                _isDeleting.value = false
            }
        }
    }

    fun renameImportSession(questionBankId: String, newName: String) {
        viewModelScope.launch {
            try {
                val updatedRows = questionRepository.updateCustomSessionNameByQuestionBankId(questionBankId, newName)
                if (updatedRows > 0) {
                    logTransaction(
                        action = "RENAME_QUESTION_BANK",
                        details = "Renamed question bank $questionBankId to '$newName'."
                    )
                    _message.value = "Session renamed successfully."
                } else {
                    _message.value = "Failed to rename session."
                }
            } catch (e: Exception) {
                _message.value = "Error renaming session: ${e.message}"
                e.printStackTrace()
            }
        }
    }

    fun setNextQuestionToEdit(id: Long?) {
        _nextQuestionToEdit.value = id
    }

    fun clearNextQuestionToEdit() {
        _nextQuestionToEdit.value = null
    }

    fun clearMessage() {
        _message.value = null
    }

    fun setMessage(message: String?) {
        _message.value = message
    }

    fun validateQuestion(question: Question): ValidationResult {
        if (question.questionText.isBlank()) return ValidationResult(false, "Question text cannot be blank.")
        if (question.optionA.isBlank()) return ValidationResult(false, "Option A cannot be blank.")
        if (question.optionB.isBlank()) return ValidationResult(false, "Option B cannot be blank.")
        if (question.optionC.isBlank()) return ValidationResult(false, "Option C cannot be blank.")
        if (question.optionD.isBlank()) return ValidationResult(false, "Option D cannot be blank.")
        if (question.correctAnswer.isNullOrBlank()) return ValidationResult(false, "Correct answer cannot be blank.")
        if (question.correctAnswer !in listOf("A", "B", "C", "D", "E")) return ValidationResult(false, "Correct answer must be A, B, C, D, or E.")
        return ValidationResult(true, null)
    }

    private suspend fun logTransaction(action: String, details: String) {
        runCatching {
            transactionLogRepository.insertTransaction(
                action = action,
                subject = subject,
                details = details
            )
        }
    }
}
