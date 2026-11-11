package app.amphora.files

import java.io.File

/** A folder or an executable in a [DirListing]. */
data class DirEntry(val file: File, val bytes: Long = 0) {
    val name: String get() = file.name
}

/**
 * What the add-game browser shows of a directory: folders and `.exe` files,
 * each in natural order. Everything else is only counted, so a folder full
 * of assets doesn't bury the one file that matters.
 */
data class DirListing(
    val dir: File,
    val folders: List<DirEntry>,
    val exes: List<DirEntry>,
    /** Files that are neither folders nor executables. */
    val others: Int,
    /** Dot files and folders. */
    val hidden: Int,
) {
    val isEmpty: Boolean get() = folders.isEmpty() && exes.isEmpty()

    companion object {
        /** Null when [dir] cannot be listed (gone, or no permission). */
        fun read(dir: File): DirListing? = dir.listFiles()?.let { of(dir, it.asList()) }

        fun of(dir: File, children: List<File>): DirListing {
            val folders = ArrayList<DirEntry>()
            val exes = ArrayList<DirEntry>()
            var others = 0
            var hidden = 0
            for (child in children) {
                when {
                    child.name.startsWith('.') -> hidden++
                    child.isDirectory -> folders += DirEntry(child)
                    child.isFile && isExe(child.name) -> exes += DirEntry(child, child.length())
                    else -> others++
                }
            }
            val byName = compareBy(NaturalOrder) { entry: DirEntry -> entry.name }
            return DirListing(dir, folders.sortedWith(byName), exes.sortedWith(byName), others, hidden)
        }

        fun isExe(name: String): Boolean = name.endsWith(".exe", ignoreCase = true)
    }
}

/**
 * File-manager order: case-insensitive, with digit runs compared as numbers,
 * so "Part 2" comes before "Part 10". Ties fall back to plain order, so the
 * comparison stays total.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isAsciiDigit()) i++
                while (j < b.length && b[j].isAsciiDigit()) j++
                val numberA = a.substring(startA, i).trimStart('0')
                val numberB = b.substring(startB, j).trimStart('0')
                if (numberA.length != numberB.length) return numberA.length.compareTo(numberB.length)
                numberA.compareTo(numberB).let { if (it != 0) return it }
            } else {
                a[i].lowercaseChar().compareTo(b[j].lowercaseChar()).let { if (it != 0) return it }
                i++
                j++
            }
        }
        val rest = (a.length - i).compareTo(b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}

/** A place to start browsing: a storage volume, or the sideload directory. */
data class StorageRoot(val label: String, val dir: File, val kind: Kind) {
    enum class Kind {
        Internal,
        Removable,

        /** The app's own external directory: readable without all-files access. */
        Sideload,
    }

    fun contains(file: File): Boolean = file.path == dir.path || file.path.startsWith(dir.path + File.separator)

    companion object {
        /** The root [file] is under; the deepest wins (the sideload directory lives inside internal storage). */
        fun of(roots: List<StorageRoot>, file: File): StorageRoot? =
            roots.filter { it.contains(file) }.maxByOrNull { it.dir.path.length }
    }
}

/** One step of the breadcrumb. */
data class Crumb(val label: String, val dir: File)

/** The path from [root] down to [dir], root first; just [dir] when it is outside every root. */
fun crumbs(root: StorageRoot?, dir: File): List<Crumb> {
    if (root == null || !root.contains(dir)) return listOf(Crumb(dir.path, dir))
    val parts = dir.path.removePrefix(root.dir.path).split(File.separatorChar).filter { it.isNotEmpty() }
    var at = root.dir
    return listOf(Crumb(root.label, root.dir)) +
        parts.map { part ->
            at = File(at, part)
            Crumb(part, at)
        }
}
