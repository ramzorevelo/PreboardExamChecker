package com.pbec.preboardexamchecker.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pbec.preboardexamchecker.data.models.Exam
import com.pbec.preboardexamchecker.data.models.Question
import com.pbec.preboardexamchecker.data.repository.ExamRepository
import com.pbec.preboardexamchecker.data.repository.IScanResultRepository
import com.pbec.preboardexamchecker.data.repository.QuestionRepository
import com.pbec.preboardexamchecker.utils.ExcelParser
import com.pbec.preboardexamchecker.utils.ExamBlueprint
import com.pbec.preboardexamchecker.utils.ExamBlueprints
import com.pbec.preboardexamchecker.utils.PdfExportUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.random.Random

@HiltViewModel
class ExamViewModel @Inject constructor(
    private val examRepository: ExamRepository,
    private val questionRepository: QuestionRepository,
    private val scanResultRepository: IScanResultRepository,
    private val excelParser: ExcelParser,
    private val pdfExportUtil: PdfExportUtil,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    val subject: String = savedStateHandle.get<String>("subject") ?: "Unknown"

    private val _exams = MutableStateFlow<List<Exam>>(emptyList())
    val exams: StateFlow<List<Exam>> = _exams.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            examRepository.getExamsBySubject(subject).collect {
                _exams.value = it
            }
        }
    }

    fun generateExam(numQuestions: Int, selectedQuestionBankIds: List<String>) {
        viewModelScope.launch {
            try {
                val allQuestions = if (selectedQuestionBankIds.isEmpty()) {
                    emptyList()
                } else {
                    questionRepository.getQuestionsByQuestionBankIdsOnly(selectedQuestionBankIds)
                }
                
                if (allQuestions.isEmpty()) {
                    _message.value = "No questions available in the selected question banks."
                    return@launch
                }

                val blueprint = ExamBlueprints.getBlueprint(subject)
                val useBlueprint = blueprint != null && numQuestions == 100

                var selectedQuestions = if (useBlueprint) {
                    generateFromBlueprint(allQuestions, blueprint!!)
                } else {
                    emptyList()
                }

                var usedRandomFallback = false
                if (selectedQuestions.isEmpty() && numQuestions > 0) {
                    selectedQuestions = allQuestions.shuffled().take(minOf(numQuestions, allQuestions.size))
                    usedRandomFallback = useBlueprint
                } else if (useBlueprint && selectedQuestions.size < 100) {
                    val missing = 100 - selectedQuestions.size
                    val remainingPool = allQuestions.filter { q -> selectedQuestions.none { it.id == q.id } }
                    selectedQuestions = selectedQuestions + remainingPool.shuffled().take(minOf(missing, remainingPool.size))
                    usedRandomFallback = true
                }

                val examName = "Exam ${exams.value.size + 1} (${subject})"
                // Set A and Set B contain the SAME questions in two different orders.
                // Two distinct seeds guarantee the orders differ; persisting the resulting
                // id lists is what fixes the printed order — no seed is stored.
                val baseSeed = System.nanoTime()
                val setAQuestionIds = selectedQuestions.shuffled(Random(baseSeed)).map { it.id }
                val setBQuestionIds = selectedQuestions.shuffled(Random(baseSeed xor 0x5DEECE66DL)).map { it.id }
                val newExam = Exam(
                    id = com.pbec.preboardexamchecker.utils.IdGenerator.newId(),
                    examName = examName,
                    subject = subject,
                    setAQuestionIds = setAQuestionIds,
                    setBQuestionIds = setBQuestionIds,
                    createdAt = Date().time
                )

                examRepository.insertExam(
                    exam = newExam,
                    selectedQuestionBankIds = selectedQuestionBankIds,
                    generatedQuestionCount = selectedQuestions.size,
                    usedBlueprint = useBlueprint,
                    usedRandomFallback = usedRandomFallback
                )
                
                val sourceInfo = if (useBlueprint && !usedRandomFallback) {
                    "using the official blueprint"
                } else if (useBlueprint && usedRandomFallback) {
                    "with partial blueprint matching (some questions were added randomly from the selected banks)"
                } else {
                    "randomly from the selected question banks"
                }
                
                _message.value = "Exam '$examName' generated successfully with ${selectedQuestions.size} questions $sourceInfo!"
            } catch (e: Exception) {
                _message.value = "Error: ${e.message}"
            }
        }
    }

    private fun generateFromBlueprint(allQuestions: List<Question>, blueprint: ExamBlueprint): List<Question> {
        val selected = mutableListOf<Question>()
        val pool = allQuestions.toMutableList()

        for (spec in blueprint.specs) {
            val topicQuestions = pool.filter { question ->
                val qTopic = question.topic?.trim()?.lowercase(Locale.ROOT) ?: ""
                val specTopic = spec.topic.lowercase(Locale.ROOT)
                
                // Aggressive Matching:
                // 1. Check if the question topic contains important keywords from the spec title or its aliases
                val specKeywords = specTopic.split(" ", ",", "&").filter { it.length > 3 }
                val aliasKeywords = spec.aliases.flatMap { it.lowercase(Locale.ROOT).split(" ", ",", "&") }.filter { it.length > 3 }
                val allKeywords = (specKeywords + aliasKeywords).distinct()
                
                val isMatch = qTopic == specTopic || 
                             qTopic.contains(specTopic) || 
                             specTopic.contains(qTopic) ||
                             spec.aliases.any { qTopic.contains(it.lowercase(Locale.ROOT)) } ||
                             (allKeywords.isNotEmpty() && allKeywords.any { qTopic.contains(it) })
                
                isMatch
            }

            if (topicQuestions.isEmpty()) continue

            // Logic to pick from topicQuestions
            val objectives = topicQuestions.filter { it.category?.trim()?.contains("Objective", true) == true }.shuffled().toMutableList()
            val computations = topicQuestions.filter { it.category?.trim()?.contains("Computation", true) == true }.shuffled().toMutableList()

            val chosenObjs = objectives.take(spec.numObjective)
            objectives.removeAll(chosenObjs)
            selected.addAll(chosenObjs)

            val chosenComps = computations.take(spec.numComputation)
            computations.removeAll(chosenComps)
            selected.addAll(chosenComps)

            // Fill remaining needed for this topic from whatever is left in THIS topic's pool
            val currentTopicCount = selected.count { q -> topicQuestions.any { it.id == q.id } }
            val stillNeeded = spec.total - currentTopicCount
            if (stillNeeded > 0) {
                val remainingInTopic = (objectives + computations).shuffled()
                selected.addAll(remainingInTopic.take(stillNeeded))
            }
            
            pool.removeAll { q -> selected.any { it.id == q.id } }
        }
        
        return selected
    }

    /** Delete the exam definition only. Existing scan records are kept (they remain visible in
     *  the Summary screen and stay linked to this exam by its unique id). */
    fun deleteExam(exam: Exam) {
        viewModelScope.launch {
            try {
                archiveExamForStats(exam)
                examRepository.deleteExam(exam)
            } catch (e: Exception) {
                _message.value = "Error deleting exam: ${e.message}"
            }
        }
    }

    /** Delete the exam definition AND move its scan records to the Trash (restorable for 30 days). */
    fun deleteExamWithRecords(exam: Exam) {
        viewModelScope.launch {
            try {
                archiveExamForStats(exam)
                examRepository.deleteExam(exam)
                scanResultRepository.moveExamRecordsToTrashCascade(exam.id)
            } catch (e: Exception) {
                _message.value = "Error deleting exam: ${e.message}"
            }
        }
    }

    /** Snapshot the exam + its referenced questions so Exam Stats can still be rebuilt after the
     *  exam (and possibly its question bank) is gone. Best-effort: never blocks the deletion. */
    private suspend fun archiveExamForStats(exam: Exam) {
        runCatching {
            val subjectQuestions = questionRepository.getAllQuestionsForSubjectOnce(exam.subject)
            examRepository.archiveExam(exam, subjectQuestions)
        }
    }

    fun renameExam(exam: Exam, newName: String) {
        if (newName.isBlank()) {
            _message.value = "Exam name cannot be empty."
            return
        }
        viewModelScope.launch {
            try {
                val updatedExam = exam.copy(examName = newName)
                examRepository.updateExam(updatedExam)
            } catch (e: Exception) {
                _message.value = "Error renaming exam: ${e.message}"
            }
        }
    }

    /**
     * Exports a single set ("A" or "B") of [exam] to PDF in that set's stored (printed) order.
     * Reuses [PdfExportUtil] as-is. Used by the per-set export buttons in the exam list.
     */
    fun exportSet(exam: Exam, set: String, outputUri: Uri) {
        viewModelScope.launch {
            try {
                val ids = if (set.equals("B", ignoreCase = true)) exam.setBQuestionIds else exam.setAQuestionIds
                if (ids.isEmpty()) {
                    _message.value = "Set $set has no questions to export."
                    return@launch
                }
                val allSubjectQuestions = questionRepository.getAllQuestionsForSubjectOnce(exam.subject)
                val questions = ids.mapNotNull { id -> allSubjectQuestions.firstOrNull { it.id == id } }
                if (questions.isEmpty()) {
                    _message.value = "Set $set has no questions to export."
                    return@launch
                }
                _message.value = "Exporting PDF... Please wait."
                pdfExportUtil.exportExamToPdf(outputUri, "${exam.examName} - Set $set", exam.subject, questions)
                _message.value = "PDF exported successfully!"
            } catch (e: Exception) {
                _message.value = "Error exporting PDF: ${e.message}"
            }
        }
    }

    fun importExcelFile(uri: Uri, selectedSubject: String, fileName: String) {
        viewModelScope.launch {
            try {
                val questions = excelParser.readQuestionsFromExcel(context, uri, selectedSubject, fileName)
                if (questions.isNotEmpty()) {
                    val importSessionId = System.currentTimeMillis()
                    val questionBankId = "bank_${UUID.randomUUID()}"
                    val questionsToInsert = questions.map {
                        it.copy(
                            // Question docs are keyed by id.toString(): a collision overwrites another question.
                            id = com.pbec.preboardexamchecker.utils.IdGenerator.newId(),
                            questionBankId = questionBankId,
                            importSessionId = importSessionId
                        )
                    }
                    questionRepository.insertQuestions(questionsToInsert)
                    _message.value = "Successfully imported ${questions.size} questions."
                } else {
                    _message.value = "No valid questions found."
                }
            } catch (e: Exception) {
                _message.value = "Error: ${e.message}"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
