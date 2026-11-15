package com.chencohensolution.transcriptor

import com.chencohensolution.transcriptor.recording.ImproveAfterStop
import com.chencohensolution.transcriptor.recording.ImproveAfterStop.No
import com.chencohensolution.transcriptor.recording.ImproveAfterStop.Now
import com.chencohensolution.transcriptor.recording.ImproveAfterStop.WhenCharging
import kotlin.test.Test
import kotlin.test.assertEquals

class ImproveAfterStopTest {
    private fun of(lines: Boolean = true, model: Boolean = true, skipped: Boolean = false, auto: Boolean = false, charging: Boolean = false) =
        ImproveAfterStop.of(lines, model, skipped, auto, charging)

    @Test
    fun aMomentWhoseLiveModelDidntFitIsImprovedAsSoonAsItStops() {
        // Large-v3 chosen live, refused for memory at the start, Zipformer ran instead: improved at Stop, charger or not.
        assertEquals(Now, of(skipped = true))
        assertEquals(Now, of(skipped = true, auto = true, charging = false))
        // Nothing to improve, or nothing to improve with.
        assertEquals(No, of(skipped = true, lines = false))
        assertEquals(No, of(skipped = true, model = false))
    }

    @Test
    fun otherwiseImprovingOnItsOwnWaitsForTheCharger() {
        assertEquals(No, of())
        assertEquals(Now, of(auto = true, charging = true))
        assertEquals(WhenCharging, of(auto = true, charging = false))
        assertEquals(No, of(auto = true, charging = true, lines = false))
    }
}
