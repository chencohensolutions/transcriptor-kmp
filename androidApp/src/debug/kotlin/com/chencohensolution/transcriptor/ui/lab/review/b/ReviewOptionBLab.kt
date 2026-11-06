package com.chencohensolution.transcriptor.ui

import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

// Fakes: as `review current` (playback and passes). The Word file is real (LabWord, made here in the lab, not by the
// app's logic) and Share opens Android's share sheet with it; Save as Word saves it in Downloads/Transcriptor instead
// (the lab can't reach the lectures folder). Delete lecture only says so.
internal val reviewOptionB = LabOption("b", "Share in the top bar", "Share button: one tap shares a Word file with everything; hold or ⋮ to save it; ⋮ → Delete lecture…") {
    val (state, actions) = rememberReviewExportLab()
    val context = LocalContext.current
    fun word() = LabWord.save(context, state.lecture.title + ".docx", LabWord.bytes(context, state.lecture, state.notebookTitle, speakers = true, times = true, notes = true))
    ReviewScreenB(
        state, actions,
        shareWord = { LabWord.share(context, word(), state.lecture.title + ".docx") },
        saveWord = { word(); Toast.makeText(context, "Lab: saved in Downloads/Transcriptor (the app: in the lecture's folder)", Toast.LENGTH_LONG).show() },
        deleteLecture = { Toast.makeText(context, "Lab: deleted; the list would open with it gone", Toast.LENGTH_LONG).show() },
    )
}
