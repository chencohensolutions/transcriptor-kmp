package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.models.DefaultModels
import com.chencohensolution.transcriptor.models.ModelCatalog
import com.chencohensolution.transcriptor.models.ModelChoices
import com.chencohensolution.transcriptor.models.ModelJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultModelsTest {
    private val chips = listOf("SM8750", "SM8650", null)
    private val languages = listOf("en", "he", "fr")

    @Test
    fun theSnapdragon8EliteGetsTheNpuBuildsAndOtherPhonesTheCpuOnes() {
        val elite = DefaultModels.forLanguage("he", "SM8750")
        assertEquals(ModelCatalog.ivritTurboNpu.id, elite.live?.id)
        assertEquals(ModelCatalog.ivritLargeV3Npu.id, elite.improve?.id)
        val other = DefaultModels.forLanguage("he", "SM8650")
        assertEquals(ModelCatalog.ivritTurbo.id, other.live?.id)
        assertEquals(ModelCatalog.ivritLargeV3.id, other.improve?.id)
    }

    @Test
    fun everyDefaultIsInTheCatalogRunsOnThePhoneAndFitsItsJob() {
        for (chip in chips) for (language in languages) {
            val d = DefaultModels.forLanguage(language, chip)
            val live = requireNotNull(d.live) { "$language on $chip has no live default" }
            val improve = requireNotNull(d.improve) { "$language on $chip has no improve default" }
            assertTrue(live in ModelCatalog.forLanguage(language, chip))
            assertTrue(improve in ModelCatalog.forLanguage(language, chip))
            assertTrue(ModelChoices.fits(live, ModelJob.Live) && live.fitsLive)
            assertTrue(ModelChoices.fits(improve, ModelJob.Improve))
        }
    }

    @Test
    fun noDefaultIsNonCommercialOrCopiedByHand() {
        val nonCommercial = Regex("NC|noncommercial", RegexOption.IGNORE_CASE)
        for (chip in chips) for (language in languages) {
            for (m in DefaultModels.forLanguage(language, chip).toDownload) {
                assertTrue(!m.manual && m.files.all { it.url.isNotEmpty() }, "${m.id} is not downloadable")
                assertTrue(!nonCommercial.containsMatchIn(m.license), "${m.id} is ${m.license}")
            }
        }
    }

    @Test
    fun firstRunGetsTheSmallestFirst() {
        val sizes = DefaultModels.forLanguage(DefaultModels.FIRST_LANGUAGE, null).toDownload.map { it.sizeBytes }
        assertEquals(sizes.sorted(), sizes)
    }
}
