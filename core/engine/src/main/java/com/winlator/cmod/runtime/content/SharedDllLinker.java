package com.winlator.cmod.runtime.content;

import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Atomically binds an immutable component DLL into a Wine prefix.
 *
 * <p>Android's app SELinux domain permits symlinks but denies hard links. Sources must live below
 * {@code files/contents}; making them read-only prevents an ordinary Wine CreateFile/open from
 * writing through a prefix link into the shared component store. Component installers publish new
 * version directories rather than modifying these files in place.
 */
public final class SharedDllLinker {
  private static final String TAG = "SharedDllLinker";

  private SharedDllLinker() {}

  public static boolean link(File contentsRoot, File source, File target) {
    File temporary = null;
    try {
      File canonicalRoot = contentsRoot.getCanonicalFile();
      File canonicalSource = source.getCanonicalFile();
      File canonicalParent = target.getParentFile().getCanonicalFile();
      Path rootPath = canonicalRoot.toPath();
      Path sourcePath = canonicalSource.toPath();

      if (!sourcePath.startsWith(rootPath)
          || !Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(sourcePath)) {
        Log.e(TAG, "Refusing non-component or non-regular DLL source: " + source);
        return false;
      }
      if (!canonicalParent.isDirectory() && !canonicalParent.mkdirs()) {
        Log.e(TAG, "Cannot create DLL target directory: " + canonicalParent);
        return false;
      }

      // Shared component payloads are immutable. A Windows installer can unlink
      // the prefix symlink and create a private file, but cannot truncate the
      // canonical source through it.
      Os.chmod(canonicalSource.getAbsolutePath(), 0444);

      String relativeTarget = canonicalParent.toPath().relativize(sourcePath).toString();
      temporary =
          new File(
              canonicalParent,
              "." + target.getName() + ".amphora-link-" + UUID.randomUUID());
      Files.deleteIfExists(temporary.toPath());
      Os.symlink(relativeTarget, temporary.getAbsolutePath());
      Os.rename(
          temporary.getAbsolutePath(), new File(canonicalParent, target.getName()).getAbsolutePath());
      return Files.isSymbolicLink(target.toPath()) && target.isFile();
    } catch (ErrnoException | IOException | RuntimeException e) {
      Log.e(TAG, "Failed to bind shared DLL " + source + " -> " + target, e);
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary.toPath());
        } catch (IOException ignored) {
        }
      }
      return false;
    }
  }
}
