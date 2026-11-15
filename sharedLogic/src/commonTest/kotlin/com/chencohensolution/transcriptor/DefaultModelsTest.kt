package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.models.DefaultModels
import com.chencohensolution.transcriptor.models.ModelCatalog
import com.chencohensolution.transcriptor.models.ModelChoices
import com.chencohensolution.transcriptor.models.ModelJob
import kotlin.test.Test
import okio.Path.Companion.toPath
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultModelsTest {
    private val chips = listOf("SM8750", "SM8650", null)
    private val languages = listOf("en", "he", "fr")

    @Test
    fun theSnapdragon8EliteGetsTheNpuBuildsAndOtherPhonesTheCpuOnes() {
        val elite = DefaultModels.forLanguage("he", "SM8750")
        assertEquals(ModelCatalog.ivritZipformerNpu.id, elite.live?.id)
        assertEquals(ModelCatalog.ivritLargeV3NpuEncoder.id, elite.improve?.id)
        assertEquals(ModelCatalog.voicePrintsResNet293.id, elite.voicePrints.id)
        val other = DefaultModels.forLanguage("he", "SM8650")
        assertEquals(ModelCatalog.ivritZipformer.id, other.live?.id)
        assertEquals(ModelCatalog.ivritLargeV3Q8.id, other.improve?.id)
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
    fun everyDefaultDownloadsAndTheSpeakerPassCanRun() {
        for (chip in chips) for (language in languages) {
            val d = DefaultModels.forLanguage(language, chip)
            for (m in d.toDownload) assertTrue(!m.manual && m.files.all { it.url.isNotEmpty() && it.sha256.isNotEmpty() }, "${m.id} is not downloadable")
            assertTrue(ModelCatalog.speakerSegmentation in d.toDownload)
        }
    }

    @Test
    fun speakerTurnsAreDiariZenOnTheS25AndPyannoteElsewhere() {
        assertEquals(ModelCatalog.diarizenLargeS80Npu.id, DefaultModels.forLanguage("en", "SM8750").segmentation.id)
        // Its NPU build doesn't load: the base build, then pyannote's.
        assertEquals(ModelCatalog.diarizenBaseS80Npu.id, DefaultModels.forLanguage("en", "SM8750") { it.id == ModelCatalog.diarizenLargeS80Npu.id }.segmentation.id)
        assertEquals(ModelCatalog.speakerSegmentation.id, DefaultModels.forLanguage("en", "SM8650").segmentation.id)
    }

    @Test
    fun firstRunGetsTheSmallestFirst() {
        val sizes = DefaultModels.forLanguage(DefaultModels.FIRST_LANGUAGE, null).toDownload.map { it.sizeBytes }
        assertEquals(sizes.sorted(), sizes)
    }

    @Test
    fun anNpuBuildThatDoesNotLoadGivesWayToTheCpuOne() {
        val d = DefaultModels.forLanguage("he", "SM8750", fails = { it.loadsOnNpu })
        assertEquals(ModelCatalog.ivritZipformer.id, d.live?.id)
    }

    @Test
    fun firstRunIsOfferedOnlyToAPhoneWithNothingToTranscribeWith() {
        assertTrue(DefaultModels.offerFirstRun(done = false, anyTranscriberReady = false))
        assertTrue(!DefaultModels.offerFirstRun(done = false, anyTranscriberReady = true))
        assertTrue(!DefaultModels.offerFirstRun(done = true, anyTranscriberReady = false))
    }

    @Test
    fun theDefaultsAreUsedOnlyWhereNothingWasChosen() {
        val fs = okio.fakefilesystem.FakeFileSystem()
        val settings = com.chencohensolution.transcriptor.settings.SettingsStore(fs, "/s".toPath())
        settings.setRefineModel("he", "mine")
        settings.useDefaults("he", DefaultModels.forLanguage("he", null))
        assertEquals(ModelCatalog.ivritZipformer.id, settings.liveModel("he"))
        assertEquals("mine", settings.refineModel("he"))
        assertEquals(ModelCatalog.speakerSegmentation.id, settings.segmentationModel)
        settings.segmentationModel = "picked"
        settings.useDefaults("he", DefaultModels.forLanguage("he", "SM8750"))
        assertEquals("picked", settings.segmentationModel)
        assertTrue(!settings.firstRunDone)
        settings.firstRunDone = true
        assertTrue(settings.firstRunDone)
    }

    @Test
    fun aLanguageIsRecommendedToGetOrCustom() {
        val he = DefaultModels.forLanguage("he", "SM8750")
        val live = he.live!!
        val improve = he.improve!!
        // Nothing on the phone: both recommended models are the ones to get.
        val fresh = DefaultModels.setup(he, null, null) { false }
        assertEquals(DefaultModels.Setup.ToGet, fresh.state)
        assertEquals(listOf(live.id, improve.id), fresh.toGet.map { it.id })
        assertEquals(live.sizeBytes + improve.sizeBytes, fresh.bytesToGet)
        // They are downloaded and are the ones that run.
        val done = DefaultModels.setup(he, live, improve) { true }
        assertEquals(DefaultModels.Setup.Recommended, done.state)
        assertEquals(emptyList(), done.toGet)
        // Another live model runs: the user's own choice, with only what is missing to get.
        val other = ModelCatalog.ivritTurbo
        val custom = DefaultModels.setup(he, other, improve) { it.id != live.id }
        assertEquals(DefaultModels.Setup.Custom, custom.state)
        assertEquals(listOf(live.id), custom.toGet.map { it.id })
        // Chosen but still downloading: not recommended yet.
        assertEquals(DefaultModels.Setup.Custom, DefaultModels.setup(he, other, null) { it.id == other.id }.state)
    }
}
