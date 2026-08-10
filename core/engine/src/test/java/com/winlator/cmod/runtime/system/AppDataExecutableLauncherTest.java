package com.winlator.cmod.runtime.system;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;

import java.io.File;
import java.io.FileOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AppDataExecutableLauncherTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void appPrivateAarch64ElfRunsThroughSystemLinker() throws Exception {
    File filesDir = temporaryFolder.newFolder("files");
    File executable = writeElf(new File(filesDir, "imagefs/usr/bin/box64"), 183);
    String[] command = {executable.getAbsolutePath(), "wine", "game.exe"};

    assertArrayEquals(
        new String[] {
          AppDataExecutableLauncher.SYSTEM_LINKER_64,
          executable.getAbsolutePath(),
          "wine",
          "game.exe"
        },
        AppDataExecutableLauncher.prepare(filesDir, command));
  }

  @Test
  public void systemExecutableIsNotWrapped() throws Exception {
    File filesDir = temporaryFolder.newFolder("files");
    String[] command = {"/system/bin/sh", "-c", "true"};

    assertSame(command, AppDataExecutableLauncher.prepare(filesDir, command));
  }

  @Test
  public void alreadyWrappedCommandIsIdempotent() throws Exception {
    File filesDir = temporaryFolder.newFolder("files");
    String[] command = {
      AppDataExecutableLauncher.SYSTEM_LINKER_64, new File(filesDir, "box64").getAbsolutePath()
    };

    assertSame(command, AppDataExecutableLauncher.prepare(filesDir, command));
  }

  @Test
  public void x86GuestElfIsNotSentToArm64Linker() throws Exception {
    File filesDir = temporaryFolder.newFolder("files");
    File executable = writeElf(new File(filesDir, "wine"), 62);
    String[] command = {executable.getAbsolutePath()};

    assertSame(command, AppDataExecutableLauncher.prepare(filesDir, command));
  }

  @Test
  public void nonElfFileIsNotWrapped() throws Exception {
    File filesDir = temporaryFolder.newFolder("files");
    File script = new File(filesDir, "launcher.sh");
    script.getParentFile().mkdirs();
    try (FileOutputStream output = new FileOutputStream(script)) {
      output.write("#!/system/bin/sh\n".getBytes());
    }
    String[] command = {script.getAbsolutePath()};

    assertSame(command, AppDataExecutableLauncher.prepare(filesDir, command));
  }

  private static File writeElf(File file, int machine) throws Exception {
    file.getParentFile().mkdirs();
    byte[] header = new byte[64];
    header[0] = 0x7f;
    header[1] = 'E';
    header[2] = 'L';
    header[3] = 'F';
    header[4] = 2;
    header[5] = 1;
    header[18] = (byte) (machine & 0xff);
    header[19] = (byte) ((machine >>> 8) & 0xff);
    try (FileOutputStream output = new FileOutputStream(file)) {
      output.write(header);
    }
    return file;
  }
}
