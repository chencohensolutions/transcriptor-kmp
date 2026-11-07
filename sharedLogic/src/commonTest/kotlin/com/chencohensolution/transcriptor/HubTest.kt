package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.models.speakerNpuFiles
import com.chencohensolution.transcriptor.models.HubClient
import com.chencohensolution.transcriptor.models.HubKind
import com.chencohensolution.transcriptor.models.InstalledModel
import com.chencohensolution.transcriptor.models.InstalledModels
import com.chencohensolution.transcriptor.models.LicenseUse
import com.chencohensolution.transcriptor.models.ModelKind
import com.chencohensolution.transcriptor.models.ModelLibrary
import com.chencohensolution.transcriptor.models.NpuResult
import com.chencohensolution.transcriptor.models.SpeechModel
import com.chencohensolution.transcriptor.models.classify
import com.chencohensolution.transcriptor.models.encode
import com.chencohensolution.transcriptor.models.fromHub
import com.chencohensolution.transcriptor.models.hubModel
import com.chencohensolution.transcriptor.models.languagesOf
import com.chencohensolution.transcriptor.models.licenseUse
import com.chencohensolution.transcriptor.models.npuOf
import com.chencohensolution.transcriptor.models.parseRepo
import com.chencohensolution.transcriptor.models.parseSearch
import com.chencohensolution.transcriptor.settings.SettingsStore
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hugging Face's API, as its responses look (trimmed): the search, a repo read whole, and what the app makes of them. */
class HubTest {
    private val cppCommit = "5359861c739e955e79d9a303bcbc70fb988958b1"

    private fun lfs(name: String, size: Long, sha: String) =
        """{"rfilename":"$name","blobId":"b-$name","size":$size,"lfs":{"sha256":"$sha","size":$size,"pointerSize":134}}"""

    private fun small(name: String, size: Long) = """{"rfilename":"$name","blobId":"git-$name","size":$size}"""

    private fun summary(id: String, files: List<String>, tags: List<String>, likes: Int = 10, sha: String = "c0") =
        """{"_id":"x","id":"$id","author":"${id.substringBefore('/')}","sha":"$sha","downloads":0,"likes":$likes,
           "tags":[${tags.joinToString { "\"$it\"" }}],"siblings":[${files.joinToString { """{"rfilename":"$it"}""" }}]}"""

    private val cppFiles = listOf(".gitattributes", "README.md", "ggml-base-encoder.mlmodelc.zip", "ggml-base.en.bin", "ggml-small-q5_1.bin", "ggml-large-v3.bin", "ggml-large-v3-turbo-q5_0.bin", "ggml-silero-v5.1.2.bin")
    private val cppSearch = summary("ggerganov/whisper.cpp", cppFiles, listOf("automatic-speech-recognition", "license:mit", "region:us"), likes = 1678, sha = cppCommit)
    private val cppRepo = """{"id":"ggerganov/whisper.cpp","sha":"$cppCommit","cardData":{"license":"mit"},"tags":["license:mit"],"siblings":[
        ${small(".gitattributes", 1500)}, ${small("README.md", 3196)}, ${lfs("ggml-base-encoder.mlmodelc.zip", 37_000_000, "e1")},
        ${lfs("ggml-base.en.bin", 147_964_211, "a1")}, ${lfs("ggml-small-q5_1.bin", 190_085_487, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb")},
        ${lfs("ggml-large-v3.bin", 3_095_033_483, "a3")}, ${lfs("ggml-large-v3-turbo-q5_0.bin", 574_041_195, "a4")}, ${lfs("ggml-silero-v5.1.2.bin", 885_000, "a5")}]}"""

    private val zipFiles = listOf("README.md", "decoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "encoder-epoch-99-avg-1.int8.onnx", "encoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx", "joiner-epoch-99-avg-1.onnx", "tokens.txt", "test_wavs/0.wav")
    private val zipId = "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-02-21"
    private val zipSearch = summary(zipId, zipFiles, listOf("license:apache-2.0"), likes = 12, sha = "z1")
    private val zipRepo = """{"id":"$zipId","sha":"z1","cardData":{"license":"apache-2.0"},"siblings":[
        ${small("README.md", 900)}, ${lfs("decoder-epoch-99-avg-1.int8.onnx", 1_049_000, "d8")}, ${lfs("decoder-epoch-99-avg-1.onnx", 2_000_000, "d32")},
        ${lfs("encoder-epoch-99-avg-1.int8.onnx", 68_700_000, "e8")}, ${lfs("encoder-epoch-99-avg-1.onnx", 250_000_000, "e32")},
        ${lfs("joiner-epoch-99-avg-1.int8.onnx", 771_000, "j8")}, ${lfs("joiner-epoch-99-avg-1.onnx", 2_500_000, "j32")}, ${small("tokens.txt", 5_000)}]}"""

    private val ncnnSearch = summary("csukuangfj/sherpa-ncnn-streaming-zipformer-en-2023-02-13", listOf("encoder_jit_trace-pnnx.ncnn.bin", "encoder_jit_trace-pnnx.ncnn.param"), listOf("license:apache-2.0"))
    private val torchSearch = summary("openai/whisper-large-v3", listOf("config.json", "model.safetensors"), listOf("en", "he", "license:apache-2.0"))

    private val npuId = "someone/ivrit-whisper-large-v3-turbo-qnn2.40-sm8750"
    private val npuSearch = summary(npuId, listOf("README.md", "encoder.bin", "decoder.bin", "tokens.txt"), listOf("he", "license:apache-2.0", "qnn"), sha = "n1")
    private val npuRepo = """{"id":"$npuId","sha":"n1","cardData":{"license":"apache-2.0","language":["he"]},"siblings":[
        ${lfs("encoder.bin", 1_346_412_544, "b9a0")}, ${lfs("decoder.bin", 452_820_992, "914f")}, ${lfs("tokens.txt", 816_730, "b34b")}]}"""

    @Test
    fun whisperCppFilesBecomeSizesPinnedToTheCommit() {
        val m = assertNotNull(hubModel(parseSearch("[$cppSearch]").single(), parseRepo(cppRepo)))
        assertEquals(HubKind.Whisper, m.kind)
        assertEquals(LicenseUse.Commercial, m.use)
        assertEquals(listOf(SpeechModel.MULTILINGUAL), m.languages)
        assertEquals(listOf("base.en", "small-q5_1", "large-v3-turbo-q5_0", "large-v3"), m.variants.map { it.label })
        val small = m.variants.first { it.label == "small-q5_1" }
        val file = small.files.single()
        assertEquals("model.bin", file.name)
        assertEquals("https://huggingface.co/ggerganov/whisper.cpp/resolve/$cppCommit/ggml-small-q5_1.bin", file.url)
        assertEquals("ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", file.sha256)
        assertNull(small.quantizeTo)
        assertEquals("244M", small.parameters)
        assertEquals("q8_0", m.variants.first { it.label == "large-v3" }.quantizeTo)
        assertEquals(false, m.variants.first { it.label == "large-v3" }.fitsLive)
        assertEquals(true, m.variants.first { it.label == "large-v3-turbo-q5_0" }.fitsLive)
        assertEquals(listOf("en"), m.variants.first { it.label == "base.en" }.languages)
        val installed = m.toSpeechModel(small)
        assertEquals("hf:ggerganov/whisper.cpp:small-q5_1", installed.id)
        assertEquals(ModelKind.Whisper, installed.kind)
        assertTrue(installed.fromHub)
    }

    @Test
    fun aStreamingRepoGivesItsInt8AndFullFilesUnderTheEnginesNames() {
        val m = assertNotNull(hubModel(parseSearch("[$zipSearch]").single(), parseRepo(zipRepo)))
        assertEquals(HubKind.Streaming, m.kind)
        assertEquals(listOf("en"), m.languages)
        assertEquals(listOf("int8", "fp32"), m.variants.map { it.label })
        val int8 = m.variants.first()
        assertEquals(listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"), int8.files.map { it.name })
        assertTrue(int8.files.first().url.endsWith("/resolve/z1/encoder-epoch-99-avg-1.int8.onnx"))
        val tokens = int8.files.last()
        assertEquals("", tokens.sha256)
        assertEquals("git-tokens.txt", tokens.gitSha1)
        assertEquals("e32", m.variants[1].files.first().sha256)
        assertEquals(ModelKind.StreamingTransducer, m.toSpeechModel(int8).kind)
    }

    @Test
    fun reposTheEnginesCantReadAreLeftOut() {
        assertNull(classify("csukuangfj/sherpa-ncnn-streaming-zipformer-en-2023-02-13", listOf("encoder_jit_trace-pnnx.ncnn.bin"), emptyList()))
        assertNull(classify("openai/whisper-large-v3", listOf("model.safetensors", "config.json"), emptyList()))
        // Found live: a language model and Parakeet's GGUF, both with ggml-*.bin files.
        assertNull(classify("hlhr202/llama-7B-ggml-int4", listOf("ggml-model-q4_0.bin"), listOf("text-generation")))
        assertNull(classify("ggml-org/parakeet-GGUF", listOf("ggml-parakeet-tdt-0.6b-v3.bin"), listOf("license:mit")))
        assertEquals(HubKind.Whisper, classify("petredimov/ggml-large-v3-q8", listOf("ggml-large-v3-q8_0.bin"), listOf("automatic-speech-recognition", "whisper")))
        assertNull(classify("csukuangfj/sherpa-onnx-zipformer-en-2023-04-01", listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"), emptyList()))
        assertEquals(HubKind.Parakeet, classify("csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", listOf("encoder.int8.onnx", "tokens.txt"), emptyList()))
        assertEquals(HubKind.SenseVoice, classify("csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17", listOf("model.int8.onnx", "tokens.txt"), emptyList()))
    }

    @Test
    fun anNpuBuildIsKnownByItsBinariesAndTheChipInItsName() {
        val m = assertNotNull(hubModel(parseSearch("[$npuSearch]").single(), parseRepo(npuRepo)))
        assertEquals("SM8750", m.npu?.chip)
        assertEquals("2.40", m.npu?.runtime)
        assertEquals(listOf("he"), m.languages)
        assertEquals(listOf("encoder.bin", "decoder.bin", "tokens.txt"), m.variants.single().files.map { it.name })
        assertTrue(m.fits("SM8750") && !m.fits("SM8650") && !m.fits(null))
        val installed = m.toSpeechModel(m.variants.single())
        assertEquals(ModelKind.WhisperNpu, installed.kind)
        assertEquals(listOf("SM8750"), installed.chips)
        assertNull(npuOf("x/zipformer-qnn-sm8650", listOf("encoder.onnx", "encoder.bin"), emptyList()))
        assertNull(npuOf("x/zipformer-qnn-sm8650", listOf("encoder.bin"), emptyList())?.runtime)
        assertEquals("2.31", npuOf("x/whisper-small", listOf("encoder.bin", "decoder.bin"), listOf("qairt-2.31", "sm8750"))?.runtime)
        // No measured peak on its card: estimated.
        assertNull(installed.npuPeakBytes)
    }

    @Test
    fun anNpuBuildsCardCanStateItsMeasuredPeakMemory() {
        val withPeak = npuRepo.replace(""""language":["he"]}""", """"language":["he"],"npu_peak_memory_mb":5000}""")
        val m = assertNotNull(hubModel(parseSearch("[$npuSearch]").single(), parseRepo(withPeak)))
        assertEquals(5000L * 1024 * 1024, m.npuPeakBytes)
        assertEquals(5000L * 1024 * 1024, m.toSpeechModel(m.variants.single()).npuPeakBytes)
        // A CPU model's card can't set one.
        val cpu = cppRepo.replace(""""cardData":{""", """"cardData":{"npu_peak_memory_mb":900,""")
        val c = assertNotNull(hubModel(parseSearch("[$cppSearch]").single(), parseRepo(cpu)))
        assertNull(c.toSpeechModel(c.variants.first()).npuPeakBytes)
    }

    @Test
    fun licensesAreForCommercialUseNotOrUnknown() {
        assertEquals(LicenseUse.Commercial, licenseUse("apache-2.0"))
        assertEquals(LicenseUse.Commercial, licenseUse("cc-by-4.0"))
        assertEquals(LicenseUse.Commercial, licenseUse("gpl-3.0"))
        assertEquals(LicenseUse.NonCommercial, licenseUse("cc-by-nc-4.0"))
        assertEquals(LicenseUse.NonCommercial, licenseUse("cc-by-nc-sa-4.0"))
        assertEquals(LicenseUse.Unknown, licenseUse("other"))
        assertEquals(LicenseUse.Unknown, licenseUse(null))
    }

    @Test
    fun languagesComeFromTheCardTagsOrName() {
        assertEquals(listOf("he"), languagesOf("ivrit-ai/zipformer2-66m-streaming-he", HubKind.Streaming, emptyList()))
        assertEquals(listOf("zh", "en"), languagesOf("csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20", HubKind.Streaming, emptyList()))
        assertEquals(listOf("he"), languagesOf("x/y", HubKind.Whisper, listOf("iw", "license:mit")))
        assertEquals(listOf(SpeechModel.MULTILINGUAL), languagesOf("x/y", HubKind.Whisper, (com.chencohensolution.transcriptor.models.Languages.whisper.take(20))))
        assertEquals(emptyList(), languagesOf("x/zipformer-model", HubKind.Streaming, emptyList()))
    }

    @Test
    fun theSearchReadsWhatMayFitFiltersItAndCountsTheRest() = runTest {
        val searchBody = "[$cppSearch, $zipSearch, $ncnnSearch, $torchSearch, $npuSearch]"
        val asked = mutableListOf<String>()
        val hub = HubClient { url ->
            asked += url
            when {
                "/models?search=" in url -> searchBody
                url.endsWith("/ggerganov/whisper.cpp/revision/main?blobs=true") -> cppRepo
                url.endsWith("/$zipId/revision/main?blobs=true") -> zipRepo
                url.endsWith("/$npuId/revision/main?blobs=true") -> npuRepo
                else -> error("not asked for: $url")
            }
        }
        val (all, unusable) = hub.search("whisper hebrew", null, null, npuOnly = false, chip = "SM8750")
        assertEquals(listOf("ggerganov/whisper.cpp", zipId, npuId), all.map { it.repo })
        assertEquals(2, unusable)
        assertTrue(asked.first().contains("search=whisper%20hebrew"))

        assertEquals(listOf("ggerganov/whisper.cpp", npuId), hub.search("x", "he", null, false, "SM8750").models.map { it.repo })
        assertEquals(listOf(zipId), hub.search("x", null, HubKind.Streaming, false, "SM8750").models.map { it.repo })
        assertEquals(listOf(npuId), hub.search("x", null, null, npuOnly = true, chip = "SM8750").models.map { it.repo })
        assertEquals(emptyList(), hub.search("x", null, null, npuOnly = true, chip = "SM8650").models)
    }

    @Test
    fun aQueryIsEncodedForTheUrl() {
        assertEquals("whisper%20hebrew", encode("whisper hebrew"))
        assertEquals("%D7%A2%D7%91", encode("עב"))
        assertEquals("a-b_c.d~", encode("a-b_c.d~"))
    }

    @Test
    fun addedModelsJoinTheLibraryAndAFailedNpuCheckTakesOneOut() {
        val fs = FakeFileSystem()
        val installed = InstalledModels(fs, "/files".toPath())
        val library = ModelLibrary(installed)
        val m = assertNotNull(hubModel(parseSearch("[$cppSearch]").single(), parseRepo(cppRepo)))
        val small = m.toSpeechModel(m.variants.first { it.label == "small-q5_1" })
        installed.add(InstalledModel(small, m.repo, m.use))

        assertEquals(small, InstalledModels(fs, "/files".toPath()).all().single().model)
        assertTrue(small in library.forLanguage("fr", null))
        assertEquals("whisper.cpp · small-q5_1", library.nameOf(small.id))

        val npu = assertNotNull(hubModel(parseSearch("[$npuSearch]").single(), parseRepo(npuRepo)))
        val npuModel = npu.toSpeechModel(npu.variants.single())
        installed.add(InstalledModel(npuModel, npu.repo, npu.use))
        assertTrue(npuModel in library.forLanguage("he", "SM8750"))
        assertTrue(npuModel !in library.forLanguage("he", "SM8650"))
        installed.setNpu(npuModel.id, NpuResult(runs = false, reason = "refused"))
        assertTrue(npuModel !in library.forLanguage("he", "SM8750"))
        assertEquals("refused", library.npu(npuModel.id)?.reason)

        installed.remove(small.id)
        assertTrue(small !in library.forLanguage("fr", null))
    }

    @Test
    fun theRefineModelAndTheAddedLanguagesAreKept() {
        val fs = FakeFileSystem()
        val settings = SettingsStore(fs, "/files".toPath())
        assertNull(settings.refineModel("he"))
        settings.setRefineModel("he", "ivrit-whisper-large-v3")
        settings.modelLanguages = listOf("fr", "de", "fr")
        val again = SettingsStore(fs, "/files".toPath())
        assertEquals("ivrit-whisper-large-v3", again.refineModel("he"))
        assertEquals(listOf("fr", "de"), again.modelLanguages)
    }

    private val segId = "csukuangfj/sherpa-onnx-pyannote-segmentation-3-0"
    private val segSearch = summary(segId, listOf("README.md", "model.onnx", "model.int8.onnx", "export-onnx.py"), listOf("license:mit"), sha = "s1")
    private val segRepo = """{"id":"$segId","sha":"s1","cardData":{"license":"mit"},"siblings":[
        ${small("README.md", 400)}, ${lfs("model.onnx", 5_992_913, "220a")}, ${lfs("model.int8.onnx", 1_500_000, "15aa")}, ${small("export-onnx.py", 2_000)}]}"""

    private val printsId = "csukuangfj/speaker-embedding-models"
    private val printsRepo = """{"id":"$printsId","sha":"p1","cardData":{"license":"apache-2.0"},"siblings":[
        ${lfs("3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx", 28_281_164, "aa3c")}, ${lfs("wespeaker_en_voxceleb_resnet34_LM.onnx", 26_500_000, "w34")},
        ${small("README.md", 300)}]}"""

    @Test
    fun speakerModelsAreClassifiedByTheirNamesAndFiles() {
        assertEquals(HubKind.SpeakerTurns, classify(segId, listOf("model.onnx", "model.int8.onnx"), emptyList()))
        assertEquals(HubKind.SpeakerTurns, classify("csukuangfj/sherpa-onnx-reverb-diarization-v1", listOf("model.onnx"), emptyList()))
        assertEquals(HubKind.VoicePrints, classify(printsId, listOf("wespeaker_en_voxceleb_resnet34_LM.onnx"), emptyList()))
        assertEquals(HubKind.VoicePrints, classify("k2-fsa/sherpa-onnx-nemo-titanet-small", listOf("nemo_en_titanet_small.onnx"), emptyList()))
        // Not a sherpa-onnx export: its ONNX lacks the metadata sherpa-onnx reads.
        assertNull(classify("Wespeaker/wespeaker-voxceleb-resnet34-LM", listOf("voxceleb_resnet34_LM.onnx", "avg_model.pt"), emptyList()))
        // PyTorch only.
        assertNull(classify("pyannote/segmentation-3.0", listOf("pytorch_model.bin", "config.yaml"), emptyList()))
        assertNull(classify("BUT-FIT/diarizen-wavlm-large-s80-md", listOf("pytorch_model.bin"), emptyList()))
        // A speech model by the same author is still one.
        assertEquals(HubKind.Streaming, classify(zipId, zipFiles, emptyList()))
    }

    @Test
    fun aSpeakerRepoInstallsEachOnnxAsASpeakerModel() {
        val seg = assertNotNull(hubModel(parseSearch("[$segSearch]").single(), parseRepo(segRepo)))
        assertEquals(HubKind.SpeakerTurns, seg.kind)
        assertEquals(LicenseUse.Commercial, seg.use)
        assertEquals(listOf("model.int8", "model"), seg.variants.map { it.label })
        val model = seg.toSpeechModel(seg.variants.last())
        assertEquals(ModelKind.SpeakerSegmentation, model.kind)
        assertEquals(listOf(SpeechModel.MULTILINGUAL), model.languages)
        val file = model.files.single()
        assertEquals("model.onnx", file.name)
        assertEquals("https://huggingface.co/$segId/resolve/s1/model.onnx", file.url)
        assertEquals("220a", file.sha256)

        val prints = assertNotNull(hubModel(parseSearch("[${summary(printsId, emptyList(), emptyList(), sha = "p1")}]").single(), parseRepo(printsRepo)))
        assertEquals(HubKind.VoicePrints, prints.kind)
        assertEquals(2, prints.variants.size)
        assertTrue(prints.variants.all { v -> prints.toSpeechModel(v).kind == ModelKind.SpeakerEmbedding && v.files.single().name == "model.onnx" })
        assertEquals("hf:$printsId:wespeaker_en_voxceleb_resnet34_LM", prints.toSpeechModel(prints.variants.first()).id)
    }

    @Test
    fun aBlankSpeakerSearchLooksForSpeakerRepos() {
        assertTrue("wespeaker" in HubClient.defaultTerms(HubKind.VoicePrints))
        assertTrue("segmentation" in HubClient.defaultTerms(HubKind.SpeakerTurns))
        assertEquals(listOf("ggml", "sherpa-onnx", "qnn"), HubClient.defaultTerms(null))
        assertTrue(HubKind.speech.none { it.speakers })
    }

    @Test
    fun speakerNpuBuildsTaggedSherpaOnnxAreFoundWithTheirNpuFiles() {
        val dz = listOf("README.md", "model.onnx", "window.bin")
        val dzTags = listOf("speaker-diarization", "diarizen", "sherpa-onnx", "qnn", "license:cc-by-nc-4.0")
        assertEquals(HubKind.SpeakerTurns, classify("hencohen83/diarizen-wavlm-base-s80-qnn-sm8750", dz, dzTags))
        assertEquals(listOf("window.bin"), speakerNpuFiles("hencohen83/diarizen-wavlm-base-s80-qnn-sm8750", dz, dzTags))
        val rn = listOf("model.onnx", "t150.bin", "t300.bin", "t600.bin")
        val rnTags = listOf("speaker-embedding", "wespeaker", "sherpa-onnx", "qnn")
        assertEquals(HubKind.VoicePrints, classify("hencohen83/wespeaker-resnet293-lm-qnn-sm8750", rn, rnTags))
        assertEquals(listOf("t150.bin", "t300.bin", "t600.bin"), speakerNpuFiles("hencohen83/wespeaker-resnet293-lm-qnn-sm8750", rn, rnTags))
        // Without sherpa-onnx in its name, author or tags it isn't one sherpa-onnx can read.
        assertNull(classify("someone/diarizen-wavlm-base-s80", listOf("model.onnx"), listOf("speaker-diarization")))
        // An ONNX speaker model without an NPU build brings no .bin.
        assertEquals(emptyList(), speakerNpuFiles("csukuangfj/sherpa-onnx-pyannote-segmentation-3-0", listOf("model.onnx", "x.bin"), emptyList()))
    }

    @Test
    fun aSearchListsWhatItCantRunWithWhy() {
        assertEquals("MLX (Apple) format", HubClient.whyNot("mlx-community/wespeaker-voxceleb-resnet34-LM", listOf("model.safetensors"), emptyList()))
        assertEquals("ONNX without sherpa-onnx's model information", HubClient.whyNot("Wespeaker/wespeaker-voxceleb-resnet34", listOf("voxceleb_resnet34.onnx", "README.md"), emptyList()))
        assertEquals("PyTorch / safetensors only", HubClient.whyNot("x/y", listOf("pytorch_model.bin", "config.json"), emptyList()))
        assertEquals("No model files", HubClient.whyNot("x/z", listOf("README.md", ".gitattributes"), emptyList()))
    }
}
