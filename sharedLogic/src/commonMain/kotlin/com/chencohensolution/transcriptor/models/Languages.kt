package com.chencohensolution.transcriptor.models

/**
 * The languages the app can transcribe: every language Whisper knows (its multilingual models cover them all), as
 * BCP-47 tags. Some have models of their own in [ModelCatalog] as well (Hebrew's ivrit.ai models), listed first by
 * [Languages.ordered].
 */
object Languages {
    /** Whisper's languages, in its own order (by how much of each it was trained on). */
    val whisper = listOf(
        "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar", "sv", "it", "id", "hi", "fi",
        "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no", "th", "ur", "hr", "bg", "lt", "la", "mi", "ml",
        "cy", "sk", "te", "fa", "lv", "bn", "sr", "az", "sl", "kn", "et", "mk", "br", "eu", "is", "hy", "ne", "mn", "bs",
        "kk", "sq", "sw", "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc", "ka", "be", "tg", "sd", "gu", "am",
        "yi", "lo", "uz", "fo", "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl", "mg", "as", "tt", "haw", "ln",
        "ha", "ba", "jw", "su", "yue",
    )

    /** Every language the app can transcribe. */
    val all: List<String> = (ModelCatalog.all.flatMap { it.languages }.filter { it != SpeechModel.MULTILINGUAL } + whisper).distinct()

    /** The languages with models made for them, not only the multilingual ones. */
    val withOwnModels: Set<String> get() = ModelCatalog.all.flatMap { it.languages }.filter { it != SpeechModel.MULTILINGUAL }.toSet()

    /**
     * A tag as the app keeps it: the language alone, lower case, with the old codes Android and Java still give
     * ("iw" for Hebrew, "in" for Indonesian, "ji" for Yiddish) made current.
     */
    fun normalize(tag: String): String = when (val language = tag.substringBefore('-').substringBefore('_').lowercase()) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        "nb" -> "no"
        else -> language
    }

    /** The language a new notebook (or a lecture in none, before any) is in: the phone's first one the app knows, else English. */
    fun default(phone: List<String>): String = phone.map(::normalize).firstOrNull { it in all } ?: "en"

    /**
     * The languages to offer, most likely first: the [phone]'s, then the ones already [used] (notebooks), then those
     * with models of their own, then the rest in Whisper's order.
     */
    fun ordered(phone: List<String>, used: List<String> = emptyList()): List<String> =
        (phone.map(::normalize).filter { it in all } + used + all.filter { it in withOwnModels } + all).distinct()
}
