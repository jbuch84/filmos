package com.github.ma1co.pmcademo.app;

import android.os.Environment;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Filepaths {

    public static File getStorageRoot() {
        return Environment.getExternalStorageDirectory();
    }

    public static List<File> getStorageRoots() {
        ArrayList<File> roots = new ArrayList<File>();
        roots.add(getStorageRoot());             // Primary (a5100)
        roots.add(new File("/storage/sdcard1")); // Physical SD (A7II)
        roots.add(new File("/mnt/sdcard"));
        roots.add(new File("/storage/extSdCard"));
        return roots;
    }

    public static File getAppDir() {
        for (File root : getStorageRoots()) {
            File dir = new File(root, "JPEGCAM");
            if (dir.exists()) return dir;
        }
        File def = new File(getStorageRoot(), "JPEGCAM");
        if (!def.exists()) def.mkdirs();
        return def;
    }

    public static File getLutDir() {
        for (File root : getStorageRoots()) {
            File dir = new File(root, "JPEGCAM/LUTS");
            if (dir.exists()) return dir;
        }
        File def = new File(getAppDir(), "LUTS");
        if (!def.exists()) def.mkdirs();
        return def;
    }

    // NEW: Path to the external Grain Textures
    public static File getGrainDir() {
        for (File root : getStorageRoots()) {
            File dir = new File(root, "JPEGCAM/GRAIN");
            if (dir.exists()) return dir;
        }
        File def = new File(getAppDir(), "GRAIN");
        if (!def.exists()) def.mkdirs();
        return def;
    }

    public static File getDcimDir() {
        // Iterate through all roots (SD cards first)
        for (File root : getStorageRoots()) {
            File dcim = new File(root, "DCIM");
            if (dcim.exists() && dcim.isDirectory()) {
                File[] subdirs = dcim.listFiles();
                // If there is a DCIM folder with ANY subdirectories, this is likely our target
                if (subdirs != null && subdirs.length > 0) {
                    for (File s : subdirs) {
                        if (s.isDirectory() && !s.getName().startsWith(".")) {
                            return dcim; 
                        }
                    }
                }
            }
        }
        // Last-ditch: return the standard DCIM on the default root
        return new File(getStorageRoot(), "DCIM"); 
    }

    public static File getRecipeDir() { File d = new File(getAppDir(), "RECIPES"); if (!d.exists()) d.mkdirs(); return d; }
    public static File getLensesDir() { File d = new File(getAppDir(), "LENSES"); if (!d.exists()) d.mkdirs(); return d; }
    public static File getGradedDir() { File d = new File(getAppDir(), "GRADED"); if (!d.exists()) d.mkdirs(); return d; }

    public static void buildAppStructure() {
        getAppDir(); getLutDir(); getRecipeDir(); getLensesDir(); getGradedDir(); getGrainDir();
    }

    // NEW: Extracts bundled starter files explicitly (Bypasses API 10 list() bug)
    public static void extractDefaultAssets(android.content.Context context) {
        File grainDir = getGrainDir();
        
        // Force-create the directory right before extracting, just in case
        if (!grainDir.exists()) {
            grainDir.mkdirs();
        }
        
        // STRICT 8.3 FILENAME COMPLIANCE FOR SONY FAT32 SD CARDS
        String[] starterFiles = {
            "port400.txt",
            "neo400.txt"
        };

        for (String assetName : starterFiles) {
            try {
                File outFile = new File(grainDir, assetName);
                
                // Only extract if it doesn't already exist on the SD card
                if (!outFile.exists()) {
                    java.io.InputStream in = context.getAssets().open("grain/" + assetName);
                    java.io.FileOutputStream out = new java.io.FileOutputStream(outFile);
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    in.close();
                    out.flush();
                    out.close();
                    android.util.Log.d("JPEG.CAM", "Successfully extracted: " + assetName);
                }
            } catch (Exception e) {
                android.util.Log.e("JPEG.CAM", "Failed to extract " + assetName + ": " + e.getMessage());
            }
        }
    }
}