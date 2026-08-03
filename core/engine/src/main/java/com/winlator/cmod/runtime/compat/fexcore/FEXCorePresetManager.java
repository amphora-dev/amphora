package com.winlator.cmod.runtime.compat.fexcore;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.runtime.wine.EnvVars;
import java.util.Iterator;

/** Resolves FEXCore preset IDs to env vars for guest launch (MVP: no preset UI/CRUD). */
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
}
