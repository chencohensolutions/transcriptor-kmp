package com.chencohensolution.transcriptor.ui

import com.chencohensolution.transcriptor.notebook.LectureSummary

/** A label a moment can have (any number of them): its name and its color, an index into the app's palette. */
data class NotebooksOptionBLabel(val id: String, val name: String, val color: Int)

/** A moment as the list shows it: its summary, its own language (it lays out its title) and its labels. */
data class NotebooksOptionBMoment(val summary: LectureSummary, val language: String, val labelIds: List<String>)

/** A moment recording now, with the labels it will get and its language. */
data class NotebooksOptionBRecording(val labelIds: List<String>, val title: String, val elapsedMillis: Long)

/** The labels guessed for a moment started now, from when moments with them usually are ("Monday at 10:00"). */
data class NotebooksOptionBGuess(val labelIds: List<String>, val usualStartMillis: Long)

/** What the first screen shows when moments have labels instead of notebooks. */
data class NotebooksOptionBState(
    /** In the order they were made. */
    val labels: List<NotebooksOptionBLabel>,
    /** Every moment, newest first (the one recording now is the banner, not here). */
    val moments: List<NotebooksOptionBMoment>,
    val nowMillis: Long,
    /** The language the next moment records in: the guessed labels' usual one, else the last moment's. */
    val language: String,
    val guess: NotebooksOptionBGuess?,
    /** For moments without labels: the label their time suggests, by moment id. */
    val suggested: Map<String, String>,
    val recording: NotebooksOptionBRecording? = null,
    val working: Map<String, Float> = emptyMap(),
)

class NotebooksOptionBActions(
    val openMoment: (momentId: String) -> Unit = {},
    /** Starts a moment with these labels (maybe none) in [language]. */
    val record: (labelIds: List<String>, language: String) -> Unit = { _, _ -> },
    val backToRecording: () -> Unit = {},
    /** Makes a label; returns its id. */
    val newLabel: (name: String) -> String = { "" },
    /** Adds [add] and removes [remove] on every moment in [momentIds]. */
    val setLabels: (momentIds: List<String>, add: Set<String>, remove: Set<String>) -> Unit = { _, _, _ -> },
    val renameLabel: (labelId: String, name: String) -> Unit = { _, _ -> },
    /** Deletes a label: its moments stay, without it. */
    val deleteLabel: (labelId: String) -> Unit = {},
    val settings: () -> Unit = {},
    val delete: (momentIds: List<String>) -> Unit = {},
)
