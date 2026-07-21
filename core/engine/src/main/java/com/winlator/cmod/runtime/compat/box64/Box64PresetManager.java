package com.winlator.cmod.runtime.compat.box64;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.runtime.wine.EnvVars;
import java.util.Iterator;
import java.util.Locale;

/** Resolves Box64 preset IDs to env vars for guest launch (MVP: no preset UI/CRUD). */
public abstract class Box64PresetManager {
  private static final String TAG = "Box64PresetManager";

  public static EnvVars getEnvVars(String prefix, Context context, String id) {
    String ucPrefix = prefix.toUpperCase(Locale.ENGLISH);
    EnvVars envVars = new EnvVars();

    if (id.equals(Box64Preset.STABILITY)) {
      envVars.put(ucPrefix + "_DYNAREC_SAFEFLAGS", "2");
      envVars.put(ucPrefix + "_DYNAREC_FASTNAN", "0");
      envVars.put(ucPrefix + "_DYNAREC_FASTROUND", "0");
      envVars.put(ucPrefix + "_DYNAREC_X87DOUBLE", "1");
      envVars.put(ucPrefix + "_DYNAREC_BIGBLOCK", "0");
      envVars.put(ucPrefix + "_DYNAREC_STRONGMEM", "2");
      envVars.put(ucPrefix + "_DYNAREC_FORWARD", "128");
      envVars.put(ucPrefix + "_DYNAREC_CALLRET", "0");
      envVars.put(ucPrefix + "_DYNAREC_WAIT", "0");
      if (ucPrefix.equals("BOX64")) {
        envVars.put("BOX64_AVX", "0");
        envVars.put("BOX64_UNITYPLAYER", "1");
        envVars.put("BOX64_MMAP32", "0");
        envVars.put("BOX64_DYNACACHE", "0");
      }
    } else if (id.equals(Box64Preset.COMPATIBILITY)) {
      envVars.put(ucPrefix + "_DYNAREC_SAFEFLAGS", "2");
      envVars.put(ucPrefix + "_DYNAREC_FASTNAN", "0");
      envVars.put(ucPrefix + "_DYNAREC_FASTROUND", "0");
      envVars.put(ucPrefix + "_DYNAREC_X87DOUBLE", "1");
      envVars.put(ucPrefix + "_DYNAREC_BIGBLOCK", "0");
      envVars.put(ucPrefix + "_DYNAREC_STRONGMEM", "1");
      envVars.put(ucPrefix + "_DYNAREC_FORWARD", "128");
      envVars.put(ucPrefix + "_DYNAREC_CALLRET", "0");
      envVars.put(ucPrefix + "_DYNAREC_WAIT", "1");
      if (ucPrefix.equals("BOX64")) {
        envVars.put("BOX64_AVX", "0");
        envVars.put("BOX64_UNITYPLAYER", "1");
        envVars.put("BOX64_MMAP32", "0");
        envVars.put("BOX64_DYNACACHE", "0");
      }
    } else if (id.equals(Box64Preset.INTERMEDIATE)) {
      envVars.put(ucPrefix + "_DYNAREC_SAFEFLAGS", "2");
      envVars.put(ucPrefix + "_DYNAREC_FASTNAN", "1");
      envVars.put(ucPrefix + "_DYNAREC_FASTROUND", "0");
      envVars.put(ucPrefix + "_DYNAREC_X87DOUBLE", "1");
      envVars.put(ucPrefix + "_DYNAREC_BIGBLOCK", "2");
      envVars.put(ucPrefix + "_DYNAREC_STRONGMEM", "0");
      envVars.put(ucPrefix + "_DYNAREC_FORWARD", "256");
      envVars.put(ucPrefix + "_DYNAREC_CALLRET", "1");
      envVars.put(ucPrefix + "_DYNAREC_WAIT", "1");
      if (ucPrefix.equals("BOX64")) {
        envVars.put("BOX64_AVX", "0");
        envVars.put("BOX64_UNITYPLAYER", "0");
        envVars.put("BOX64_MMAP32", "1");
        envVars.put("BOX64_DYNACACHE", "0");
      }
    } else if (id.equals(Box64Preset.PERFORMANCE)) {
      envVars.put(ucPrefix + "_DYNAREC_SAFEFLAGS", "1");
      envVars.put(ucPrefix + "_DYNAREC_FASTNAN", "1");
      envVars.put(ucPrefix + "_DYNAREC_FASTROUND", "1");
      envVars.put(ucPrefix + "_DYNAREC_X87DOUBLE", "0");
      envVars.put(ucPrefix + "_DYNAREC_BIGBLOCK", "3");
      envVars.put(ucPrefix + "_DYNAREC_STRONGMEM", "0");
      envVars.put(ucPrefix + "_DYNAREC_FORWARD", "512");
      envVars.put(ucPrefix + "_DYNAREC_CALLRET", "1");
      envVars.put(ucPrefix + "_DYNAREC_WAIT", "1");
      if (ucPrefix.equals("BOX64")) {
        envVars.put("BOX64_AVX", "0");
        envVars.put("BOX64_UNITYPLAYER", "0");
        envVars.put("BOX64_MMAP32", "1");
        envVars.put("BOX64_DYNACACHE", "0");
      }
    } else if (id.startsWith(Box64Preset.CUSTOM)) {
      for (String[] preset : customPresetsIterator(prefix, context)) {
        if (preset[0].equals(id)) {
          envVars.putAll(preset[2]);
          break;
        }
      }
    }

    Log.d(
        TAG,
        "getEnvVars resolved prefix='"
            + prefix
            + "' presetId='"
            + id
            + "' -> envVars='"
            + envVars.toString()
            + "'");
    return envVars;
  }

  private static Iterable<String[]> customPresetsIterator(String prefix, Context context) {
    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
    final String customPresetsStr = preferences.getString(prefix + "_custom_presets", "");
    final String[] customPresets = customPresetsStr.split(",");
    final int[] index = {0};
    return () ->
        new Iterator<String[]>() {
          @Override
          public boolean hasNext() {
            return index[0] < customPresets.length && !customPresetsStr.isEmpty();
          }

          @Override
          public String[] next() {
            return customPresets[index[0]++].split("\\|");
          }
        };
  }
}
