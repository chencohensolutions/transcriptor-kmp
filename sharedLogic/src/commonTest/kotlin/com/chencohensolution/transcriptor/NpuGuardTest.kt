package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.models.InstalledModels
import com.chencohensolution.transcriptor.models.ModelCatalog
import com.chencohensolution.transcriptor.models.ModelLibrary
import com.chencohensolution.transcriptor.models.NpuLoadGuard
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NpuGuardTest {
    private val fs = FakeFileSystem()
    private val installed = InstalledModels(fs, "/m".toPath())
    private val large = ModelCatalog.ivritLargeV3Npu
    private val turbo = ModelCatalog.ivritTurboNpu

    @Test
    fun aModelTooBigForFreeMemoryIsRefusedAndDisabled() {
        val guard = NpuLoadGuard(installed) { 6_100_000_000 } // the S25 with about 6.1 GB free
        var loaded = false
        // Large-v3 16-bit: 3.3 GB of context binaries, about 8.7 GB to load.
        assertFailsWith<NpuLoadGuard.Refused> { guard.load(large) { loaded = true } }
        assertFalse(loaded)
        assertEquals(false, installed.npu(large.id)?.runs)
        assertTrue(installed.npu(large.id)!!.reason!!.startsWith("Not enough memory"))
        // Turbo (1.8 GB of binaries, about 5 GB to load) fits.
        assertEquals("ok", guard.load(turbo) { "ok" })
        assertNull(installed.loading())
    }

    @Test
    fun theEstimateIsTwoAndAHalfTimesTheContextBinariesAndAMeasuredPeakOverridesIt() {
        val mb = 1024L * 1024
        // What was measured: the 8-bit large-v3 pair, 1.95 GB, took the S25 from 6.1 to 1.1 GB free.
        val pair = turbo.copy(id = "large-8bit", files = listOf(
            turbo.files[0].copy(name = "encoder.bin", sizeBytes = 1_000_000_000),
            turbo.files[1].copy(name = "decoder.bin", sizeBytes = 950_000_000),
            turbo.files[2],
        ))
        assertEquals(1_950_000_000, pair.npuBinaryBytes) // tokens.txt not counted
        assertEquals(4_875_000_000 + NpuLoadGuard.HEADROOM, NpuLoadGuard.needs(pair))
        assertTrue(NpuLoadGuard.needs(pair) > 5_000_000_000, "covers the measured 5 GB peak")
        // A measured peak, from the catalog or the model's card, replaces the estimate.
        val measured = pair.copy(npuPeakBytes = 5_000 * mb)
        assertEquals(5_000 * mb + NpuLoadGuard.HEADROOM, NpuLoadGuard.needs(measured))
        assertFailsWith<NpuLoadGuard.Refused> { NpuLoadGuard(installed) { 5_000 * mb }.load(measured) { } }
        // A ggml Whisper model's model.bin isn't an NPU binary.
        assertEquals(0, ModelCatalog.all.first { it.kind == com.chencohensolution.transcriptor.models.ModelKind.Whisper }.npuBinaryBytes)
    }

    @Test
    fun theSpeakerModelsNpuBuildsPassEasilyOnTheS25() {
        val gb = 1_000_000_000L
        for (m in listOf(ModelCatalog.voicePrintsResNet293, ModelCatalog.voicePrintsEres2NetV2, ModelCatalog.ivritZipformerNpu)) {
            assertTrue(NpuLoadGuard.needs(m) < 1 * gb, "${m.id}: ${NpuLoadGuard.needs(m)}")
        }
        // ResNet100 (3 x 106 MB) and DiariZen large (about 300 MB window.bin), as added by hand or from Hugging Face.
        val r100 = ModelCatalog.voicePrintsResNet293.copy(id = "r100", files = List(3) { ModelCatalog.voicePrintsResNet293.files[1].copy(name = "t${150 shl it}.bin", sizeBytes = 106_000_000) })
        val dzLarge = ModelCatalog.voicePrintsResNet293.copy(id = "dz", files = listOf(ModelCatalog.voicePrintsResNet293.files[1].copy(name = "window.bin", sizeBytes = 300_000_000)))
        assertTrue(NpuLoadGuard.needs(r100) < 2 * gb)
        assertTrue(NpuLoadGuard.needs(dzLarge) < 2 * gb)
        val guard = NpuLoadGuard(installed) { 4_400_000_000 }
        for (m in listOf(ModelCatalog.voicePrintsResNet293, ModelCatalog.voicePrintsEres2NetV2, r100, dzLarge)) assertEquals("ok", guard.load(m) { "ok" })
    }

    @Test
    fun aMeasuredPeakIsKeptWithAModelAddedFromHuggingFace() {
        val model = turbo.copy(id = "hf:x/whisper-qnn-sm8750:model", npuPeakBytes = 4_000_000_000)
        installed.add(com.chencohensolution.transcriptor.models.InstalledModel(model, "x/whisper-qnn-sm8750", com.chencohensolution.transcriptor.models.LicenseUse.Commercial))
        assertEquals(4_000_000_000, InstalledModels(fs, "/m".toPath()).all().single { it.model.id == model.id }.model.npuPeakBytes)
    }

    @Test
    fun aLoadTheAppDidNotSurviveDisablesTheModelAtTheNextStart() {
        val guard = NpuLoadGuard(installed) { 16_000_000_000 }
        // The load runs and Android kills the app inside it: the mark stays.
        installed.setLoading(large.id)
        val next = NpuLoadGuard(InstalledModels(fs, "/m".toPath())) { 16_000_000_000 }
        assertEquals(large.id, next.recover())
        assertEquals(NpuLoadGuard.KILLED, installed.npu(large.id)?.reason)
        assertNull(installed.loading())
        assertNull(next.recover())
        // Disabled: left out of what can run, and not loaded again on its own.
        assertTrue(ModelLibrary(installed).forLanguage("he", "SM8750").none { it.id == large.id })
        assertFailsWith<NpuLoadGuard.Refused> { guard.load(large) { } }
        // Until the user asks to try again.
        guard.reenable(large.id)
        assertTrue(ModelLibrary(installed).forLanguage("he", "SM8750").any { it.id == large.id })
    }

    @Test
    fun aLoadThatThrowsClearsTheMark() {
        val guard = NpuLoadGuard(installed) { 16_000_000_000 }
        assertFailsWith<IllegalStateException> { guard.load(turbo) { error("QNN error") } }
        assertNull(installed.loading())
    }
}
