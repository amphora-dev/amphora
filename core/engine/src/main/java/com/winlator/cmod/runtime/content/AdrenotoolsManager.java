package com.winlator.cmod.runtime.content;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.shared.io.FileUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * Minimal AdrenotoolsManager (RFC D8).
 *
 * Only the surface that {@code vulkan.c}'s JNI {@code FindClass} callback
 * requires is retained: the {@code (Context)} constructor and
 * {@link #getLibraryName(String)}. The multi-driver management surface
 * (install/remove/enumerate/setDriverById/reloadContainers/extractDriverFromResources
 * + the {@code feature.settings.GraphicsDriverConfigUtils} reverse dependency)
 * is removed -- MVP ships a single fixed Turnip driver (RFC §4 isolation, D8).
 *
 * The single bundled driver is pre-installed to {@code contents/adrenotools/<id>/}
 * during rootfs/content setup (P2); this class only reports its library name to
 * the native renderer at runtime.
 */
public class AdrenotoolsManager {

    private final File adrenotoolsContentDir;

    public AdrenotoolsManager(Context context) {
        this.adrenotoolsContentDir = new File(context.getFilesDir(), "contents/adrenotools");
        if (!adrenotoolsContentDir.exists()) {
            adrenotoolsContentDir.mkdirs();
        }
    }

    public String getLibraryName(String adrenoToolsDriverId) {
        String libraryName = "";
        try {
            File metaProfile = new File(adrenotoolsContentDir, adrenoToolsDriverId + "/meta.json");
            String jsonStr = FileUtils.readString(metaProfile);
            JSONObject jsonObject = new JSONObject(jsonStr != null ? jsonStr : "{}");
            libraryName = jsonObject.getString("libraryName");
        } catch (JSONException e) {
            Log.w("AdrenotoolsManager", "No libraryName in meta.json for driver " + adrenoToolsDriverId);
        }
        return libraryName;
    }
}
