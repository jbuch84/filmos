package com.github.ma1co.pmcademo.app;

import java.io.File;

public class LutEngine {
    static { System.loadLibrary("native-lib"); }
    private String currentLutName = "";
    private String currentGrainTexturePath = "";

    private native boolean loadLutNative(String filePath);
    
    // NEW: Native hook for loading the 512x512 texture into C++
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

    // NEW: Public wrapper to load the grain texture safely
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
}
