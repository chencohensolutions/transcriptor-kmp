package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.models.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelCatalogTest {
    @Test
    fun hebrewGetsItsOwnModelsBeforeTheMultilingualOnes() {
        assertEquals(
            listOf("ivrit-whisper-large-v3-turbo", "ivrit-zipformer2-66m-streaming", "ivrit-whisper-large-v3", "whisper-large-v3-turbo", "whisper-large-v3"),
            ModelCatalog.forLanguage("he", chip = null).map { it.id },
        )
    }

    @Test
    fun otherLanguagesGetTheMultilingualModels() {
        assertEquals(listOf("whisper-large-v3-turbo", "whisper-large-v3"), ModelCatalog.forLanguage("en", chip = null).map { it.id })
    }

    @Test
    fun anNpuModelIsOfferedFirstOnItsChipOnly() {
        assertEquals("ivrit-whisper-large-v3-turbo-npu-sm8750", ModelCatalog.forLanguage("he", "SM8750").first().id)
        assertEquals(ModelCatalog.forLanguage("he", chip = null), ModelCatalog.forLanguage("he", "SM8650"))
    }
}
