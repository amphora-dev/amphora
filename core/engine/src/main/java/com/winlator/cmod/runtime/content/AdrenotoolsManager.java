package com.winlator.cmod.runtime.content;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.shared.io.FileUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Adrenotools driver registry under {@code filesDir/contents/adrenotools/<id>/}.
 *
 * MVP keeps a single default bundled wrapper install (copied from imagefs by the
 * preparer). Optional packages (e.g. WN-Turnip zip) are installed via
 * {@link #installFromZip(File, String)}.
 */
public class AdrenotoolsManager {

    private static final String TAG = "AdrenotoolsManager";

    private final File adrenotoolsContentDir;

    public AdrenotoolsManager(Context context) {
        this.adrenotoolsContentDir = new File(context.getFilesDir(), "contents/adrenotools");
        if (!adrenotoolsContentDir.exists()) {
            adrenotoolsContentDir.mkdirs();
        }
    }

    public File getDriverDir(String adrenoToolsDriverId) {
        return new File(adrenotoolsContentDir, adrenoToolsDriverId);
    }

    public boolean isInstalled(String adrenoToolsDriverId) {
        File dir = getDriverDir(adrenoToolsDriverId);
        File meta = new File(dir, "meta.json");
        String libraryName = getLibraryName(adrenoToolsDriverId);
        if (libraryName.isEmpty()) return false;
        return meta.isFile() && new File(dir, libraryName).isFile();
    }

    public String getLibraryName(String adrenoToolsDriverId) {
        String libraryName = "";
        try {
            File metaProfile = new File(adrenotoolsContentDir, adrenoToolsDriverId + "/meta.json");
            String jsonStr = FileUtils.readString(metaProfile);
            JSONObject jsonObject = new JSONObject(jsonStr != null ? jsonStr : "{}");
            libraryName = jsonObject.getString("libraryName");
        } catch (JSONException e) {
            Log.w(TAG, "No libraryName in meta.json for driver " + adrenoToolsDriverId);
        }
        return libraryName;
    }

    /**
     * Install an adrenotools-compatible zip ({@code libvulkan_*.so} +
     * {@code meta.json}) into {@code contents/adrenotools/<driverId>/}.
     * Replaces any previous contents of that id.
     */
    public void installFromZip(File zipFile, String driverId) throws IOException {
        if (zipFile == null || !zipFile.isFile()) {
            throw new IOException("Adrenotools zip missing: " + zipFile);
        }
        if (driverId == null || driverId.isEmpty() || driverId.contains("..") || driverId.contains("/")) {
            throw new IOException("Invalid adrenotools driver id: " + driverId);
        }
        File destDir = getDriverDir(driverId);
        if (destDir.exists()) {
            FileUtils.delete(destDir);
        }
        if (!destDir.mkdirs()) {
            throw new IOException("Cannot create adrenotools dir: " + destDir);
        }

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            byte[] buf = new byte[64 * 1024];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = new File(entry.getName()).getName();
                if (name.isEmpty() || name.startsWith(".")) continue;
                File out = new File(destDir, name);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }

        if (!isInstalled(driverId)) {
            FileUtils.delete(destDir);
            throw new IOException(
                "Adrenotools zip did not produce meta.json + library for id=" + driverId);
        }
        Log.i(TAG, "Installed adrenotools driver id=" + driverId
            + " library=" + getLibraryName(driverId)
            + " dir=" + destDir.getAbsolutePath());
    }
}
