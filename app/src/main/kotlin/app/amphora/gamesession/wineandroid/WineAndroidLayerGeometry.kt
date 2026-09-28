package app.amphora.gamesession.wineandroid

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Flattens the HWND view tree into SurfaceFlinger layer placements.
 *
 * Every window's buffer is a child layer of one container SurfaceView, so the
 * stacking has to be explicit: SurfaceFlinger orders sibling SurfaceViews by
 * sub-layer and then creation order, not by the view hierarchy, which is how a
 * media-overlay Vulkan view ended up above every GDI dialog.
 *
 * [Node.frame] is the view's frame relative to its parent view (host px); roots
 * are relative to the container. [place] assigns z in pre-order (a parent below
 * its children, siblings in drawing order), maps each buffer onto its on-screen
 * frame, and clips it to every ancestor the way FrameLayout clips child views.
 */
internal object WineAndroidLayerGeometry {
    /** Half-open integer rect; plain Kotlin so JVM tests need no Android stubs. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val isEmpty: Boolean get() = width <= 0 || height <= 0

        fun offset(dx: Int, dy: Int) = Box(left + dx, top + dy, right + dx, bottom + dy)

        fun intersect(other: Box) =
            Box(max(left, other.left), max(top, other.top), min(right, other.right), min(bottom, other.bottom))
    }

    data class Node<K>(
        val key: K,
        val frame: Box,
        val bufferWidth: Int,
        val bufferHeight: Int,
        val children: List<Node<K>> = emptyList(),
    )

    /**
     * [frame] is where the whole buffer maps (container px), [destination] the
     * part of it left after clipping, [source] that part in buffer px; z starts at 1.
     */
    data class Placement<K>(val key: K, val z: Int, val frame: Box, val source: Box, val destination: Box)

    fun <K> place(roots: List<Node<K>>): List<Placement<K>> {
        val out = mutableListOf<Placement<K>>()
        var z = 1
        fun visit(node: Node<K>, originX: Int, originY: Int, clip: Box?) {
            val frame = node.frame.offset(originX, originY)
            val visible = if (clip == null) frame else frame.intersect(clip)
            if (!frame.isEmpty && !visible.isEmpty && node.bufferWidth > 0 && node.bufferHeight > 0) {
                out += Placement(node.key, z++, frame, sourceFor(frame, visible, node), visible)
            }
            for (child in node.children) visit(child, frame.left, frame.top, visible)
        }
        for (root in roots) visit(root, 0, 0, null)
        return out
    }

    /** The part of the buffer that lands in [visible], given the whole buffer maps to [frame]. */
    private fun <K> sourceFor(frame: Box, visible: Box, node: Node<K>): Box {
        val sx = node.bufferWidth.toFloat() / frame.width
        val sy = node.bufferHeight.toFloat() / frame.height
        return Box(
            ((visible.left - frame.left) * sx).roundToInt(),
            ((visible.top - frame.top) * sy).roundToInt(),
            ((visible.right - frame.left) * sx).roundToInt(),
            ((visible.bottom - frame.top) * sy).roundToInt(),
        )
    }
}
