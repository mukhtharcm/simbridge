package io.github.mukhtharcm.simbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmTest {
    @Test fun rmsOfSilenceAndOfAConstant() {
        assertEquals(0, Pcm.rms(ShortArray(320)))
        assertEquals(1000, Pcm.rms(ShortArray(320) { 1000 }))
    }

    @Test fun stereoRepeatsEachSample() {
        assertEquals(listOf<Short>(1, 1, 2, 2), Pcm.toStereo(shortArrayOf(1, 2)).toList())
    }

    @Test fun onlyPlainNumbersCanBeDialled() {
        assertTrue(Rules.dialable("+911234567890"))
        assertTrue(Rules.dialable("100"))
        for (bad in listOf("**21*123#", "*123#", "12", "+91 12345", "1234;rm", "")) assertFalse(bad, Rules.dialable(bad))
    }

    @Test fun smsTextMustBeNonEmptyAndBounded() {
        assertTrue(Rules.sendable("hi"))
        assertFalse(Rules.sendable("  "))
        assertFalse(Rules.sendable("x".repeat(1531)))
    }

    @Test fun tokenComesFromTheV0PathOnly() {
        assertEquals("abc", Rules.tokenIn("/v0?token=abc"))
        assertEquals("abc", Rules.tokenIn("/v0?x=1&token=abc"))
        assertNull(Rules.tokenIn("/v0"))
        assertNull(Rules.tokenIn("/other?token=abc"))
        assertTrue(Rules.sameToken("abc", "abc"))
        assertFalse(Rules.sameToken("abc", "abd"))
    }

    @Test fun jitterBufferWaitsThenPlaysInOrderAndCountsGaps() {
        val b = JitterBuffer(prebuffer = 2, capacity = 3)
        val f = (1..5).map { ShortArray(1) { _ -> it.toShort() } }
        b.put(f[0])
        assertNull("waits for the prebuffer", b.take())
        b.put(f[1])
        assertEquals(1, b.take()!![0].toInt())
        assertEquals(2, b.take()!![0].toInt())
        assertNull("ran dry", b.take())
        assertEquals(1, b.gaps)
        b.put(f[2])
        assertNull("waits again after running dry", b.take())
        b.put(f[3]); b.put(f[4]); b.put(f[0])
        assertEquals("oldest frame dropped at capacity", 1, b.dropped)
        assertEquals(4, b.take()!![0].toInt())
    }
}
