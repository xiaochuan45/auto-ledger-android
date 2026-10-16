package io.github.xiaochuan45.autoledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountsTest {
    @Test fun `parse decimal money exactly without rounding`() {
        assertEquals(2350L, Amounts.parseMinor("23.50"))
        assertEquals(2310L, Amounts.parseMinor(" 23.1 "))
        assertEquals(2300L, Amounts.parseMinor("23"))
        assertEquals(1L, Amounts.parseMinor("0.01"))
        assertEquals(0L, Amounts.parseMinor("0"))
        assertEquals(Long.MAX_VALUE, Amounts.parseMinor("92233720368547758.07"))
    }

    @Test fun `reject unsafe or ambiguous amount input`() {
        listOf("-1", "+1", "1.001", "1.999", "1.", ".5", "NaN", "Infinity", "1e2", "1,000", "¥23.50", "23元", "", "1 2", "92233720368547758.08", "92233720368547759", "999999999999999999999999999999999999").forEach {
            assertNull("Must reject $it", Amounts.parseMinor(it))
        }
    }

    @Test fun `format is exact with two fraction digits even at integer boundaries`() {
        assertEquals("0.00", Amounts.formatMinor(0))
        assertEquals("0.01", Amounts.formatMinor(1))
        assertEquals("23.50", Amounts.formatMinor(2350))
        assertEquals("-0.01", Amounts.formatMinor(-1))
        assertEquals("92233720368547758.07", Amounts.formatMinor(Long.MAX_VALUE))
        assertEquals("-92233720368547758.08", Amounts.formatMinor(Long.MIN_VALUE))
    }
}
