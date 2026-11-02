package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.models.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelCatalogTest {
    @Test
    fun hebrewGetsItsOwnModelsBeforeTheMultilingualOnes() {
        assertEquals(
            listOf("ivrit-whisper-large-v3-turbo", "ivrit-whisper-large-v3", "whisper-large-v3-turbo", "whisper-large-v3"),
            ModelCatalog.forLanguage("he").map { it.id },
        )
    }

    @Test
    fun otherLanguagesGetTheMultilingualModels() {
        assertEquals(listOf("whisper-large-v3-turbo", "whisper-large-v3"), ModelCatalog.forLanguage("en").map { it.id })
    }
}
