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
        val guard = NpuLoadGuard(installed) { 4_400_000_000 } // the S25 with about 4.4 GB free
        var loaded = false
        assertFailsWith<NpuLoadGuard.Refused> { guard.load(large) { loaded = true } }
        assertFalse(loaded)
        assertEquals(false, installed.npu(large.id)?.runs)
        assertTrue(installed.npu(large.id)!!.reason!!.startsWith("Not enough memory"))
        // Turbo (1.8 GB) fits.
        assertEquals("ok", guard.load(turbo) { "ok" })
        assertNull(installed.loading())
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
