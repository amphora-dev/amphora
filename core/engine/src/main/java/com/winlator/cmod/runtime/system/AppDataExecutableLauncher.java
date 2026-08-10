package com.winlator.cmod.runtime.system;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Routes app-private AArch64 ELF files through Android's platform linker.
 *
 * <p>Apps targeting API 29 or newer cannot directly {@code execve()} writable
 * {@code app_data_file} executables. Executing {@code /system/bin/linker64} and passing the ELF
 * path as its first argument keeps the kernel-visible executable in {@code system_linker_exec}.
 */
final class AppDataExecutableLauncher {
  static final String SYSTEM_LINKER_64 = "/system/bin/linker64";

  private static final int ELF_HEADER_SIZE = 20;
  private static final int ELF_MACHINE_OFFSET = 18;
  private static final int ELF_MACHINE_AARCH64 = 183;

  private AppDataExecutableLauncher() {}

  static String[] prepare(File filesDir, String[] command) {
    if (filesDir == null || command == null || command.length == 0 || command[0] == null) {
      return command;
    }
    if (SYSTEM_LINKER_64.equals(command[0])) return command;

    File executable = new File(command[0]);
    if (!isAppDataPath(filesDir, executable) || !isAarch64Elf(executable)) return command;

    String[] wrapped = new String[command.length + 1];
    wrapped[0] = SYSTEM_LINKER_64;
    System.arraycopy(command, 0, wrapped, 1, command.length);
    return wrapped;
  }

  static boolean isAppDataPath(File directory, File candidate) {
    if (directory == null || candidate == null || !candidate.isAbsolute()) return false;
    try {
      String root = directory.getCanonicalPath();
      String path = candidate.getCanonicalPath();
      return path.equals(root) || path.startsWith(root + File.separator);
    } catch (IOException ignored) {
      String root = directory.getAbsolutePath();
      String path = candidate.getAbsolutePath();
      return path.equals(root) || path.startsWith(root + File.separator);
    }
  }

  private static boolean isAarch64Elf(File executable) {
    byte[] header = new byte[ELF_HEADER_SIZE];
    try (FileInputStream input = new FileInputStream(executable)) {
      int offset = 0;
      while (offset < header.length) {
        int read = input.read(header, offset, header.length - offset);
        if (read < 0) return false;
        offset += read;
      }
    } catch (IOException ignored) {
      return false;
    }

    if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') {
      return false;
    }
    int machine =
        Byte.toUnsignedInt(header[ELF_MACHINE_OFFSET])
            | (Byte.toUnsignedInt(header[ELF_MACHINE_OFFSET + 1]) << 8);
    return machine == ELF_MACHINE_AARCH64;
  }

  static String describe(String[] command) {
    return command == null ? "null" : Arrays.toString(command);
  }
}
