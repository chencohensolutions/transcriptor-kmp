package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.recording.AacEncoders
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohensolution.transcriptor.models.HubSearch
import com.chencohensolution.transcriptor.models.InstalledModel
import com.chencohensolution.transcriptor.models.ModelKind
import com.chencohensolution.transcriptor.speech.ModelStatus
import com.chencohensolution.transcriptor.speech.SherpaNpuWhisperEngine
import com.chencohensolution.transcriptor.ui.ModelsActions
import com.chencohensolution.transcriptor.ui.ModelsScreen
import com.chencohensolution.transcriptor.ui.ModelsState
import com.chencohensolution.transcriptor.ui.recordingChoice
import com.chencohensolution.transcriptor.ui.NpuCheck
import com.chencohensolution.transcriptor.ui.speakerModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings (its languages' pages) and Manage models, fed from [Recording]: the models' downloads (read every second while it is open),
 * the settings, the languages in use, the Hugging Face search, models added and removed, and each NPU model's
 * one-time check once it is downloaded.
 */
@Composable
fun Settings(onBack: () -> Unit, chooseFolder: () -> Unit) {
    var serverOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (serverOpen) {
        androidx.activity.compose.BackHandler { serverOpen = false }
        ServerSettings(onBack = { serverOpen = false })
        return
    }
    val server by Recording.backup.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val library by Recording.library.library.collectAsStateWithLifecycle()
    val folder by Recording.folder.collectAsStateWithLifecycle()
    var changed by remember { mutableStateOf(0) }
    // The recording profile shown on Recording settings (the active one at first), and the one moments record with.
    val store = Recording.settings
    var active by remember { mutableStateOf(store.activeProfile) }
    var shown by remember { mutableStateOf(active) }
    var format by remember { mutableStateOf(store.recordingChoice(shown).audioFormat) }
    var flacLevel by remember { mutableStateOf(Recording.settings.flacLevel) }
    var aac by remember { mutableStateOf(Recording.settings.aac) }
    var flac by remember { mutableStateOf(Recording.settings.flac) }
    var encodeAfter by remember { mutableStateOf(Recording.settings.encodeAfter) }
    var speakers by remember { mutableStateOf(Recording.settings.findSpeakersOnStop) }
    var auto by remember { mutableStateOf(Recording.settings.autoImprove) }
    var transcribeWhen by remember { mutableStateOf(Recording.settings.transcribeWhen) }
    var quiet by remember { mutableStateOf(Recording.settings.quietRecording) }
    var stopTwice by remember { mutableStateOf(Recording.settings.stopTwice) }
    var advancedMode by remember { mutableStateOf(Recording.settings.advancedMode) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var meters by remember { mutableStateOf(Recording.settings.processorMeters) }
    var levelMeter by remember { mutableStateOf(Recording.settings.levelMeter) }
    var micInput by remember { mutableStateOf(store.profile(shown).micInput) }
    var profileSettings by remember { mutableStateOf(store.profile(shown)) }
    /** Reads [shown]'s settings (and the timing) back after a change: the store is the truth. */
    fun load() {
        val p = store.profile(shown)
        val choice = store.recordingChoice(shown)
        format = choice.audioFormat; aac = p.aac; flac = p.flac; flacLevel = p.flacLevel; micInput = p.micInput; profileSettings = p
        encodeAfter = choice.encodeAfter; transcribeWhen = choice.transcribeWhen; active = store.activeProfile
    }
    /** Changes [shown]'s settings and reads them back. */
    fun edit(change: (com.chencohensolution.transcriptor.settings.ProfileSettings) -> com.chencohensolution.transcriptor.settings.ProfileSettings) {
        store.setProfile(shown, change(store.profile(shown)))
        load()
    }
    var micInputs by remember { mutableStateOf(Recording.audioInputs.connected()) }
    // Settings › Microphone's test: listens only while that page is open, never while a moment records.
    val probe = remember { com.chencohensolution.transcriptor.recording.MicProbe(Recording.audioInputs) }
    val micMeter by probe.meter.collectAsStateWithLifecycle()
    var micTestOff by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { probe.stop() } }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val stop = Recording.audioInputs.watch { micInputs = it }
        onDispose { stop() }
    }
    var added by remember { mutableStateOf(Recording.settings.modelLanguages) }
    var hub by remember { mutableStateOf(HubSearch()) }
    var checking by remember { mutableStateOf(emptySet<String>()) }
    val chip = SherpaNpuWhisperEngine.chip
    val models = remember(changed) { Recording.modelLibrary.all }
    // The catalog's speaker models and those added from Hugging Face.
    val speakerList = remember(models) { (speakerModels + models.filter { !it.kind.transcribes }).distinctBy { it.id } }
    val statuses by produceState(emptyMap<String, ModelStatus>(), models) {
        while (true) {
            value = withContext(Dispatchers.IO) { (models + speakerList).distinctBy { it.id }.associate { it.id to Recording.models.status(it) } }
            delay(1_000)
        }
    }
    val npu = remember(statuses, checking, changed) {
        models.filter { it.kind.onNpu }.mapNotNull { m ->
            when {
                m.id in checking -> m.id to NpuCheck.Checking
                else -> Recording.modelLibrary.npu(m.id)?.let { m.id to if (it.runs) NpuCheck.Runs else NpuCheck.Fails(it.reason ?: "") }
            }
        }.toMap()
    }
    // An NPU model is tried on the NPU once, after its download.
    LaunchedEffect(statuses) {
        for (m in models.filter { it.kind.onNpu && it.runsOn(chip) }) {
            if (statuses[m.id] != ModelStatus.Ready || m.id in checking || Recording.modelLibrary.npu(m.id) != null) continue
            checking = checking + m.id
            scope.launch {
                withContext(Dispatchers.IO) { Recording.checkNpu(m) }
                checking = checking - m.id
                changed++
            }
        }
    }
    val languages = (library.moments.map { it.language } + library.lastLanguage + Recording.phoneLanguage() + added).distinct()
    // Recording or improving: the models may be in use, none is removed then.
    val busy = { Recording.session.isRecording || Recording.refining.value != null }
    val state = ModelsState(
        languages = languages, chip = chip, models = models, statuses = statuses,
        live = languages.mapNotNull { l -> Recording.settings.liveModel(l)?.let { l to it } }.toMap(),
        refine = languages.mapNotNull { l -> Recording.settings.refineModel(l)?.let { l to it } }.toMap(),
        npu = npu, audioFormat = format, flacLevel = flacLevel, aac = aac, flac = flac, profile = shown, activeProfile = active, profileSettings = profileSettings, codecs = remember { com.chencohensolution.transcriptor.recording.OtherEncoders.available }, encodeAfter = encodeAfter,
        aacEncoders = AacEncoders.all, autoAacEncoder = remember(aac) { AacEncoders.candidates(aac.copy(encoder = null)).first() }, findSpeakersOnStop = speakers, autoImprove = auto, transcribeWhen = transcribeWhen, quietRecording = quiet, stopTwice = stopTwice, advancedMode = advancedMode, deepCapture = remember(micInput, micInputs) { Recording.audioInputs.deepCapture(micInput) }, folder = folder, processorMeters = meters, levelMeter = levelMeter, micInput = micInput, micInputs = micInputs, micDetails = remember { Recording.audioInputs.builtInDetails() }, micMeter = micMeter, micTestOff = micTestOff, freeBytes = remember { runCatching { android.os.StatFs(context.filesDir.path).availableBytes }.getOrDefault(0L) },
        voiceModel = remember(statuses, changed) { Recording.voiceModel?.id },
        segmentationModel = remember(statuses, changed) { Recording.segmentationModel?.id },
        speakerTuning = remember(changed, speakerList) { speakerList.associate { it.id to Recording.settings.speakerTuning(it.id) } },
        busy = remember(statuses) { busy() },
        server = server.paired?.let { p ->
            val name = com.chencohensolution.transcriptor.ui.isolated(p.server.removePrefix("https://"))
            if (server.waiting > 0) androidx.compose.ui.res.stringResource(R.string.server_on_waiting, name, server.waiting)
            else androidx.compose.ui.res.stringResource(R.string.server_on, name)
        } ?: androidx.compose.ui.res.stringResource(R.string.server_off),
        npuVoiceModels = remember(statuses) {
            // Voice prints and speaker turns with their NPU build here for this phone's chip.
            speakerList.filter { m ->
                statuses[m.id] == ModelStatus.Ready && when (m.kind) {
                    ModelKind.SpeakerEmbedding -> Recording.voicePrintFiles(m).npu.isNotEmpty()
                    ModelKind.SpeakerSegmentation -> SherpaNpuWhisperEngine.chip == "SM8750" &&
                        java.io.File(Recording.models.dir(m), com.chencohensolution.transcriptor.speech.SherpaSegmenter.NPU_FILE).exists()
                    else -> false
                }
            }.map { it.id }.toSet()
        },
    )
    val io = { work: () -> Unit -> scope.launch(Dispatchers.IO) { work(); changed++ }; Unit }
    ModelsScreen(
        state,
        ModelsActions(
            back = onBack,
            download = { m -> io { Recording.models.download(m) } },
            cancel = { m -> io { Recording.models.cancel(m) } },
            delete = { m -> io { if (!busy()) { Recording.models.delete(m); Recording.settings.forgetModel(m.id) } } },
            useLive = { l, m -> io { Recording.settings.setLiveModel(l, m.id) } },
            useRefine = { l, m -> io { Recording.settings.setRefineModel(l, m.id) } },
            useVoicePrints = { m -> io { Recording.settings.voiceModel = m.id } },
            setSpeakerTuning = { m, more -> io { Recording.settings.setSpeakerTuning(m.id, more) } },
            useSegmentation = { m -> io { Recording.settings.segmentationModel = m.id } },
            retryNpu = { m -> io { Recording.npuGuard.reenable(m.id) } },
            // Every recording setting goes into the shown profile; WAV first and the timing are for all.
            setAudioFormat = { f ->
                val first = f.name.startsWith("WavThen")
                val music = shown == com.chencohensolution.transcriptor.settings.QualityProfile.Music
                // Music keeps its own WAV first; the others share one (not changed by picking WAV).
                edit { it.copy(codec = com.chencohensolution.transcriptor.settings.Codec.of(f), wavFirst = if (music && f != com.chencohensolution.transcriptor.settings.AudioFormat.Wav) first else it.wavFirst) }
                if (!music && f != com.chencohensolution.transcriptor.settings.AudioFormat.Wav) store.wavFirst = first
                load()
            },
            setFlacLevel = { level -> edit { it.copy(flacLevel = level) } },
            setAac = { a -> edit { it.copy(aac = a.valid()) } },
            setFlac = { f -> edit { it.copy(flac = f.valid()) } },
            setSound = { rate, channels, bits ->
                val next = state.recordingChoice.withSound(rate, channels, bits)
                edit { it.copy(aac = next.aac, flac = next.flac) }
            },
            showProfile = { p ->
                if (p != com.chencohensolution.transcriptor.settings.QualityProfile.Music) store.activeProfile = p
                shown = p
                load()
            },
            resetProfile = { store.resetProfile(shown); load() },
            setBitrate = { codec, bps -> edit { it.copy(bitrates = it.bitrates + (codec.key to bps)) } },
            setPower = { preset ->
                val next = state.recordingChoice.with(preset)
                val wavFirst = if (format == com.chencohensolution.transcriptor.settings.AudioFormat.Wav || shown == com.chencohensolution.transcriptor.settings.QualityProfile.Music) store.wavFirst
                    else next.audioFormat.name.startsWith("WavThen")
                store.setTiming(wavFirst, next.encodeAfter, next.transcribeWhen)
                // Shown on Music, the battery's WAV first is Music's own.
                if (shown == com.chencohensolution.transcriptor.settings.QualityProfile.Music && format != com.chencohensolution.transcriptor.settings.AudioFormat.Wav) {
                    edit { it.copy(wavFirst = next.audioFormat.name.startsWith("WavThen")) }
                }
                load()
            },
            setEncodeAfter = { encodeAfter = it; io { Recording.settings.encodeAfter = it } },
            setTranscribeWhen = { transcribeWhen = it; io { Recording.settings.transcribeWhen = it } },
            setFindSpeakersOnStop = { speakers = it; io { Recording.settings.findSpeakersOnStop = it } },
            setAutoImprove = { auto = it; io { Recording.settings.autoImprove = it } },
            setQuietRecording = { quiet = it; io { Recording.settings.quietRecording = it } },
            setStopTwice = { stopTwice = it; io { Recording.settings.stopTwice = it } },
            setAdvancedMode = { advancedMode = it; io { Recording.settings.advancedMode = it } },
            openNotificationSettings = {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            setProcessorMeters = { meters = it; io { Recording.settings.processorMeters = it } },
            setLevelMeter = { levelMeter = it; io { Recording.settings.levelMeter = it } },
            setMicInput = { m -> edit { it.copy(micInput = m) } },
            startMicTest = { input ->
                val allowed = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                micTestOff = !allowed || Recording.session.isRecording || !probe.start(input)
            },
            stopMicTest = { probe.stop() },
            chooseFolder = chooseFolder,
            openServer = { serverOpen = true },
            addLanguage = { l -> added = (added + l).distinct(); io { Recording.settings.modelLanguages = added } },
            search = { query, language, kind, npuOnly ->
                hub = HubSearch(query, language, kind, npuOnly, searching = true, results = hub.results)
                scope.launch {
                    hub = runCatching { Recording.hub.search(query, language, kind, npuOnly, chip) }.fold(
                        { r -> hub.copy(searching = false, results = r.models, unusable = r.unusable, others = r.others, error = null) },
                        { hub.copy(searching = false, error = it.message ?: it.toString()) },
                    )
                }
            },
            install = { m, v ->
                val model = m.toSpeechModel(v)
                io {
                    Recording.installedModels.add(InstalledModel(model, m.repo, m.use))
                    Recording.models.download(model)
                }
            },
            remove = { m -> io { if (!busy()) { Recording.models.delete(m); Recording.installedModels.remove(m.id); Recording.settings.forgetModel(m.id) } } },
        ),
        hub,
    )
}
