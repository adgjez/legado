package io.legado.app.model.localBook.epubcore.translate

import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.model.localBook.epubcore.layout.EpubCorePage
import io.legado.app.model.localBook.epubcore.model.ReaderModel

class EpubNativeTranslationPipeline(
    private val collector: EpubComputedDomCollector = EpubComputedDomCollector(),
    private val classifier: EpubBlockClassifier = EpubBlockClassifier()
) {

    fun translate(model: ReaderModel): EpubTranslationResult {
        return classifier.classify(collector.collect(model))
    }

    fun layout(
        model: ReaderModel,
        config: EpubCoreLayoutConfig
    ): EpubNativeLaidDocument {
        val translated = translate(model).document
        return EpubNativeTextLayouter(config).layout(translated)
    }

    fun paginate(
        model: ReaderModel,
        config: EpubCoreLayoutConfig
    ): List<EpubCorePage> {
        val laidDocument = layout(model, config)
        return EpubNativePaginator(config).paginate(laidDocument)
    }
}
