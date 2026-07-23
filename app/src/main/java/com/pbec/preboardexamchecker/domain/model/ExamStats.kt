package com.pbec.preboardexamchecker.domain.model

/** One bar of the score-distribution histogram (e.g. "70–79%" -> 12 students). */
data class ScoreBucket(val label: String, val count: Int)

/** One answer choice of a question, with how many takers picked it. */
data class QuestionOption(
    val letter: Char,           // 'A'..'D'
    val text: String,
    val chosenCount: Int,
    val isCorrect: Boolean,
)

/** Item-analysis stats for one question, aggregated across all takers (both sets, by question id). */
data class QuestionStat(
    val number: Int,            // canonical position (1-based, Set A order)
    val questionId: Long,
    val questionText: String,
    val keyLetter: Char?,       // correct answer 'A'..'E', or null if the key is missing
    val options: List<QuestionOption>,
    val correctCount: Int,
    val answeredCount: Int,     // takers who marked exactly one bubble (A–E)
    val totalTakers: Int,
    val blankCount: Int,        // takers who left it blank ('-')
    val multiCount: Int,        // takers who marked multiple ('?')
) {
    /** Takers who did NOT get it right (incorrect + blank + multi). */
    val wrongCount: Int get() = totalTakers - correctCount

    /** % correct out of all takers (blank/invalid count against the question). */
    val correctPct: Double get() = if (totalTakers > 0) correctCount * 100.0 / totalTakers else 0.0
}

/** Full statistics for one exam. */
data class ExamStats(
    val examId: Long,
    val examName: String,
    val subject: String,
    val takers: Int,
    val avgPct: Double,
    val medianPct: Double,
    val minPct: Double,
    val maxPct: Double,
    val passRate: Double,       // % of takers whose subject result passed the 50% floor
    val distribution: List<ScoreBucket>,
    val questions: List<QuestionStat>,
) {
    /** True when at least one per-question answer was recorded (older scans may lack rawAnswers). */
    val perQuestionAvailable: Boolean get() = questions.any { it.answeredCount > 0 }
}
