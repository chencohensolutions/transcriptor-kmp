package com.chencohensolution.transcriptor.ui

import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

// Fakes: as `review current` (playback and passes). The Word file is real (LabWord, made here in the lab, not by the
// app's logic) and Share… opens Android's share sheet with it; Save in the lecture's folder saves it in
// Downloads/Transcriptor instead (the lab can't reach the lectures folder). The choices aren't remembered. Delete
// lecture only says so.
internal val reviewOptionA = LabOption("a", "Export as Word… in the menu", "⋮ → Export as Word…: what goes in, a preview, Share… or Save; ⋮ → Delete lecture…") {
    val (state, actions) = rememberReviewExportLab()
    val context = LocalContext.current
    ReviewScreenA(
        state, actions,
        exportWord = { choice, to ->
            val name = state.lecture.title + ".docx"
            val uri = LabWord.save(context, name, LabWord.bytes(context, state.lecture, state.notebookTitle, choice.speakers, choice.times, choice.notes))
            if (to == WordDestinationA.Share) LabWord.share(context, uri, name)
            else Toast.makeText(context, "Lab: saved in Downloads/Transcriptor (the app: in the lecture's folder)", Toast.LENGTH_LONG).show()
        },
        deleteLecture = { Toast.makeText(context, "Lab: deleted; the list would open with it gone", Toast.LENGTH_LONG).show() },
    )
}
