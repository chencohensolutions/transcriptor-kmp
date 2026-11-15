package com.chencohensolution.transcriptor.lab

import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureSummary
import com.chencohensolution.transcriptor.notebook.Note
import com.chencohensolution.transcriptor.notebook.Label
import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Speaker

/** Made-up labels, moments and a lecture for the UI labs (Android's src/debug, and iOS later). */
object LabData {
    private const val MIN = 60_000L
    private const val TURBO = "ivrit-whisper-large-v3-turbo"
    private const val LARGE = "ivrit-whisper-large-v3"

    /** Monday 2 November 2026, 10:00 Israel time. */
    private const val LECTURE_START = 1_793_606_400_000L

    private fun s(id: Int, start: Double, end: Double, text: String, speaker: String? = null, edited: Boolean = false) =
        Segment("s$id", (start * 1000).toLong(), (end * 1000).toLong(), text, TURBO, edited, speaker)

    /** A psychology class, 47 minutes in, as live transcription writes it. */
    val lecture = Lecture(
        id = "lecture-7",
        labels = listOf("psych"),
        title = "זיכרון עבודה",
        language = "he",
        startedAtMillis = LECTURE_START,
        durationMillis = 47 * MIN + 12_000,
        segments = listOf(
            s(1, 2.0, 14.5, "טוב, בוקר טוב לכולם. היום אנחנו ממשיכים עם הנושא של זיכרון, ובמיוחד זיכרון עבודה."),
            s(2, 15.1, 31.8, "בשיעור הקודם דיברנו על המודל של אטקינסון ושיפרין, המודל המולטי־מחסני, ואמרנו שיש לו כמה בעיות."),
            s(3, 32.4, 51.0, "הבעיה המרכזית היא שהזיכרון לטווח קצר שם הוא מחסן פסיבי. הוא רק מחזיק מידע, הוא לא עושה איתו כלום."),
            s(4, 52.2, 70.3, "ב־1974 באדלי והיץ' מציעים משהו אחר לגמרי. הם אומרים: זה לא מחסן, זו מערכת עבודה."),
            s(5, 71.0, 96.4, "יש לה שלושה רכיבים. הלולאה הפונולוגית, הלוח החזותי־מרחבי, ומעליהם המנהל המרכזי, שמחלק את הקשב ביניהם."),
            s(6, 97.0, 104.2, "שאלה, זה נכנס למבחן?"),
            s(7, 104.8, 118.9, "הכול נכנס למבחן. אבל כן, את שלושת הרכיבים אתם צריכים לדעת להסביר ולתת דוגמה לכל אחד."),
            s(8, 120.5, 146.0, "הלולאה הפונולוגית מחזיקה מידע מילולי, בערך שתי שניות של דיבור. אם אתם חוזרים על מספר טלפון בראש, זו היא."),
            s(9, 147.2, 171.6, "ויש לזה ניסוי יפה: אם נותנים לנבדקים לחזור על מילה לא קשורה, \"דה דה דה\", הזכירה של רשימת מילים יורדת מאוד."),
            s(10, 172.0, 190.4, "זה נקרא דיכוי ארטיקולטורי. תכתבו את זה, נחזור לזה בתרגול."),
        ),
        notes = listOf(
            Note("n1", 33_000, "פסיבי ↔ אקטיבי: זה ההבדל העיקרי בין המודלים"),
            Note("n2", 72_000, "לצייר את התרשים של שלושת הרכיבים מהמצגת, שקף 14"),
            Note("n3", 173_500, "דיכוי ארטיקולטורי – לבדוק בספר עמ' 212"),
        ),
    )

    /** What live transcription is writing now, before the line ends. */
    const val partialLine = "הלוח החזותי־מרחבי, לעומת זאת, מחזיק"

    /** The lecture as live transcription labels its speakers: the lecturer named in an earlier lecture, a student not. */
    val liveLectureWithSpeakers = lecture.copy(
        segments = lecture.segments.map { it.copy(speakerId = if (it.id == "s6") "speaker-2" else "speaker-1") },
        speakers = listOf(Speaker("speaker-1", "המרצה"), Speaker("speaker-2", "")),
    )

    /** The same lecture after the refine pass told the voices apart and a line was fixed by hand. */
    val refinedLecture = lecture.copy(
        segments = lecture.segments.map { seg ->
            val student = seg.id == "s6"
            seg.copy(
                modelId = LARGE,
                speakerId = if (student) "speaker-2" else "speaker-1",
                text = if (seg.id == "s2") "בשיעור הקודם דיברנו על המודל של אטקינסון ושיפרין, המודל הרב־מחסני, ואמרנו שיש לו כמה בעיות." else seg.text,
                edited = seg.id == "s2",
            )
        },
        speakers = listOf(Speaker("speaker-1", "המרצה"), Speaker("speaker-2", "דובר 2")),
    )

    private fun summary(id: String, title: String, daysAgo: Int, minutes: Int, notes: Int, labels: List<String>, language: String = "he", refined: Boolean = true) =
        LectureSummary(id, title, LECTURE_START - daysAgo * 24 * 60 * MIN, minutes * MIN, notes, refined, language, labels)

    val labels = listOf(
        Label("psych", "פסיכולוגיה", 0),
        Label("stats", "סטטיסטיקה", 1),
        Label("english", "Academic English", 2),
        Label("exam", "מבחן", 3),
    )

    /** Moments with labels, newest first: Monday psychology classes (one before an exam), statistics, English. */
    val moments = listOf(
        summary("lecture-7", "זיכרון עבודה", 0, 47, 3, listOf("psych"), refined = false),
        summary("stats-3", "התפלגות נורמלית", 2, 90, 14, listOf("stats", "exam")),
        summary("en-1", "Reading abstracts", 4, 60, 2, listOf("english"), language = "en"),
        summary("lecture-6", "המודל המולטי־מחסני", 7, 88, 11, listOf("psych", "exam")),
        summary("stats-2", "מדדי פיזור", 9, 90, 8, listOf("stats")),
        summary("lecture-5", "קשב סלקטיבי", 14, 91, 6, listOf("psych")),
        summary("lecture-4", "תפיסה חזותית", 21, 85, 9, listOf("psych")),
    )

    /**
     * A casual English conversation of four at a table (the app's main use): short turns, one long one, and a sliver
     * that live speaker turns split off Maya ("Speaker 5"). Hen's voice was known; the others aren't named yet.
     */
    val conversation: Lecture = run {
        val said = listOf(
            "speaker-2" to "no, the second weekend, the first one they're fully booked",
            "speaker-3" to "the guy said he'd throw in the boat",
            "speaker-1" to "wait, what boat",
            "speaker-3" to "there's a rowing boat. It's on the listing, third photo, under the tarp. I asked him about it when we spoke and he said nobody ever uses it, so it's ours for the weekend if we want it.",
            "speaker-4" to "I'm not rowing anything",
            "speaker-2" to "you say that now",
            "speaker-5" to "every time",
            "speaker-1" to "okay so who's driving, because my car is still at the garage",
            "speaker-4" to "I can take three, plus the bags if we pack light",
            "speaker-3" to "pack light, she says",
            "speaker-2" to "we leave Friday at four then, before the traffic",
            "speaker-1" to "four works. I'll bring the grill.",
        )
        var at = 724_000L
        val lines = said.mapIndexed { i, (speaker, text) ->
            val length = 900L + text.length * 62L
            Segment("s${i + 1}", at, at + length, text, "whisper-large-v3", speakerId = speaker).also { at += length + 350 }
        }
        Lecture(
            id = "lab-conversation", title = "Sat dinner", language = "en", startedAtMillis = LECTURE_START - 2 * 24 * 60 * MIN,
            durationMillis = 42 * MIN + 8_000, segments = lines,
            notes = listOf(Note("n1", lines[3].endMillis, "ask about the boat")),
            speakers = listOf(Speaker("speaker-1", "Hen"), Speaker("speaker-2", ""), Speaker("speaker-3", ""), Speaker("speaker-4", ""), Speaker("speaker-5", "")),
        )
    }

    /** The voices the app would know for [conversation]. */
    val knownNames = listOf("Dan", "Maya", "Noa", "Hen")

    /** Labels for [conversations]. */
    val conversationLabels = listOf(Label("lab-friends", "Friends", 2), Label("lab-home", "Home", 4), Label("lab-trips", "Trips", 1))

    /**
     * English conversations for the first screen and its search (the app's main use): a dinner of four with one voice
     * still unnamed, a trip being planned, and a short call. Titles are as the app gives them (the date) except one
     * the user renamed.
     */
    val conversations: List<Lecture> = run {
        fun moment(id: String, title: String, daysAgo: Int, minutes: Int, speakers: List<Speaker>, labels: List<String>, notes: List<Note>, vararg said: Pair<Int, String>): Lecture {
            var at = 4_000L
            val lines = said.mapIndexed { i, (who, text) ->
                val length = 900L + text.length * 62L
                Segment("s${i + 1}", at, at + length, text, "whisper-large-v3", speakerId = "speaker-$who").also { at += length + 350 }
            }
            return Lecture(id, title, "en", LECTURE_START - daysAgo * 24 * 60 * MIN + 9 * 60 * MIN, minutes * MIN, lines, notes, speakers, labels = labels, refinedWith = "whisper-large-v3")
        }
        listOf(
            moment(
                "lab-dinner", "Nov 14, 2026", 1, 42,
                listOf(Speaker("speaker-1", "Hen"), Speaker("speaker-2", "Maya"), Speaker("speaker-3", "Dan"), Speaker("speaker-4", "")),
                listOf("lab-friends"), listOf(Note("n1", 30_000, "ask about the boat")),
                2 to "okay", 2 to "no, the second weekend, the first one they're fully booked",
                3 to "the guy said he'd throw in the boat", 1 to "wait, what boat",
                3 to "there's a rowing boat. It's on the listing, third photo, under the tarp.",
                4 to "I'm not rowing anything", 1 to "Dan, tell them about the cabin guy and what he said about the stove",
            ),
            moment(
                "lab-trip", "Trip planning", 4, 72,
                listOf(Speaker("speaker-1", "Hen"), Speaker("speaker-2", "Maya"), Speaker("speaker-3", "Dan"), Speaker("speaker-4", "Noa")),
                listOf("lab-friends", "lab-trips"), emptyList(),
                1 to "so we booked the cabin for the second weekend, and it's two nights",
                4 to "who's bringing the grill", 3 to "Hen is, he said so at dinner", 2 to "we leave Friday at four then, before the traffic",
                1 to "the cabin has a stove but bring the grill anyway",
            ),
            moment(
                "lab-landlord", "Nov 9, 2026", 6, 9,
                listOf(Speaker("speaker-1", "Hen"), Speaker("speaker-2", "")),
                listOf("lab-home"), emptyList(),
                2 to "hello", 1 to "hi, it's about the boiler again, it stopped on Sunday night",
                2 to "I'll send someone on Tuesday morning, between eight and ten",
            ),
            moment("lab-memo", "Nov 8, 2026", 7, 3, emptyList(), emptyList(), emptyList()).copy(refinedWith = null),
        )
    }
}
