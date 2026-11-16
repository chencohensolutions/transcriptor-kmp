package com.chencohensolution.transcriptor.backup

import com.chencohensolution.transcriptor.notebook.Label
import kotlinx.serialization.Serializable

/** The labels as Audio Logs keeps them on the server, in its own data (`appData("labels")`), version 1. */
@Serializable
internal data class ServerLabelsV1(val v: Int = 1, val labels: List<ServerLabel> = emptyList())

@Serializable
internal data class ServerLabel(val id: String, val name: String, val color: Int = 0, val locked: Boolean = false) {
    fun toLabel() = Label(id, name, color, locked)
}

internal fun Label.toServer() = ServerLabel(id, name, color, locked)

/**
 * The labels after merging this phone's ([local]) with the server's ([server]), from the list both had at the last
 * sync ([base]; null before the first): a label changed or added on either side is kept (this phone's change when both
 * changed it), one deleted on either side since the base is gone. In this phone's order, then the server's new ones.
 */
fun mergeLabels(base: List<Label>?, local: List<Label>, server: List<Label>): List<Label> {
    val serverById = server.associateBy { it.id }
    val localById = local.associateBy { it.id }
    if (base == null) return local + server.filter { it.id !in localById }
    val baseById = base.associateBy { it.id }
    val merged = mutableListOf<Label>()
    for (l in local) {
        val was = baseById[l.id]
        val there = serverById[l.id]
        when {
            was == null -> merged += l // added here (or on both: this phone's)
            there == null -> {} // deleted on the server
            l != was -> merged += l // changed here
            else -> merged += there // unchanged here: the server's, changed or not
        }
    }
    for (s in server) {
        if (s.id in localById) continue
        if (s.id !in baseById) merged += s // added on the server; one in the base and gone here was deleted here
    }
    return merged
}
