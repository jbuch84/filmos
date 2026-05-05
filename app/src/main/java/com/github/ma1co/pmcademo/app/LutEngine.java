package com.github.ma1co.pmcademo.app;

import java.io.File;

public class LutEngine {
    static { System.loadLibrary("native-lib"); }
    private String currentLutName = "";
    private String currentGrainTexturePath = "";

    // Result returned by loadFromCam() so ImageProcessor knows what was loaded.
    public static class CamLoadResult {
        public boolean lutLoaded   = false;
        public boolean grainLoaded = false;
    }

    // Cache key for .cam loading: "<path>|<fileSize>" — avoids re-opening the ZIP
    // on every shot when the same .cam is in use.
    private String currentCamKey        = "";
    private boolean lastCamLutLoaded    = false;
    private boolean lastCamGrainLoaded  = false;

    private native boolean loadLutNative(String filePath);
    private native boolean loadGrainTextureNative(String filePath);

    // Signature matches C++ exactly: 16 total parameters after env/obj
    private native boolean processImageNative(
        String inPath, String outPath, int scaleDenom, int opacity,
        int grain, int grainSize, int vignette, int rollOff,
        int colorChrome, int chromeBlue, int shadowToe,
        int subtractiveSat, int halation, int bloom,
        int advancedGrainExperimental, int jpegQuality,
        boolean applyCrop, int numCores
    );

    /**
     * Loads either a .cube/.cub or .png HaldCLUT from the SD card.
     */
    public boolean loadLut(File lutFile, String lutName) {
        String safeName = lutName != null ? lutName : "OFF";
        String path = lutFile != null ? lutFile.getAbsolutePath() : "";
        boolean noLut = "OFF".equalsIgnoreCase(safeName) || "NONE".equalsIgnoreCase(path) || path.length() == 0;

        if (noLut) {
            if ("OFF".equals(currentLutName)) return true;
            loadLutNative("");
            currentLutName = "OFF";
            return true;
        }

        String lutKey = safeName + "|" + path;
        if (lutKey.equals(currentLutName)) return true;
        if (loadLutNative(path)) {
            currentLutName = lutKey;
            return true;
        }
        currentLutName = "";
        return false;
    }

    public boolean loadLut(String lutPath, String lutName) {
        String safePath = lutPath != null ? lutPath : "";
        File lutFile = safePath.length() > 0 && !"NONE".equalsIgnoreCase(safePath) ? new File(safePath) : null;
        return loadLut(lutFile, lutName);
    }

    public boolean applyLutToJpeg(String in, String out, int scale, int opacity,
                                  int grain, int grainSize, int vignette, int rollOff,
                                  int colorChrome, int chromeBlue, int shadowToe,
                                  int subtractiveSat, int halation, int bloom,
                                  int advancedGrainExperimental,
                                  int quality,
                                  boolean applyCrop, int numCores) {
        return processImageNative(in, out, scale, opacity, grain, grainSize, vignette,
                                 rollOff, colorChrome, chromeBlue, shadowToe,
                                 subtractiveSat, halation, bloom,
                                 advancedGrainExperimental, quality,
                                 applyCrop, numCores);
    }

    // Public wrapper to load the grain texture safely (loose-file path).
    public boolean loadGrainTexture(File texFile) {
        if (texFile == null || !texFile.exists()) return false;
        String texPath = texFile.getAbsolutePath();
        if (texPath.equals(currentGrainTexturePath)) return true;
        if (loadGrainTextureNative(texPath)) {
            currentGrainTexturePath = texPath;
            return true;
        }
        return false;
    }

    /**
     * Opens a .cam bundle (renamed ZIP) and loads its LUT and grain into native memory.
     *
     * Two-phase approach to avoid SD card conflicts:
     *   Phase 1 — open ZipFile, read all asset bytes into memory, CLOSE ZipFile.
     *   Phase 2 — write temp files and call native loaders (no open file handles).
     *
     * Reading into a pre-sized byte[] (no ByteArrayOutputStream double-copy):
     *   lut.cube ~970 KB + grain.png ~300 KB ≈ 1.3 MB peak — safe on the 24 MB heap.
     *
     * Cached by camPath + file size so the ZIP is only opened once per recipe switch.
     */
    public CamLoadResult loadFromCam(String camPath, File cacheDir) {
        CamLoadResult result = new CamLoadResult();
        java.io.File camFile = new java.io.File(camPath);
        if (!camFile.exists()) {
            DebugLog.write("CAM: file not found: " + camPath);
            return result;
        }

        // Cache check — if same .cam and same size, native already has it loaded.
        String cacheKey = camPath + "|" + camFile.length();
        if (cacheKey.equals(currentCamKey)) {
            DebugLog.write("CAM: cache hit lut=" + lastCamLutLoaded + " grain=" + lastCamGrainLoaded);
            result.lutLoaded   = lastCamLutLoaded;
            result.grainLoaded = lastCamGrainLoaded;
            return result;
        }

        String lutEntry   = null;
        String grainEntry = null;
        byte[] lutBytes   = null;
        byte[] grainBytes = null;
        File tempLutFile   = null;
        File tempGrainFile = null;

        try {
            // ---- Phase 1: read everything into memory, then close the ZipFile ----
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(camFile);
            try {
                // 1a. Read recipe.json to discover entry names.
                java.util.zip.ZipEntry je = zf.getEntry("recipe.json");
                if (je != null) {
                    byte[] jsonBytes = readZipEntry(zf, je);
                    try {
                        org.json.JSONObject json = new org.json.JSONObject(new String(jsonBytes, "UTF-8"));
                        lutEntry   = json.optString("lutEntry",   null);
                        grainEntry = json.optString("grainEntry", null);
                    } catch (Exception e) {
                        DebugLog.write("CAM: recipe.json parse error: " + e.getMessage());
                    }
                } else {
                    DebugLog.write("CAM: no recipe.json in bundle");
                }

                // 1b. Read LUT bytes into memory.
                if (lutEntry == null) {
                    DebugLog.write("CAM: no lutEntry in recipe.json");
                } else {
                    java.util.zip.ZipEntry le = zf.getEntry(lutEntry);
                    if (le != null) {
                        DebugLog.write("CAM: reading lut \"" + lutEntry + "\" (" + le.getSize() + "b)");
                        lutBytes = readZipEntryExact(zf, le);
                    } else {
                        DebugLog.write("CAM: lutEntry \"" + lutEntry + "\" not found in ZIP");
                    }
                }

                // 1c. Read grain bytes into memory (optional).
                if (grainEntry != null) {
                    java.util.zip.ZipEntry ge = zf.getEntry(grainEntry);
                    if (ge != null) {
                        DebugLog.write("CAM: reading grain \"" + grainEntry + "\" (" + ge.getSize() + "b)");
                        grainBytes = readZipEntryExact(zf, ge);
                    } else {
                        DebugLog.write("CAM: grainEntry \"" + grainEntry + "\" not found in ZIP");
                    }
                }
            } finally {
                zf.close(); // ZIP closed before any SD card writes
            }

            // ---- Phase 2: write temp files and load native (no open ZIP handles) ----
            if (lutBytes != null) {
                String ext = lutEntry.contains(".") ? lutEntry.substring(lutEntry.lastIndexOf('.')) : ".cube";
                tempLutFile = new File(cacheDir, "cam_lut_tmp" + ext);
                DebugLog.write("CAM: writing lut to " + tempLutFile.getAbsolutePath());
                writeBytesToFile(lutBytes, tempLutFile);
                lutBytes = null; // release before native load
                result.lutLoaded = loadLutNative(tempLutFile.getAbsolutePath());
                DebugLog.write("CAM: loadLutNative=" + result.lutLoaded);
                currentLutName = "";
            }

            if (grainBytes != null) {
                tempGrainFile = new File(cacheDir, "cam_grain_tmp.png");
                DebugLog.write("CAM: writing grain to " + tempGrainFile.getAbsolutePath());
                writeBytesToFile(grainBytes, tempGrainFile);
                grainBytes = null; // release before native load
                result.grainLoaded = loadGrainTextureNative(tempGrainFile.getAbsolutePath());
                DebugLog.write("CAM: loadGrainTextureNative=" + result.grainLoaded);
                currentGrainTexturePath = "";
            }

        } catch (Exception e) {
            DebugLog.write("CAM: load exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return result;
        } finally {
            if (tempLutFile  != null) tempLutFile.delete();
            if (tempGrainFile != null) tempGrainFile.delete();
        }

        currentCamKey      = cacheKey;
        lastCamLutLoaded   = result.lutLoaded;
        lastCamGrainLoaded = result.grainLoaded;
        DebugLog.write("CAM: loaded lut=" + result.lutLoaded + " grain=" + result.grainLoaded);
        return result;
    }

    /** Reads a small ZipEntry (e.g. recipe.json) into memory via ByteArrayOutputStream. */
    private static byte[] readZipEntry(java.util.zip.ZipFile zf, java.util.zip.ZipEntry entry)
            throws java.io.IOException {
        java.io.InputStream is = zf.getInputStream(entry);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        is.close();
        return baos.toByteArray();
    }

    /**
     * Reads a ZipEntry into a pre-sized byte array using the entry's declared size.
     * No ByteArrayOutputStream means no double-copy — safe for assets up to a few MB.
     * Falls back to ByteArrayOutputStream if the declared size is unavailable.
     */
    private static byte[] readZipEntryExact(java.util.zip.ZipFile zf, java.util.zip.ZipEntry entry)
            throws java.io.IOException {
        java.io.InputStream is = zf.getInputStream(entry);
        try {
            long declared = entry.getSize();
            if (declared > 0 && declared <= 4 * 1024 * 1024) {
                byte[] data = new byte[(int) declared];
                int offset = 0;
                while (offset < data.length) {
                    int n = is.read(data, offset, data.length - offset);
                    if (n == -1) break;
                    offset += n;
                }
                return data;
            } else {
                // Size unknown — fall back to growing buffer (should not normally happen)
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                return baos.toByteArray();
            }
        } finally {
            is.close();
        }
    }

    /** Writes a byte array to a file. */
    private static void writeBytesToFile(byte[] data, File dest) throws java.io.IOException {
        java.io.FileOutputStream fos = new java.io.FileOutputStream(dest);
        try {
            fos.write(data);
        } finally {
            fos.close();
        }
    }
}
