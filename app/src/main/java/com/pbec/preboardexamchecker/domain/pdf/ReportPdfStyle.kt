package com.pbec.preboardexamchecker.domain.pdf

import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.colors.DeviceRgb
import com.pbec.preboardexamchecker.domain.model.SummaryStatus
import com.pbec.preboardexamchecker.ui.scanner.scoring.ScoringConfig

/** Shared labels/colors so the class report and the student slip render consistently. */
internal object ReportPdfStyle {

    /** Subjects in their canonical GWA order (Mathematics, ESAS, Professional EE). */
    val subjectOrder: List<String> = ScoringConfig.SUBJECT_WEIGHTS.keys.toList()

    /** Short column header for a subject, e.g. "Mathematics" -> "Math". */
    fun shortLabel(subject: String): String = when (subject) {
        "Mathematics" -> "Math"
        "ESAS" -> "ESAS"
        "Professional EE" -> "EEPS"
        else -> subject
    }

    fun statusText(status: SummaryStatus): String = when (status) {
        SummaryStatus.PASS -> "PASS"
        SummaryStatus.FAIL -> "FAIL"
        SummaryStatus.INCOMPLETE -> "INCOMPLETE"
    }

    fun statusColor(status: SummaryStatus): DeviceRgb = when (status) {
        SummaryStatus.PASS -> DeviceRgb(27, 94, 32)       // green 900
        SummaryStatus.FAIL -> DeviceRgb(183, 28, 28)      // red 900
        SummaryStatus.INCOMPLETE -> DeviceRgb(120, 120, 120)
    }

    val headerFill = DeviceRgb(0, 102, 139)               // app seed color #00668B
    val headerText = ColorConstants.WHITE
    val belowFloorText = DeviceRgb(183, 28, 28)
}
