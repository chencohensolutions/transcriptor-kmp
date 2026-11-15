package com.chencohensolution.transcriptor.recording

/** What happens to a moment's improve pass once it stops. */
enum class ImproveAfterStop {
    /** Right away (after the speaker pass, if it runs). */
    Now,

    /** Kept in the queue ([com.chencohensolution.transcriptor.settings.SettingsStore.improvePending]) until the phone charges. */
    WhenCharging,

    /** Not on its own: the user improves it from the moment's menu. */
    No;

    companion object {
        /**
         * A moment whose chosen live model didn't fit in the free memory at the start ([skippedForMemory]) ran live on a
         * smaller one: it is improved as soon as it stops, once the live model is unloaded, charger or not and whatever
         * "improve on its own" says, since the user had asked for the larger model's text. Otherwise "improve on its own"
         * ([autoImprove]) improves it now on the charger, else when the phone next charges. Nothing without lines or
         * without a model to improve with.
         */
        fun of(hasLines: Boolean, hasImproveModel: Boolean, skippedForMemory: Boolean, autoImprove: Boolean, charging: Boolean) = when {
            !hasLines || !hasImproveModel -> No
            skippedForMemory -> Now
            !autoImprove -> No
            charging -> Now
            else -> WhenCharging
        }
    }
}
