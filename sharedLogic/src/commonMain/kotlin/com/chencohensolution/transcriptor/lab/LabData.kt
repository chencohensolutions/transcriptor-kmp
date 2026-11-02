package com.chencohensolution.transcriptor.lab

import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureSummary
import com.chencohensolution.transcriptor.notebook.Note
import com.chencohensolution.transcriptor.notebook.Notebook
import com.chencohensolution.transcriptor.notebook.Segment
import com.chencohensolution.transcriptor.notebook.Speaker

/** Made-up notebooks and a lecture for the UI labs (Android's src/debug, and iOS later). */
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
        notebookId = "psych",
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

    private fun summary(id: String, title: String, daysAgo: Int, minutes: Int, notes: Int, refined: Boolean = true) =
        LectureSummary(id, title, LECTURE_START - daysAgo * 24 * 60 * MIN, minutes * MIN, notes, refined)

    val notebooks = listOf(
        Notebook(
            "psych", "מבוא לפסיכולוגיה", "he",
            listOf(
                summary("lecture-7", "זיכרון עבודה", 0, 47, 3, refined = false),
                summary("lecture-6", "המודל המולטי־מחסני", 7, 88, 11),
                summary("lecture-5", "קשב סלקטיבי", 14, 91, 6),
                summary("lecture-4", "תפיסה חזותית", 21, 85, 9),
            ),
        ),
        Notebook(
            "stats", "סטטיסטיקה א׳", "he",
            listOf(
                summary("stats-3", "התפלגות נורמלית", 2, 90, 14),
                summary("stats-2", "מדדי פיזור", 9, 90, 8),
            ),
        ),
        Notebook(
            "english", "Academic English", "en",
            listOf(summary("en-1", "Reading abstracts", 4, 60, 2)),
        ),
    )
}
