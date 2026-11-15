package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.models.ModelCatalog
import com.chencohensolution.transcriptor.models.ModelChoices
import com.chencohensolution.transcriptor.models.ModelRemoval
import com.chencohensolution.transcriptor.settings.SettingsStore
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelRemovalTest {
    // An added speaker-turn model, as Hugging Face's DiariZen builds install.
    private val added = ModelCatalog.diarizenLargeS80.copy(id = "hf:hencohen83/diarizen-wavlm-large-s80-qnn-sm8750:model + NPU", manual = false)

    @Test
    fun anAddedModelIsRemovedABuiltInOneLosesOnlyItsDownload() {
        assertEquals(ModelRemoval.Remove, ModelChoices.removal(added, downloaded = true))
        assertEquals(ModelRemoval.Remove, ModelChoices.removal(added, downloaded = false))
        assertEquals(ModelRemoval.DeleteDownload, ModelChoices.removal(ModelCatalog.ivritTurbo, downloaded = true))
        assertEquals(ModelRemoval.DeleteDownload, ModelChoices.removal(ModelCatalog.speakerSegmentation, downloaded = true))
        // Nothing to free: not downloaded, or copied by hand (no download to get it back).
        assertNull(ModelChoices.removal(ModelCatalog.ivritTurbo, downloaded = false))
        assertNull(ModelChoices.removal(ModelCatalog.diarizenLargeS80, downloaded = true))
    }

    @Test
    fun aRemovedModelIsForgottenWhereverItWasChosen() {
        val settings = SettingsStore(FakeFileSystem(), "/s".toPath())
        settings.setLiveModel("he", added.id)
        settings.setLiveModel("en", ModelCatalog.ivritTurbo.id)
        settings.setRefineModel("he", added.id)
        settings.segmentationModel = added.id
        settings.voiceModel = ModelCatalog.voicePrints.id
        settings.forgetModel(added.id)
        // Its jobs go back to their defaults (pyannote for turns, the first ready model live); the others stay.
        assertNull(settings.liveModel("he"))
        assertNull(settings.refineModel("he"))
        assertNull(settings.segmentationModel)
        assertEquals(ModelCatalog.ivritTurbo.id, settings.liveModel("en"))
        assertEquals(ModelCatalog.voicePrints.id, settings.voiceModel)
    }
}
