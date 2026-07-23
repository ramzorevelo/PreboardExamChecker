package com.pbec.preboardexamchecker.utils

import java.util.UUID

/**
 * Random 63-bit positive Long ids.
 *
 * Ids must be Long (Room/Firestore field contracts) and non-negative (fallback paths assume it).
 * Firestore keys question docs by id.toString(), so a collision overwrites another document.
 * Nothing orders by id — repositories sort by createdAt — so non-monotonic values are fine.
 */
object IdGenerator {
    fun newId(): Long = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
}
