package com.chencohensolution.transcriptor.ui

/**
 * Settings with the models chosen in one place: [models] as the app has it (languages, models, downloads, the live
 * and improve choice per language), and the two switches the one "Improve after the lecture" becomes.
 */
data class ModelsStateA(
    val models: ModelsState,
    /** The speaker pass runs when a lecture stops. */
    val findSpeakersOnStop: Boolean,
    /** The improve pass runs on its own after a lecture (it is always offered on the lecture's screen). */
    val autoImprove: Boolean,
)

class ModelsActionsA(
    /** Downloads, the live and improve choices, the folder and the audio, as the app has them. */
    val models: ModelsActions,
    val setFindSpeakersOnStop: (Boolean) -> Unit = {},
    val setAutoImprove: (Boolean) -> Unit = {},
    /** Opens the models library (downloads, Hugging Face), on a language. */
    val openLibrary: (language: String?) -> Unit = {},
)
