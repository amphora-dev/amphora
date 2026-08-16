package app.amphora.gamesession.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadFingerTrackerTest {
    private fun tracker() = TouchpadFingerTracker<String>(capacity = 4)

    @Test
    fun firstContactWithNonZeroPointerIdIsRankZero() {
        val t = tracker()

        assertTrue(t.put(2, "a"))
        assertTrue(t.put(3, "b"))

        assertEquals(0, t.rankOf(2))
        assertEquals(1, t.rankOf(3))
        assertSame("a", t.first())
        assertEquals(2, t.count)
    }

    @Test
    fun putRefusesNewIdsAtCapacityButReplacesKnownIds() {
        val t = tracker()

        assertTrue(t.put(1, "a"))
        assertTrue(t.put(2, "b"))
        assertTrue(t.put(3, "c"))
        assertTrue(t.put(7, "d"))
        assertFalse(t.put(9, "e"))
        assertEquals(4, t.count)

        // Re-tracking a stale id replaces the entry instead of growing past capacity.
        assertTrue(t.put(2, "b2"))
        assertSame("b2", t[2])
        assertEquals(4, t.count)
    }

    @Test
    fun removeKeepsTouchOrderOfRemainingFingers() {
        val t = tracker()

        t.put(2, "a")
        t.put(3, "b")
        t.put(5, "c")

        assertSame("a", t.remove(2))
        assertNull(t.remove(2))
        assertEquals(2, t.count)

        assertEquals(0, t.rankOf(3))
        assertEquals(1, t.rankOf(5))
        assertSame("b", t.first())
    }

    @Test
    fun rankOfUnknownIdIsMinusOne() {
        val t = tracker()

        assertEquals(-1, t.rankOf(0))

        t.put(4, "a")
        assertEquals(-1, t.rankOf(0))
    }

    @Test
    fun entriesByIdSortsByIdRegardlessOfTouchOrder() {
        val t = tracker()

        t.put(3, "c")
        t.put(1, "a")
        t.put(2, "b")

        assertEquals(listOf(1 to "a", 2 to "b", 3 to "c"), t.entriesById())
    }

    @Test
    fun otherByLowestIdSkipsTheGivenFinger() {
        val t = tracker()

        t.put(3, "c")
        t.put(1, "a")
        t.put(2, "b")

        assertSame("a", t.otherByLowestId("c"))
        assertSame("a", t.otherByLowestId("b"))
        assertSame("b", t.otherByLowestId("a"))
    }

    @Test
    fun otherByLowestIdReturnsNullForUntrackedFingerWhenAlone() {
        val t = tracker()

        t.put(3, "only")

        assertNull(t.otherByLowestId("only"))
        // An untracked probe must not match any tracked entry.
        assertSame("only", t.otherByLowestId("untracked"))
    }

    @Test
    fun snapshotIteratesTouchOrderAndSurvivesMutation() {
        val t = tracker()

        t.put(2, "a")
        t.put(1, "b")

        val snapshot = t.snapshot()
        t.clear()

        assertEquals(listOf(2 to "a", 1 to "b"), snapshot)
        assertTrue(t.isEmpty())
        assertEquals(0, t.count)
    }

    @Test
    fun clearResetsAllState() {
        val t = tracker()

        t.put(1, "a")
        t.put(2, "b")
        t.clear()

        assertTrue(t.isEmpty())
        assertNull(t[1])
        assertFalse(t.contains(2))
        assertEquals(-1, t.rankOf(1))
        assertNull(t.first())
    }

    @Test
    fun allReflectsEveryTrackedFinger() {
        val t = tracker()

        assertTrue(t.all { true })
        t.put(1, "a")
        t.put(2, "b")
        assertTrue(t.all { it != "z" })
        assertFalse(t.all { it == "a" })
    }
}
