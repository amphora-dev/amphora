package app.amphora.core.content

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Publishes a fully written file without exposing a partially copied replacement.
 *
 * Source and destination are deliberately created in the same directory by callers,
 * so supported Android/Linux filesystems implement this as one atomic rename. If a
 * filesystem cannot provide that guarantee, publication fails and the old destination
 * remains available.
 */
internal object AtomicFilePublisher {
    fun replace(source: File, destination: File) {
        require(source.isFile) { "Replacement source is missing: $source" }
        require(source.parentFile?.canonicalFile == destination.parentFile?.canonicalFile) {
            "Atomic replacement requires source and destination in the same directory"
        }
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
