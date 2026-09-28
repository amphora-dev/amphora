package app.amphora.gamesession.wineandroid

import app.amphora.gamesession.wineandroid.WineAndroidLayerGeometry.Box
import app.amphora.gamesession.wineandroid.WineAndroidLayerGeometry.Node
import org.junit.Assert.assertEquals
import org.junit.Test

class WineAndroidLayerGeometryTest {
    @Test
    fun zFollowsPreOrderSoLaterSiblingsAndChildrenStackOnTop() {
        val desktop = Node("desktop", Box(0, 0, 200, 100), 100, 50)
        val game = Node("game", Box(20, 20, 120, 80), 50, 30)
        val gameClient = Node("game-client", Box(22, 30, 118, 78), 48, 24)
        val dialog =
            Node(
                "dialog",
                Box(60, 40, 160, 90),
                50,
                25,
                children = listOf(Node("ok-button", Box(10, 10, 30, 20), 10, 5)),
            )

        val placed = WineAndroidLayerGeometry.place(listOf(desktop, game, gameClient, dialog))

        assertEquals(
            listOf("desktop" to 1, "game" to 2, "game-client" to 3, "dialog" to 4, "ok-button" to 5),
            placed.map { it.key to it.z },
        )
    }

    @Test
    fun wholeBufferScalesOntoTheViewFrame() {
        val placed = WineAndroidLayerGeometry.place(listOf(Node("w", Box(10, 20, 210, 120), 100, 50)))

        assertEquals(Box(0, 0, 100, 50), placed.single().source)
        assertEquals(Box(10, 20, 210, 120), placed.single().destination)
    }

    @Test
    fun childrenAreOffsetByTheirParentAndClippedToIt() {
        // Parent at (100,100) 200x100 host px; the child hangs 50 px past its right edge.
        val child = Node("child", Box(150, 10, 250, 60), 50, 25)
        val parent = Node("parent", Box(100, 100, 300, 200), 100, 50, children = listOf(child))

        val placed = WineAndroidLayerGeometry.place(listOf(parent)).associateBy { it.key }

        assertEquals(Box(250, 110, 300, 160), placed.getValue("child").destination)
        assertEquals(Box(250, 110, 350, 160), placed.getValue("child").frame)
        // The left half of the child buffer survives the clip.
        assertEquals(Box(0, 0, 25, 25), placed.getValue("child").source)
    }

    @Test
    fun fullyClippedOrEmptyNodesGetNoLayerButVisibleChildrenStillDo() {
        val outside = Node("outside", Box(500, 500, 600, 600), 10, 10)
        val zeroSized = Node("zero", Box(0, 0, 0, 0), 2, 2)
        val noBuffer =
            Node("no-buffer", Box(0, 0, 50, 50), 0, 0, children = listOf(Node("kid", Box(0, 0, 10, 10), 5, 5)))
        val root = Node("root", Box(0, 0, 100, 100), 50, 50, children = listOf(outside, zeroSized, noBuffer))

        val placed = WineAndroidLayerGeometry.place(listOf(root))

        assertEquals(listOf("root" to 1, "kid" to 2), placed.map { it.key to it.z })
    }
}
