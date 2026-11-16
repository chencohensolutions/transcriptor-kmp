package com.chencohensolution.transcriptor.notebook

import com.chencohensolution.transcriptor.settings.RecordMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The labels and the moments on the phone (and in the library), as the first screen lists them. */
data class Library(
    /** The labels in the order they were made. */
    val labels: List<Label> = emptyList(),
    /** Every moment, newest first, with only labels that still exist. */
    val moments: List<LectureSummary> = emptyList(),
    /** The last moment's language; the phone's before any. */
    val lastLanguage: String = "en",
    /** The server has older recordings than [moments] holds: the list reads them as it nears its end. */
    val serverOlder: Boolean = false,
    /** Newer recordings were let go from the list's window: the list reads them again as it nears its top. */
    val serverNewer: Boolean = false,
    /** A page of the server's recordings is on its way. */
    val serverLoading: Boolean = false,
)

/**
 * The part of the server's recordings the list holds ([com.chencohensolution.transcriptor.backup.ServerMoments]),
 * as moments: those not moments here, newest first, and whether there are more either side.
 */
data class ServerPart(
    val moments: List<LectureSummary> = emptyList(),
    val older: Boolean = false,
    val newer: Boolean = false,
    val loading: Boolean = false,
)

/** A moment recording now, with the labels it was started with, for the first screen's banner. */
data class RecordingNow(val labels: List<String>, val lectureTitle: String, val elapsedMillis: Long, val lectureId: String? = null)

/** What stays behind the phone's lock besides tucked moments and locked labels' ([LectureSummary.locked]). */
data class Locks(val calls: Boolean = false, val voiceMessages: Boolean = false)

/** Whether a moment from the server's [source] is a phone call. */
fun isCall(source: String?): Boolean = source.equals("call", ignoreCase = true)

/**
 * The label every call has, made the first time a call is in the list (with the same id on every phone, so the
 * server's copy of the labels merges them): calls are told apart from recordings, and its Needs fingerprint hides
 * them. It is never written into a moment: a call has it while it is a call.
 */
const val CALLS_LABEL = "calls"

/** Whether a moment from the server's [source] is a voice message (WhatsApp's). */
fun isVoiceMessage(source: String?): Boolean = source?.lowercase()?.let { it == "folder:whatsapp" || it == "whatsapp" } == true

/** What the first screen shows. */
data class NotebooksState(
    /** In the order they were made. */
    val labels: List<Label>,
    /** Every moment, newest first (the one recording now is the banner, not here). */
    val moments: List<LectureSummary>,
    val nowMillis: Long,
    /** The language the next moment records in: the guessed label's usual one, else the last moment's. */
    val language: String,
    /** The labels a moment started now would get, from when moments with them usually are; null when none fits. */
    val guess: LabelGuess?,
    /** For moments without labels: the label their time suggests, by moment id. */
    val suggested: Map<String, String>,
    val recording: RecordingNow? = null,
    /** Moments with a pass running on them (the speaker pass, refining, compressing to FLAC): their progress. */
    val working: Map<String, Float> = emptyMap(),
    /** The Record bar's Live, After or Only record, as the settings keep it ([com.chencohensolution.transcriptor.settings.SettingsStore.recordMode]). */
    val recordMode: RecordMode = RecordMode.After,
    /** The list can read older recordings from the server as it nears its end ([Library.serverOlder]). */
    val serverOlder: Boolean = false,
    /** The list can read newer recordings again as it nears its top ([Library.serverNewer]). */
    val serverNewer: Boolean = false,
    val serverLoading: Boolean = false,
)

/**
 * The screen's state from the [library] at [now], with what is recording and running. The moment recording now is the
 * banner, not in the list.
 */
fun notebooksState(
    library: Library, now: Long, utcOffset: (Long) -> Long,
    recording: RecordingNow? = null, working: Map<String, Float> = emptyMap(),
): NotebooksState {
    val moments = library.moments.filter { it.id != recording?.lectureId }
    val guess = guessLabels(moments, now, utcOffset)
    val language = guess?.labels?.firstOrNull()?.let { label -> usualLanguage(moments.filter { label in it.labels }) } ?: library.lastLanguage
    return NotebooksState(
        labels = library.labels,
        moments = moments,
        nowMillis = now,
        language = language,
        guess = guess,
        // Not for calls and voice messages: a label by the hour is for classes and the like.
        suggested = moments.filter { it.labels.isEmpty() && it.source == null }
            .mapNotNull { m -> guessLabels(moments, m.startedAtMillis, utcOffset)?.labels?.firstOrNull()?.let { m.id to it } }.toMap(),
        recording = recording,
        working = working,
        serverOlder = library.serverOlder,
        serverNewer = library.serverNewer,
        serverLoading = library.serverLoading,
    )
}

/** The language most of [moments] are in; null for none. */
private fun usualLanguage(moments: List<LectureSummary>): String? =
    moments.groupingBy { it.language }.eachCount().maxByOrNull { it.value }?.key

/** A moment as a list shows it. */
fun Lecture.summary(): LectureSummary {
    val talk = segments.filter { it.speakerId != null }.groupBy { it.speakerId }.mapValues { (_, lines) -> lines.sumOf { it.endMillis - it.startMillis } }
    val spoke = speakers.filter { it.id in talk }.sortedByDescending { talk[it.id] }
    return LectureSummary(
        id, title, startedAtMillis, durationMillis, notes.size, refinedWith != null, language, labels, pendingTranscript,
        people = spoke.filter { it.name.isNotEmpty() }.map { it.name }, unnamedPeople = spoke.count { it.name.isEmpty() },
        preview = previewOf(this), tucked = tucked, source = source,
    )
}

/** The first line with at least [PREVIEW_WORDS] words (a greeting or an "okay" says little), else the first, cut to [PREVIEW_CHARS]. */
internal fun previewOf(lecture: Lecture): String {
    val line = lecture.segments.firstOrNull { it.text.trim().split(' ').size >= PREVIEW_WORDS } ?: lecture.segments.firstOrNull() ?: return ""
    val text = line.text.trim()
    return if (text.length <= PREVIEW_CHARS) text else text.take(PREVIEW_CHARS).substringBeforeLast(' ').trimEnd(',', ';', ':') + "…"
}

private const val PREVIEW_WORDS = 5
private const val PREVIEW_CHARS = 110

/** The names of [ids]' labels, in order, joined ("Psychology, Exam"); empty for none. */
fun labelNames(ids: List<String>, labels: List<Label>): String = ids.mapNotNull { id -> labels.firstOrNull { it.id == id }?.name }.joinToString(", ")

/**
 * The labels and the moments ([library]), read from the stores by [reload] (files: not on the main thread), and the
 * changes the first screen makes: labels made, renamed and deleted, moments labeled and deleted. A moment that is
 * [busy] (being recorded, or a pass runs on it) is left as it is, since its own save would undo the change.
 */
class NotebooksController(
    private val labels: LabelStore,
    private val lectures: LectureStore,
    private val now: () -> Long,
    /** The language before any moment: the phone's ([com.chencohensolution.transcriptor.models.Languages.default]). */
    private val defaultLanguage: () -> String = { "en" },
    /** Whether calls and voice messages are behind the phone's lock (Settings). */
    private val locks: () -> Locks = { Locks() },
    /** The Calls label's name in the phone's language ([CALLS_LABEL]); null: calls get no label. */
    private val callsLabel: () -> String? = { null },
) {
    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library.asStateFlow()

    fun reload() {
        // The index, not every moment's folder (hundreds of files in the lectures folder).
        val all = lectures.index().sortedByDescending { it.startedAtMillis }
        local = all.map { it.summary().copy(audioOnServer = it.serverSha256 != null && !it.hasAudio) }
        localLanguage = all.firstOrNull()?.language
        known = labels.all()
        compose()
        lectures.saveIndex()
    }

    private var local: List<LectureSummary> = emptyList()
    private var localLanguage: String? = null
    private var known: List<Label> = emptyList()
    private var server = ServerPart()

    /**
     * The server's recordings the list holds now ([ServerPart]), shown among the moments here by when they were made.
     * While the window of pages doesn't reach the newest or the oldest, the moments here past its edges wait too, so
     * the list reads in one order whichever way the user scrolls.
     */
    fun setServer(part: ServerPart) {
        server = part
        compose()
    }

    private fun compose() {
        val name = callsLabel()
        if (name != null && known.none { it.id == CALLS_LABEL } && (local.any { isCall(it.source) } || server.moments.any { isCall(it.source) })) {
            runCatching { labels.ensure(CALLS_LABEL, name, now(), locks().calls) }
            known = labels.all()
        }
        val ids = known.map { it.id }.toSet()
        val lockedLabels = known.filter { it.locked }.map { it.id }.toSet()
        val lock = locks()
        fun locked(s: LectureSummary) = s.labels.any { it in lockedLabels } ||
            (lock.calls && isCall(s.source)) || (lock.voiceMessages && isVoiceMessage(s.source))
        val newest = server.moments.firstOrNull()?.startedAtMillis.takeIf { server.newer }
        val oldest = server.moments.lastOrNull()?.startedAtMillis.takeIf { server.older }
        val here = local.filter { (newest == null || it.startedAtMillis <= newest) && (oldest == null || it.startedAtMillis >= oldest) }
        val hereIds = here.map { it.id }.toSet()
        val moments = (here + server.moments.filter { it.id !in hereIds }).sortedByDescending { it.startedAtMillis }
        _library.value = Library(
            labels = known,
            moments = moments.map { s ->
                val withCall = if (isCall(s.source) && CALLS_LABEL in ids && CALLS_LABEL !in s.labels) s.labels + CALLS_LABEL else s.labels
                s.copy(labels = withCall.filter { l -> l in ids }).let { it.copy(locked = locked(it)) }
            },
            lastLanguage = localLanguage ?: defaultLanguage(),
            serverOlder = server.older,
            serverNewer = server.newer,
            serverLoading = server.loading,
        )
    }

    fun newLabel(name: String): Label = labels.create(name, now()).also { reload() }

    /** The moments [query] is found in, newest first ([searchMoments]). Reads every moment: not on the main thread. */
    fun search(query: String): List<SearchHit> {
        val shut = _library.value.moments.filter { it.locked }.map { it.id }.toSet()
        return searchMoments(lectures.all().filter { !it.tucked && it.id !in shut }, labels.all(), query)
    }

    /** Puts the label's moments behind the phone's lock ("Needs fingerprint"), or takes them out. */
    fun setLabelLocked(id: String, locked: Boolean) {
        labels.setLocked(id, locked)
        reload()
    }

    /** The locks changed in Settings: the list again. */
    fun locksChanged() = compose()

    /** Tucks [lectureIds] away, or brings them back, skipping the [busy] ones; returns the ids changed. */
    fun setTucked(lectureIds: Collection<String>, tucked: Boolean, busy: (String) -> Boolean = { false }): List<String> {
        val changed = lectureIds.filter { id ->
            if (busy(id)) return@filter false
            val lecture = lectures.load(id) ?: return@filter false
            lecture.tucked != tucked && lectures.setTucked(lecture, tucked) != lecture
        }
        reload()
        return changed
    }

    /** Adds [add] and removes [remove] on each of [lectureIds] that isn't [busy]; returns the ids changed. */
    fun setLabels(lectureIds: Collection<String>, add: Set<String>, remove: Set<String>, busy: (String) -> Boolean = { false }): List<String> {
        val changed = lectureIds.filter { id ->
            if (busy(id)) return@filter false
            val lecture = lectures.load(id) ?: return@filter false
            val next = (lecture.labels + add).distinct() - remove
            next != lecture.labels && lectures.setLabels(lecture, next) != lecture
        }
        reload()
        return changed
    }

    fun renameLabel(id: String, name: String) {
        labels.rename(id, name)
        reload()
    }

    /** Deletes a label; the moments that had it stay, without it ([busy] ones keep its id, shown as nothing). */
    fun deleteLabel(id: String, busy: (String) -> Boolean = { false }) {
        labels.delete(id)
        for (m in _library.value.moments.filter { id in it.labels }) {
            if (busy(m.id)) continue
            lectures.load(m.id)?.let { lectures.setLabels(it, it.labels - id) }
        }
        reload()
    }

    /**
     * Deletes moments for good (by id), skipping those [busy] says are being recorded or worked on; returns the ids
     * deleted.
     */
    fun delete(lectureIds: Collection<String>, busy: (String) -> Boolean = { false }): List<String> {
        val deleted = lectureIds.filter { id -> !busy(id) && lectures.delete(id) }
        reload()
        return deleted
    }
}
