package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.models.ModelCatalog
import com.chencohensolution.transcriptor.models.ModelChoices
import com.chencohensolution.transcriptor.models.ModelJob
import com.chencohensolution.transcriptor.models.SpeechModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelChoicesTest {
    private val hebrew = ModelCatalog.forLanguage("he", "SM8750")
    private fun ready(vararg models: SpeechModel): (SpeechModel) -> Boolean = { m -> models.any { it.id == m.id } }

    @Test
    fun improvingOffersWhisperOnlyTheLargeOnesFirst() {
        val ids = ModelChoices.candidates(hebrew, ModelJob.Improve, ready()).map { it.id }
        assertEquals(
            listOf(
                "ivrit-whisper-large-v3-npu-sm8750", "ivrit-whisper-large-v3", "whisper-large-v3",
                "ivrit-whisper-large-v3-turbo-npu-sm8750", "ivrit-whisper-large-v3-turbo", "whisper-large-v3-turbo",
            ),
            ids,
        )
    }

    @Test
    fun liveOffersTheFastModelsTheReadyOnesFirst() {
        val ids = ModelChoices.candidates(hebrew, ModelJob.Live, ready(ModelCatalog.ivritTurbo)).map { it.id }
        assertEquals(ModelCatalog.ivritTurbo.id, ids.first())
        assertEquals(hebrew.filter { it.fitsLive && it.kind.transcribes }.size, ids.size)
        assertEquals(true, ModelCatalog.ivritZipformer.id in ids)
    }

    @Test
    fun theChosenModelRunsWhenReadyElseTheFirstReady() {
        val ready = ready(ModelCatalog.ivritTurbo, ModelCatalog.ivritTurboNpu)
        val live = ModelChoices.candidates(hebrew, ModelJob.Live, ready)
        assertEquals(ModelCatalog.ivritTurbo, ModelChoices.running(live, ModelCatalog.ivritTurbo.id, ready))
        assertEquals(ModelCatalog.ivritTurboNpu, ModelChoices.running(live, ModelCatalog.ivritZipformer.id, ready))
        assertNull(ModelChoices.running(live, null, ready()))
    }

    @Test
    fun aModelToGetIsListedOnceOnALanguagesPage() {
        val toGet = ModelChoices.improveToGet(hebrew, ready()).map { it.id }
        assertEquals(listOf("ivrit-whisper-large-v3-npu-sm8750", "ivrit-whisper-large-v3", "whisper-large-v3"), toGet)
    }

    @Test
    fun theLargestModelIsSuggestedOnlyWhenNothingCanImprove() {
        assertEquals(ModelCatalog.ivritLargeV3Npu, ModelChoices.improveSuggestion(hebrew, ready()))
        assertNull(ModelChoices.improveSuggestion(hebrew, ready(ModelCatalog.ivritTurbo)))
    }

    @Test
    fun anEstimateNeedsTheModelsSpeed() {
        assertNull(ModelChoices.estimateMinutes(null, 3_000_000))
        assertEquals(20, ModelChoices.estimateMinutes(0.4, 3_000_000))
        assertEquals(1, ModelChoices.estimateMinutes(0.01, 60_000))
    }
}
