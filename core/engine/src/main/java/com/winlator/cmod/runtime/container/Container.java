package com.winlator.cmod.runtime.container;

import android.os.Environment;
import android.util.Log;

import com.winlator.cmod.runtime.compat.box64.Box64Preset;
import com.winlator.cmod.runtime.wine.EnvVars;
import com.winlator.cmod.shared.util.KeyValueSet;
import com.winlator.cmod.runtime.wine.WineInfo;
import com.winlator.cmod.runtime.compat.fexcore.FEXCorePreset;
import com.winlator.cmod.runtime.display.environment.ImageFs;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Container {
    private static final String TAG = "Container";
    private static final ConcurrentHashMap<String, Object> SAVE_LOCKS = new ConcurrentHashMap<>();
    public static final String DEFAULT_ENV_VARS = "WRAPPER_MAX_IMAGE_COUNT=0 VKD3D_SHADER_MODEL=6_6 ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true TU_DEBUG=noconform,sysmem";
    public static final String DEFAULT_SCREEN_SIZE = "1280x720";
    public static final String DEFAULT_GRAPHICS_DRIVER = "wrapper";
    public static final String DEFAULT_AUDIO_DRIVER = "alsa";
    public static final String DEFAULT_EMULATOR = "Box64";
    public static final String DEFAULT_EMULATOR64 = "Box64";
    /** Amphora 只认分号格式；真实 pin 由 getOrCreate/syncRuntimePins 写入。 */
    public static final String DEFAULT_DXWRAPPER = "dxvk-;vkd3d-;dd7to9";
    public static final String DEFAULT_DXWRAPPERCONFIG = "version=,async=1,asyncCache=1" + ",vkd3dVersion=None,vkd3dLevel=12_1" + ",ddrawrapper=" + Container.DEFAULT_DDRAWRAPPER + ",csmt=3" + ",gpuName=NVIDIA GeForce GTX 480" + ",videoMemorySize=4096" + ",strict_shader_math=1" + ",OffscreenRenderingMode=fbo" + ",renderer=gl";
    public static final String DEFAULT_GRAPHICSDRIVERCONFIG =
            "vulkanVersion=1.3" + ";version=" + ";blacklistedExtensions=" + ";maxDeviceMemory=0" + ";presentMode=mailbox" + ";syncFrame=0" + ";disablePresentWait=1" + ";resourceType=auto" + ";bcnEmulation=auto" + ";bcnEmulationType=compute" + ";bcnEmulationCache=0" + ";gpuName=Device";
    /** elishacloud/dxwrapper Dd7to9; Amphora does not fall back to WineD3D for DirectDraw. */
    public static final String DEFAULT_DDRAWRAPPER = "dd7to9";
    public static final String DEFAULT_WINCOMPONENTS = "direct3d=1,directsound=0,directmusic=0,directshow=0,directplay=0,xaudio=0,dinput8=1,vcrun2010=1";
    public static final String FALLBACK_WINCOMPONENTS = "direct3d=1,directsound=1,directmusic=1,directshow=1,directplay=1,xaudio=1,dinput8=1,vcrun2010=1";
    public static final String DEFAULT_DRIVES = buildDefaultDrives();
    public static final byte STARTUP_SELECTION_NORMAL = 0;
    public static final byte STARTUP_SELECTION_ESSENTIAL = 1;
    public static final byte STARTUP_SELECTION_AGGRESSIVE = 2;
    public static final byte MAX_DRIVE_LETTERS = 26;
    /** Former WinHandler.DEFAULT_INPUT_TYPE (XInput); kept as a container field default. */
    public static final byte DEFAULT_INPUT_TYPE = 4;
    /** Former WinHandler.FLAG_INPUT_TYPE_DINPUT — used by preparer joystick registry. */
    public static final byte FLAG_INPUT_TYPE_DINPUT = 8;
    public final int id;
    private String name;
    private String screenSize = DEFAULT_SCREEN_SIZE;
    private String envVars = DEFAULT_ENV_VARS;
    private String graphicsDriver = DEFAULT_GRAPHICS_DRIVER;
    private String graphicsDriverConfig = DEFAULT_GRAPHICSDRIVERCONFIG;
    private String dxwrapper = DEFAULT_DXWRAPPER;
    private String dxwrapperConfig = "";
    private String wincomponents = DEFAULT_WINCOMPONENTS;
    private String audioDriver = DEFAULT_AUDIO_DRIVER;
    private String drives = DEFAULT_DRIVES;
    private String wineVersion = WineInfo.MAIN_WINE_VERSION.identifier();
    private boolean fullscreenStretched;
    private boolean useUnixLibs = true;
    private byte startupSelection = STARTUP_SELECTION_ESSENTIAL;
    private String cpuList;
    private String cpuListWoW64;
    private String desktopTheme = "LIGHT,IMAGE,#0277bd";
    private String fexcoreVersion = "";
    private String fexcorePreset = FEXCorePreset.PERFORMANCE;
    private String box64Preset = Box64Preset.PERFORMANCE;
    private File rootDir;
    private JSONObject extraData;
    private String midiSoundFont = "";
    private int inputType = DEFAULT_INPUT_TYPE;
    private boolean exclusiveXInput = true;
    private String lc_all = "";
    private String box64Version = "";
    private String emulator = DEFAULT_EMULATOR;
    private String emulator64 = DEFAULT_EMULATOR64;
    private String executablePath = "";
    private String execArgs = "";
    private boolean launchBionicSteam;
    private boolean useColdClient = false;
    private boolean allowSteamUpdates;
    private boolean needsUnpacking = true;
    private boolean steamOfflineMode = false;
    private boolean unpackFiles = false;
    private boolean runtimePatcher = false;

    private ContainerManager containerManager;
    /** Local object state at the last load/save, used to merge non-conflicting external edits. */
    private JSONObject savedState;



    public Container(int id) {
        this.id = id;
        this.name = "Container-"+id;
    }

    private static String buildDefaultDrives() {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File primary = Environment.getExternalStorageDirectory();
        String downloadsPath =
                downloads != null ? downloads.getAbsolutePath() : "/storage/emulated/0/Download";
        String primaryPath =
                primary != null ? primary.getAbsolutePath() : "/storage/emulated/0";
        return "D:" + downloadsPath + "F:" + primaryPath;
    }

    public Container(int id, ContainerManager containerManager) {
        this.id = id;
        this.name = "Container-"+id;
        this.containerManager = containerManager;
    }

    
    public String getExecutablePath() {
        return executablePath;
    }

    public void setExecutablePath(String executablePath) {
        String newPath = executablePath != null ? executablePath : "";
        // If the executable path changed from a non-empty value, mark as needing unpacking
        // so Steamless DRM stripping will re-run on the new exe
        if (!this.executablePath.isEmpty() && !this.executablePath.equals(newPath)) {
            this.needsUnpacking = true;
        }
        this.executablePath = newPath;
    }

    public String getExecArgs() {
        return execArgs;
    }

    public void setExecArgs(String execArgs) {
        this.execArgs = execArgs != null ? execArgs : "";
    }
    
    public ContainerManager getManager() {
        return containerManager;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getScreenSize() {
        return screenSize;
    }

    public void setScreenSize(String screenSize) {
        this.screenSize = screenSize;
    }

    public String getEnvVars() {
        return envVars;
    }

    public void setEnvVars(String envVars) {
        this.envVars = envVars != null ? envVars : "";
    }

    public String getGraphicsDriver() {
        return graphicsDriver;
    }

    public void setGraphicsDriver(String graphicsDriver) {
        this.graphicsDriver = graphicsDriver;
    }

    public String getGraphicsDriverConfig() { return this.graphicsDriverConfig; }

    public void setGraphicsDriverConfig(String graphicsDriverConfig) { this.graphicsDriverConfig = graphicsDriverConfig; }

    public String getDXWrapper() {
        return dxwrapper;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public String getDXWrapperConfig() {
        return dxwrapperConfig;
    }

    public void setDXWrapperConfig(String dxwrapperConfig) {
        this.dxwrapperConfig = dxwrapperConfig != null ? dxwrapperConfig : "";
    }

    public String getAudioDriver() {
        return audioDriver;
    }

    public void setAudioDriver(String audioDriver) {
        this.audioDriver = audioDriver;
    }

    public String getWinComponents() {
        return wincomponents;
    }

    public void setWinComponents(String wincomponents) {
        this.wincomponents = wincomponents;
    }

    public String getDrives() {
        return drives;
    }

    public void setDrives(String drives) {
        this.drives = drives;
    }

    public String getLC_ALL() {
        return lc_all;
    }

    public void setLC_ALL(String lc_all) {
        this.lc_all = lc_all;
    }

    public boolean isFullscreenStretched() { return fullscreenStretched; }

    public void setFullscreenStretched(boolean fullscreenStretched) { this.fullscreenStretched = fullscreenStretched; }

    public boolean isUseUnixLibs() { return useUnixLibs; }

    public void setUseUnixLibs(boolean useUnixLibs) { this.useUnixLibs = useUnixLibs; }

    public byte getStartupSelection() {
        return startupSelection;
    }

    public void setStartupSelection(byte startupSelection) {
        this.startupSelection = startupSelection;
    }

    public String getCPUList() {
        return getCPUList(false);
    }

    public String getCPUList(boolean allowFallback) {
        return cpuList != null ? cpuList : (allowFallback ? getFallbackCPUList() : null);
    }

    public void setCPUList(String cpuList) {
        this.cpuList = cpuList != null && !cpuList.isEmpty() ? cpuList : null;
    }

    public String getCPUListWoW64() {
        return getCPUListWoW64(false);
    }

    public String getCPUListWoW64(boolean allowFallback) {
        return cpuListWoW64 != null ? cpuListWoW64 : (allowFallback ? getFallbackCPUListWoW64() : null);
    }

    public void setCPUListWoW64(String cpuListWoW64) {
        this.cpuListWoW64 = cpuListWoW64 != null && !cpuListWoW64.isEmpty() ? cpuListWoW64 : null;
    }

    public void setFEXCoreVersion(String version) {
        this.fexcoreVersion = version;
    }

    public String getFEXCoreVersion() {
        return this.fexcoreVersion;
    }

    public void setFEXCorePreset(String preset) {
        this.fexcorePreset = preset;
    }

    public String getFEXCorePreset() {
        return fexcorePreset;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    public String getBox64Version() { return box64Version; }

    public void setBox64Version(String version) { this.box64Version = version; }

    public void setEmulator(String emulator) {
        this.emulator = emulator;
    }

    public String getEmulator() {
        return this.emulator;
    }

    public void setEmulator64(String emulator64) {
        this.emulator64 = emulator64;
    }

    public String getEmulator64() {
        return this.emulator64;
    }

    public File getRootDir() {
        return rootDir;
    }

    public void setRootDir(File rootDir) {
        this.rootDir = rootDir;
    }

    public void setExtraData(JSONObject extraData) {
        this.extraData = extraData;
    }

    public String getLanguage() {
        return getExtra("containerLanguage", "english");
    }

    public String getExtra(String key) {
        return getExtra(key, "");
    }

    public String getExtra(String name, String fallback) {
        try {
            return extraData != null && extraData.has(name) ? extraData.getString(name) : fallback;
        }
        catch (JSONException e) {
            return fallback;
        }
    }

    public boolean hasExtra(String name) {
        return extraData != null && extraData.has(name);
    }

    public void putExtra(String name, Object value) {
        if (extraData == null) extraData = new JSONObject();
        try {
            if (value != null) {
                extraData.put(name, value);
            }
            else extraData.remove(name);
        }
        catch (JSONException e) {}
    }

    public String getWineVersion() {
        return wineVersion;
    }

    public void setWineVersion(String wineVersion) {
        this.wineVersion = wineVersion;
    }

    public File getConfigFile() {
        return new File(rootDir, ".container");
    }

    public File getDesktopDir() {
        return new File(rootDir, ".wine/drive_c/users/"+ImageFs.USER+"/Desktop/");
    }

    public File getStartMenuDir() {
        return new File(rootDir, ".wine/drive_c/ProgramData/Microsoft/Windows/Start Menu/");
    }

    public File getIconsDir(int size) {
        return new File(rootDir, ".local/share/icons/hicolor/"+size+"x"+size+"/apps/");
    }

    public String getDesktopTheme() {
        return desktopTheme;
    }

    public void setDesktopTheme(String desktopTheme) {
        this.desktopTheme = desktopTheme;
    }

    public String getMIDISoundFont() {
        return midiSoundFont;
    }

    public void setMidiSoundFont(String fileName) {
        midiSoundFont = fileName;
    }

    public int getInputType() {
        return inputType;
    }

    public void setInputType(int inputType) {
        this.inputType = inputType;
    }

    public boolean isExclusiveXInput() {
        return exclusiveXInput;
    }

    public void setExclusiveXInput(boolean exclusiveXInput) {
        this.exclusiveXInput = exclusiveXInput;
    }

    public Iterable<String[]> drivesIterator() {
        return drivesIterator(drives);
    }

    public static Iterable<String[]> drivesIterator(final String drives) {
        final int[] index = {drives.indexOf(":")};
        final String[] item = new String[2];
        return () -> new Iterator<String[]>() {
            @Override
            public boolean hasNext() {
                return index[0] != -1;
            }

            @Override
            public String[] next() {
                item[0] = String.valueOf(drives.charAt(index[0]-1));
                int nextIndex = drives.indexOf(":", index[0]+1);
                item[1] = drives.substring(index[0]+1, nextIndex != -1 ? nextIndex-1 : drives.length());
                index[0] = nextIndex;
                return item;
            }
        };
    }

    public synchronized boolean saveData() {
        final JSONObject desired;
        try {
            desired = buildData();
        }
        catch (JSONException e) {
            Log.e(TAG, "Cannot serialize container " + id, e);
            return false;
        }

        File configFile = getConfigFile();
        File parent = configFile.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return false;
        File lockFile = new File(parent, configFile.getName() + ".lock");
        Object processLock = SAVE_LOCKS.computeIfAbsent(lockFile.getAbsolutePath(), key -> new Object());

        synchronized (processLock) {
            try (RandomAccessFile lockAccess = new RandomAccessFile(lockFile, "rw");
                 FileChannel lockChannel = lockAccess.getChannel();
                 FileLock ignored = lockChannel.lock()) {
                JSONObject merged = mergeWithCurrent(configFile, desired);
                if (merged == null || !atomicWrite(configFile, merged.toString())) return false;
                savedState = copyJson(desired);
                return true;
            }
            catch (IOException | JSONException e) {
                Log.e(TAG, "Cannot save container " + id + " to " + configFile, e);
                return false;
            }
        }
    }

    private JSONObject buildData() throws JSONException {
        JSONObject data = new JSONObject();
        data.put("id", id);
        data.put("name", name);
        data.put("screenSize", screenSize);
        data.put("envVars", envVars);
        data.put("cpuList", cpuList);
        data.put("cpuListWoW64", cpuListWoW64);
        data.put("graphicsDriver", graphicsDriver);
        data.put("graphicsDriverConfig", graphicsDriverConfig);
        data.put("emulator", emulator);
        data.put("emulator64", emulator64);
        data.put("executablePath", executablePath);
        data.put("execArgs", execArgs);
        data.put("dxwrapper", dxwrapper);
        if (!dxwrapperConfig.isEmpty()) data.put("dxwrapperConfig", dxwrapperConfig);
        data.put("audioDriver", audioDriver);
        data.put("wincomponents", wincomponents);
        data.put("drives", drives);
        data.put("fullscreenStretched", fullscreenStretched);
        data.put("useUnixLibs", useUnixLibs);
        data.put("inputType", inputType);
        data.put("exclusiveXInput", exclusiveXInput);
        data.put("startupSelection", startupSelection);
        data.put("box64Version", box64Version);
        data.put("fexcorePreset", fexcorePreset);
        data.put("fexcoreVersion", fexcoreVersion);
        data.put("box64Preset", box64Preset);
        data.put("desktopTheme", desktopTheme);
        data.put("extraData", extraData);
        data.put("midiSoundFont", midiSoundFont);
        data.put("lc_all", lc_all);
        data.put("launchBionicSteam", launchBionicSteam);
        data.put("useColdClient", useColdClient);
        data.put("coldClientMigrated", true);
        data.put("allowSteamUpdates", allowSteamUpdates);
        data.put("needsUnpacking", needsUnpacking);
        data.put("steamOfflineMode", steamOfflineMode);
        data.put("unpackFiles", unpackFiles);
        data.put("runtimePatcher", runtimePatcher);
        if (!WineInfo.isMainWineVersion(wineVersion)) data.put("wineVersion", wineVersion);
        return data;
    }

    /**
     * Merge only fields changed on this object since it was loaded. A second process may have
     * updated unrelated fields in the meantime; those values must survive this save. If both
     * writers changed the same field, preserve the value already on disk and reject the stale save.
     */
    private JSONObject mergeWithCurrent(File configFile, JSONObject desired)
            throws IOException, JSONException {
        if (!configFile.exists()) return copyJson(desired);

        String currentText =
                new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
        if (currentText.trim().isEmpty()) {
            Log.e(TAG, "Refusing to replace empty or unreadable container config " + configFile);
            return null;
        }
        JSONObject current = new JSONObject(currentText);
        if (savedState == null) return copyJson(desired);

        JSONObject merged = copyJson(current);
        Set<String> keys = new HashSet<>();
        savedState.keys().forEachRemaining(keys::add);
        desired.keys().forEachRemaining(keys::add);
        for (String key : keys) {
            boolean baselineHas = savedState.has(key);
            boolean desiredHas = desired.has(key);
            if (jsonValuesEqual(baselineHas, savedState.opt(key), desiredHas, desired.opt(key))) {
                continue;
            }
            boolean currentHas = current.has(key);
            if (!jsonValuesEqual(baselineHas, savedState.opt(key), currentHas, current.opt(key))) {
                Log.w(TAG, "Rejecting stale container " + id + " update for field " + key);
                return null;
            }
            if (desiredHas) merged.put(key, desired.get(key));
            else merged.remove(key);
        }
        return merged;
    }

    private static boolean jsonValuesEqual(
            boolean firstHas, Object first, boolean secondHas, Object second) {
        if (firstHas != secondHas) return false;
        if (!firstHas) return true;
        if (first == second) return true;
        if (first == null || second == null) return false;
        return first.toString().equals(second.toString());
    }

    private static JSONObject copyJson(JSONObject source) throws JSONException {
        return new JSONObject(source.toString());
    }

    private static boolean atomicWrite(File target, String contents) throws IOException {
        File parent = target.getParentFile();
        if (parent == null) return false;
        File temp = File.createTempFile(target.getName() + ".", ".tmp", parent);
        try {
            byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            }
            try {
                Files.move(
                        temp.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            syncDirectory(parent);
            return target.isFile() && target.length() == bytes.length;
        }
        finally {
            if (temp.exists() && !temp.delete()) {
                Log.w(TAG, "Could not remove abandoned container temp file " + temp);
            }
        }
    }

    private static void syncDirectory(File directory) {
        try (FileChannel channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) {
            channel.force(true);
        }
        catch (IOException | UnsupportedOperationException e) {
            // The data file itself is already fsynced. Some Android filesystems do not expose
            // directory fsync through java.nio, so retain the atomic rename and log the limitation.
            Log.w(TAG, "Directory fsync unavailable for " + directory, e);
        }
    }


    public void loadData(JSONObject data) throws JSONException {
        wineVersion = WineInfo.MAIN_WINE_VERSION.identifier();
        dxwrapperConfig = "";
        checkObsoleteOrMissingProperties(data);

        for (Iterator<String> it = data.keys(); it.hasNext(); ) {
            String key = it.next();
            switch (key) {
                case "name" :
                    setName(data.getString(key));
                    break;
                case "screenSize" :
                    setScreenSize(data.getString(key));
                    break;
                case "envVars" :
                    setEnvVars(data.getString(key));
                    break;
                case "cpuList" :
                    setCPUList(data.getString(key));
                    break;
                case "cpuListWoW64" :
                    setCPUListWoW64(data.getString(key));
                    break;
                case "graphicsDriver" :
                    setGraphicsDriver(data.getString(key));
                    break;
                case "graphicsDriverConfig" :
                    setGraphicsDriverConfig(data.getString(key));
                    break;
                
                case "executablePath":
                    setExecutablePath(data.getString(key));
                    break;
                case "execArgs":
                    setExecArgs(data.getString(key));
                    break;
                case "emulator":
                    setEmulator(data.getString(key));
                    break;
                case "emulator64":
                    setEmulator64(data.getString(key));
                    break;
                case "wincomponents" :
                    setWinComponents(data.getString(key));
                    break;
                case "dxwrapper" :
                    setDXWrapper(data.getString(key));
                    break;
                case "dxwrapperConfig" :
                    setDXWrapperConfig(data.getString(key));
                    break;
                case "drives" :
                    setDrives(data.getString(key));
                    break;
                case "fullscreenStretched" :
                    setFullscreenStretched(data.getBoolean(key));
                    break;
                case "useUnixLibs" :
                    setUseUnixLibs(data.getBoolean(key));
                    break;
                case "inputType" :
                    setInputType(data.getInt(key));
                    break;
                case "exclusiveXInput" :
                    setExclusiveXInput(data.getBoolean(key));
                    break;
                case "startupSelection" :
                    setStartupSelection((byte)data.getInt(key));
                    break;
                case "extraData" : {
                    JSONObject extraData = data.getJSONObject(key);
                    checkObsoleteOrMissingProperties(extraData);
                    setExtraData(extraData);
                    break;
                }
                case "wineVersion" :
                    setWineVersion(data.getString(key));
                    break;
                case "box64Version":
                    setBox64Version(data.getString(key));
                    break;
                case "fexcoreVersion":
                    setFEXCoreVersion(data.getString(key));
                    break;
                case "fexcorePreset":
                    setFEXCorePreset(data.getString(key));
                    break;
                case "box64Preset" :
                    setBox64Preset(data.getString(key));
                    break;
                case "audioDriver" :
                    setAudioDriver(data.getString(key));
                    break;
                case "desktopTheme" :
                    setDesktopTheme(data.getString(key));
                    break;
                case "midiSoundFont" :
                    setMidiSoundFont(data.getString(key));
                    break;
                case "lc_all" :
                    setLC_ALL(data.getString(key));
                    break;
                case "launchBionicSteam" :
                    setLaunchBionicSteam(data.getBoolean(key));
                    break;
                case "useColdClient" :
                    // Only respect explicit user choice if coldClientMigrated flag is set
                    if (data.has("coldClientMigrated")) {
                        setUseColdClient(data.getBoolean(key));
                    }
                    // Otherwise keep default true (migrating from old data)
                    break;
                case "useLegacyDRM" :
                    // Old field — always default to ColdClient on
                    break;
                case "allowSteamUpdates" :
                    setAllowSteamUpdates(data.getBoolean(key));
                    break;
                case "needsUnpacking" :
                    setNeedsUnpacking(data.getBoolean(key));
                    break;
                case "steamOfflineMode":
                    setSteamOfflineMode(data.getBoolean(key));
                    break;
                case "unpackFiles":
                    setUnpackFiles(data.getBoolean(key));
                    break;
                case "runtimePatcher":
                    setRuntimePatcher(data.getBoolean(key));
                    break;
                case "moveSteamExe":
                    break;
            }
        }

        normalizeEmulatorFieldsForArch();
        savedState = buildData();
    }

    // Coerce emulator/emulator64 to values that are valid for the prefix's arch.
    // ARM64EC: 64-bit JIT is always FEXCore today; 32-bit JIT is FEXCore or wowbox64.
    // x86_64: both are box64. Anything else is legacy/invalid (e.g. literal "Box64"
    // string from pre-split builds, "FEXCore" hand-edited into x86_64 JSON, empty,
    // null, or mixed case). Normalizing here means every read path — launcher,
    // settings dialogs, shortcut overrides — sees a clean value without us having
    // to add defensive parsing scattered around the code. The next user-initiated
    // saveData() persists the cleaned value to disk; we deliberately don't write
    // here.
    private void normalizeEmulatorFieldsForArch() {
        boolean arm64ec = "arm64ec".equalsIgnoreCase(getExtra("wineprefixArch"));
        emulator = arm64ec
                ? sanitizeArm64ecEmulator32(emulator)
                : sanitizeX86Emulator(emulator);
        emulator64 = arm64ec
                ? "fexcore"
                : sanitizeX86Emulator(emulator64);
    }

    static String sanitizeArm64ecEmulator32(String value) {
        if (value == null) return "fexcore";
        String low = value.trim().toLowerCase(Locale.ROOT);
        return (low.equals("fexcore") || low.equals("wowbox64")) ? low : "fexcore";
    }

    static String sanitizeX86Emulator(String value) {
        // x86_64 containers always run via the box64 binary regardless of this
        // field, so the only sane saved value is "box64" — coerce everything else.
        return "box64";
    }

    public static void checkObsoleteOrMissingProperties(JSONObject data) {
        try {
            if (data.has("dxcomponents")) {
                data.put("wincomponents", data.getString("dxcomponents"));
                data.remove("dxcomponents");
            }

            if (data.has("dxwrapper")) {
                String dxwrapper = data.getString("dxwrapper");
                if (dxwrapper.equals("original-wined3d")) {
                    data.put("dxwrapper", DEFAULT_DXWRAPPER);
                }
                else if (dxwrapper.startsWith("d8vk-") || dxwrapper.startsWith("dxvk-")) {
                    data.put("dxwrapper", dxwrapper);
                }
            }

            if (data.has("graphicsDriver")) {
                String graphicsDriver = data.getString("graphicsDriver");
                if (graphicsDriver.equals("turnip-zink") || graphicsDriver.equals("turnip")) {
                    data.put("graphicsDriver", "wrapper");
                }
                else if (graphicsDriver.equals("llvmpipe")) {
                    data.put("graphicsDriver", "wrapper");
                }
            }

            if (data.has("envVars") && data.has("extraData")) {
                JSONObject extraData = data.getJSONObject("extraData");
                int appVersion = Integer.parseInt(extraData.optString("appVersion", "0"));
                if (appVersion < 16) {
                    EnvVars defaultEnvVars = new EnvVars(DEFAULT_ENV_VARS);
                    EnvVars envVars = new EnvVars(data.getString("envVars"));
                    for (String name : defaultEnvVars) if (!name.equals("VKD3D_SHADER_MODEL") && !envVars.has(name)) envVars.put(name, defaultEnvVars.get(name));
                    data.put("envVars", envVars.toString());
                }
            }

            KeyValueSet wincomponents1 = new KeyValueSet(DEFAULT_WINCOMPONENTS);
            KeyValueSet wincomponents2 = new KeyValueSet(data.getString("wincomponents"));
            String result = "";

            for (String[] wincomponent1 : wincomponents1) {
                String value = wincomponent1[1];

                for (String[] wincomponent2 : wincomponents2) {
                    if (wincomponent1[0].equals(wincomponent2[0])) {
                        value = wincomponent2[1];
                        break;
                    }
                }

                result += (!result.isEmpty() ? "," : "")+wincomponent1[0]+"="+value;
            }

            data.put("wincomponents", result);
        }
        catch (JSONException e) {}
    }

    public static String getFallbackCPUList() {
        String cpuList = "";
        int numProcessors = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < numProcessors; i++) cpuList += (!cpuList.isEmpty() ? "," : "")+i;
        return cpuList;
    }

    public static String getFallbackCPUListWoW64() {
        String cpuList = "";
        int numProcessors = Runtime.getRuntime().availableProcessors();
        for (int i = numProcessors / 2; i < numProcessors; i++) cpuList += (!cpuList.isEmpty() ? "," : "")+i;
        return cpuList;
    }

    // Check if a specific environment variable exists
    public boolean hasEnvVar(String keyValue) {
        if (envVars == null || envVars.isEmpty()) return false;
        String[] vars = envVars.split(",");
        for (String var : vars) {
            if (var.trim().equalsIgnoreCase(keyValue.trim())) {
                return true; // Found the variable
            }
        }
        return false;
    }

    /** Bionic Steam mode: wine launches steam.exe + game.exe and our embedded
     *  libsteamclient.so via wn-steam-bootstrap handles the SteamWorks IPC. */
    public boolean isLaunchBionicSteam() {
        return launchBionicSteam;
    }

    public void setLaunchBionicSteam(boolean launchBionicSteam) {
        this.launchBionicSteam = launchBionicSteam;
    }

    public boolean isUseColdClient() {
        return useColdClient;
    }

    public void setUseColdClient(boolean useColdClient) {
        this.useColdClient = useColdClient;
    }

    public boolean isAllowSteamUpdates() {
        return allowSteamUpdates;
    }

    public void setAllowSteamUpdates(boolean allowSteamUpdates) {
        this.allowSteamUpdates = allowSteamUpdates;
    }

    public boolean isNeedsUnpacking() {
        return needsUnpacking;
    }

    public void setNeedsUnpacking(boolean needsUnpacking) {
        this.needsUnpacking = needsUnpacking;
    }

    public boolean isSteamOfflineMode() {
        return steamOfflineMode;
    }

    public void setSteamOfflineMode(boolean steamOfflineMode) {
        this.steamOfflineMode = steamOfflineMode;
    }

    public boolean isUnpackFiles() {
        return unpackFiles;
    }

    public void setUnpackFiles(boolean unpackFiles) {
        this.unpackFiles = unpackFiles;
    }

    public boolean isRuntimePatcher() {
        return runtimePatcher;
    }

    public void setRuntimePatcher(boolean runtimePatcher) {
        this.runtimePatcher = runtimePatcher;
    }

}
