package com.pbec.preboardexamchecker.domain.model

import com.pbec.preboardexamchecker.domain.usecase.GwaResult

/**
 * Final remark for a student's overall result.
 *
 *  - [PASS]       — all 3 subjects scanned, every subject ≥ 50%, and GWA ≥ 70%.
 *  - [FAIL]       — all 3 subjects scanned but a subject is below 50% or GWA is below 70%.
 *  - [INCOMPLETE] — the student is missing at least one of the 3 subject scans.
 *
 * (No "conditioned" state — by design.)
 */
enum class SummaryStatus { PASS, FAIL, INCOMPLETE }

/** Derives the [SummaryStatus] from a computed [GwaResult]. */
fun GwaResult.toSummaryStatus(): SummaryStatus = when {
    !complete -> SummaryStatus.INCOMPLETE
    passed -> SummaryStatus.PASS
    else -> SummaryStatus.FAIL
}
