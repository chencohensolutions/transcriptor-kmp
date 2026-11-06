package com.chencohensolution.transcriptor.ui

/** What a Word export of a lecture holds: the speakers' names, each paragraph's time, the notes. */
data class WordExportA(val speakers: Boolean = true, val times: Boolean = true, val notes: Boolean = true)

/** Where the Word file goes: Android's share sheet, or the lecture's folder beside its recording. */
enum class WordDestinationA { Share, LectureFolder }
