package io.legado.app.model.localBook.epubcore.style

data class EpubStyleValue(
    val value: String,
    val important: Boolean,
    val sourceRank: Int,
    val specificity: Int,
    val ruleOrder: Int,
    val declarationOrder: Int
) {
    fun hasHigherPriorityThan(other: EpubStyleValue): Boolean {
        return compareValuesBy(
            this,
            other,
            EpubStyleValue::sourceRank,
            EpubStyleValue::specificity,
            EpubStyleValue::ruleOrder,
            EpubStyleValue::declarationOrder
        ) > 0
    }
}
