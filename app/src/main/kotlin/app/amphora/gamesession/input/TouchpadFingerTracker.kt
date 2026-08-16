package app.amphora.gamesession.input

/**
 * Multi-touch finger bookkeeping keyed by MotionEvent pointer id.
 *
 * Replaces the WinNative pattern of a fixed `arrayOfNulls<Finger>(4)` indexed
 * by raw pointer id. Pointer ids are neither dense nor zero-based: a stylus or
 * palm can claim id 0 (see [TouchpadView.setPointerIdsToIgnore]), so positional
 * reads like `fingers[0]` / `fingers[1]` degraded — or threw — once the first
 * contact received a non-zero id. Keying by id while preserving touch order
 * lets callers express "first finger down" / "second finger down" instead of
 * praying for ids 0 and 1.
 */
internal class TouchpadFingerTracker<F>(private val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val fingers = LinkedHashMap<Int, F>()

    /** Number of currently tracked fingers. */
    val count: Int
        get() = fingers.size

    fun isEmpty(): Boolean = fingers.isEmpty()

    operator fun get(id: Int): F? = fingers[id]

    fun contains(id: Int): Boolean = fingers.containsKey(id)

    /**
     * Track [finger] under [id]. Re-tracking a known id replaces the stale
     * entry and succeeds; a previously unseen id is refused once [capacity]
     * fingers are tracked.
     */
    fun put(id: Int, finger: F): Boolean {
        if (!fingers.containsKey(id) && fingers.size >= capacity) return false
        fingers[id] = finger
        return true
    }

    fun remove(id: Int): F? = fingers.remove(id)

    fun clear() {
        fingers.clear()
    }

    /** 0-based touch order (insertion order) of [id]; -1 when not tracked. */
    fun rankOf(id: Int): Int {
        var rank = 0
        for (key in fingers.keys) {
            if (key == id) return rank
            rank++
        }
        return -1
    }

    /** Earliest still-tracked finger. */
    fun first(): F? = fingers.values.firstOrNull()

    /**
     * Snapshot in ascending pointer-id order — the iteration order of the old
     * id-indexed array, which pair-sensitive consumers (two-finger scroll
     * direction) rely on.
     */
    fun entriesById(): List<Pair<Int, F>> = fingers.entries.sortedBy { it.key }.map { it.key to it.value }

    /** Tracked finger with the lowest pointer id that is not [finger]. */
    fun otherByLowestId(finger: F): F? = fingers.entries.sortedBy { it.key }.firstOrNull { it.value !== finger }?.value

    fun all(predicate: (F) -> Boolean): Boolean = fingers.values.all(predicate)

    /** Ordered copy safe to iterate while the tracker is mutated. */
    fun snapshot(): List<Pair<Int, F>> = fingers.toList()
}
