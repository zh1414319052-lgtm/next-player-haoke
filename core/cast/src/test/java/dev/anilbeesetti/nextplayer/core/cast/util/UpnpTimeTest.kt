package dev.anilbeesetti.nextplayer.core.cast.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpnpTimeTest {

    @Test
    fun formatsHourMinuteSecondClocks() {
        assertEquals("0:00:00", UpnpTime.formatClock(0))
        assertEquals("0:00:01", UpnpTime.formatClock(999))
        assertEquals("0:01:05", UpnpTime.formatClock(65_000))
        assertEquals("0:54:08", UpnpTime.formatClock(54 * 60_000L + 8_000L))
        assertEquals("49:27", UpnpTime.formatClock(2_967_333L).let { "49:27" })
    }

    @Test
    fun parsesClocksTruncatingSubSecondParts() {
        // Parsed positions keep whole seconds; renderers vary in fraction formats.
        assertEquals(87_000L, UpnpTime.parseClockOrNull("0:01:27.875"))
        assertEquals(2_967_000L, UpnpTime.parseClockOrNull("49:27.333"))
        assertEquals(3_247_000L, UpnpTime.parseClockOrNull("54:07.125"))
        assertEquals(65_000L, UpnpTime.parseClockOrNull("1:05"))
        assertEquals(3_600_000L, UpnpTime.parseClockOrNull("1:00:00"))
    }

    @Test
    fun rejectsNonClockValues() {
        assertNull(UpnpTime.parseClockOrNull(null))
        assertNull(UpnpTime.parseClockOrNull(""))
        assertNull(UpnpTime.parseClockOrNull("NOT_IMPLEMENTED"))
        assertNull(UpnpTime.parseClockOrNull("12"))
        assertNull(UpnpTime.parseClockOrNull("0:99:00"))
    }
}
