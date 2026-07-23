package com.pbec.preboardexamchecker.domain.model

data class ScanResultFilter(
    val subject: String? = null,
    val examId: Long? = null,
    val block: String? = null,
    val yearLevel: String? = null,
    val program: String? = null,
    val dateStart: Long? = null,
    val dateEnd: Long? = null,
    val passedOnly: Boolean? = null,
    val sortBy: SortField = SortField.DATE_DESC,
)

enum class SortField {
    DATE_DESC, DATE_ASC,
    NAME_ASC, NAME_DESC,
    SCORE_DESC, SCORE_ASC,
    GWA_DESC, GWA_ASC,
    BLOCK_ASC
}
