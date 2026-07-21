package com.winlator.cmod.runtime.compat.fexcore;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.runtime.wine.EnvVars;
import java.util.ArrayList;
import java.util.Iterator;

public class FEXCorePresetManager {
  private static final String TAG = "FEXCorePresetManager";

  public static EnvVars getEnvVars(Context context, String id) {
    EnvVars envVars = new EnvVars();

    if (id.equals(FEXCorePreset.STABILITY)) {
      envVars.put("FEX_TSOENABLED", "1");
      envVars.put("FEX_VECTORTSOENABLED", "1");
      envVars.put("FEX_MEMCPYSETTSOENABLED", "1");
      envVars.put("FEX_HALFBARRIERTSOENABLED", "1");
      envVars.put("FEX_X87REDUCEDPRECISION", "0");
      envVars.put("FEX_MULTIBLOCK", "0");
    } else if (id.equals(FEXCorePreset.COMPATIBILITY)) {
      envVars.put("FEX_TSOENABLED", "1");
      envVars.put("FEX_VECTORTSOENABLED", "1");
      envVars.put("FEX_MEMCPYSETTSOENABLED", "1");
      envVars.put("FEX_HALFBARRIERTSOENABLED", "1");
      envVars.put("FEX_X87REDUCEDPRECISION", "0");
      envVars.put("FEX_MULTIBLOCK", "1");
    } else if (id.equals(FEXCorePreset.INTERMEDIATE)) {
      envVars.put("FEX_TSOENABLED", "1");
      envVars.put("FEX_VECTORTSOENABLED", "0");
      envVars.put("FEX_MEMCPYSETTSOENABLED", "0");
      envVars.put("FEX_HALFBARRIERTSOENABLED", "1");
      envVars.put("FEX_X87REDUCEDPRECISION", "1");
      envVars.put("FEX_MULTIBLOCK", "1");
    } else if (id.equals(FEXCorePreset.PERFORMANCE)) {
      envVars.put("FEX_TSOENABLED", "0");
      envVars.put("FEX_VECTORTSOENABLED", "0");
      envVars.put("FEX_MEMCPYSETTSOENABLED", "0");
      envVars.put("FEX_HALFBARRIERTSOENABLED", "0");
      envVars.put("FEX_X87REDUCEDPRECISION", "1");
      envVars.put("FEX_MULTIBLOCK", "1");
    } else if (id.startsWith(FEXCorePreset.CUSTOM)) {
      for (String[] preset : customPresetsIterator(context)) {
        if (preset[0].equals(id)) {
          envVars.putAll(preset[2]);
          break;
        }
      }
    }

    normalizeSmcChecksEnvVars(envVars);
    Log.d(TAG, "getEnvVars resolved presetId='" + id + "' -> envVars='" + envVars.toString() + "'");
    return envVars;
  }

  public static void normalizeSmcChecksEnvVars(EnvVars envVars) {
    normalizeSmcChecksEnvVars(envVars, null);
  }

  public static void normalizeSmcChecksEnvVars(EnvVars envVars, EnvVars preferredEnvVars) {
    String smcChecks = envVars.get("FEX_SMCCHECKS");
    String legacySmcChecks = envVars.get("FEX_SMC_CHECKS");
    if (preferredEnvVars != null) {
      String preferredSmcChecks = preferredEnvVars.get("FEX_SMCCHECKS");
      String preferredLegacySmcChecks = preferredEnvVars.get("FEX_SMC_CHECKS");
      if (!preferredSmcChecks.isEmpty()) {
        smcChecks = preferredSmcChecks;
      } else if (!preferredLegacySmcChecks.isEmpty()) {
        smcChecks = preferredLegacySmcChecks;
      }
    }
    if (smcChecks.isEmpty()) {
      smcChecks = legacySmcChecks;
    }
    if (!smcChecks.isEmpty()) {
      envVars.put("FEX_SMCCHECKS", smcChecks);
      envVars.put("FEX_SMC_CHECKS", smcChecks);
    }
  }

  public static ArrayList<FEXCorePreset> getPresets(Context context) {
    ArrayList<FEXCorePreset> presets = new ArrayList<>();
    // Hardcoded labels — amphora has no Winlator string resources for these presets.
    presets.add(new FEXCorePreset(FEXCorePreset.STABILITY, "Stability"));
    presets.add(new FEXCorePreset(FEXCorePreset.COMPATIBILITY, "Compatibility"));
    presets.add(new FEXCorePreset(FEXCorePreset.INTERMEDIATE, "Intermediate"));
    presets.add(new FEXCorePreset(FEXCorePreset.PERFORMANCE, "Performance"));
    for (String[] preset : customPresetsIterator(context))
      presets.add(new FEXCorePreset(preset[0], preset[1]));
    return presets;
  }

  public static FEXCorePreset getPreset(Context context, String id) {
    for (FEXCorePreset preset : getPresets(context)) if (preset.id.equals(id)) return preset;
    return null;
  }

  private static Iterable<String[]> customPresetsIterator(Context context) {
    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
    final String customPresetsStr = preferences.getString("fexcore_custom_presets", "");
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

  public static int getNextPresetId(Context context) {
    int maxId = 0;
    for (String[] preset : customPresetsIterator(context)) {
      maxId = Math.max(maxId, Integer.parseInt(preset[0].replace(FEXCorePreset.CUSTOM + "-", "")));
    }
    return maxId + 1;
  }

  public static void editPreset(Context context, String id, String name, EnvVars envVars) {
    String key = "fexcore_custom_presets";
    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
    String customPresetsStr = preferences.getString(key, "");

    if (id != null) {
      String[] customPresets = customPresetsStr.split(",");
      for (int i = 0; i < customPresets.length; i++) {
        String[] preset = customPresets[i].split("\\|");
        if (preset[0].equals(id)) {
          customPresets[i] = id + "|" + name + "|" + envVars.toString();
          break;
        }
      }
      customPresetsStr = String.join(",", customPresets);
    } else {
      String preset =
          FEXCorePreset.CUSTOM
              + "-"
              + getNextPresetId(context)
              + "|"
              + name
              + "|"
              + envVars.toString();
      customPresetsStr += (!customPresetsStr.isEmpty() ? "," : "") + preset;
    }
    preferences.edit().putString(key, customPresetsStr).apply();
  }

  public static void duplicatePreset(Context context, String id) {
    ArrayList<FEXCorePreset> presets = getPresets(context);
    FEXCorePreset originPreset = null;
    for (FEXCorePreset preset : presets) {
      if (preset.id.equals(id)) {
        originPreset = preset;
        break;
      }
    }
    if (originPreset == null) return;

    String newName;
    for (int i = 1; ; i++) {
      newName = originPreset.name + " (" + i + ")";
      boolean found = false;
      for (FEXCorePreset preset : presets) {
        if (preset.name.equals(newName)) {
          found = true;
          break;
        }
      }
      if (!found) break;
    }

    editPreset(context, null, newName, getEnvVars(context, originPreset.id));
  }

  public static void removePreset(Context context, String id) {
    String key = "fexcore_custom_presets";
    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
    String oldCustomPresetsStr = preferences.getString(key, "");
    String newCustomPresetsStr = "";

    String[] customPresets = oldCustomPresetsStr.split(",");
    for (int i = 0; i < customPresets.length; i++) {
      String[] preset = customPresets[i].split("\\|");
      if (!preset[0].equals(id))
        newCustomPresetsStr += (!newCustomPresetsStr.isEmpty() ? "," : "") + customPresets[i];
    }

    preferences.edit().putString(key, newCustomPresetsStr).apply();
  }
}
