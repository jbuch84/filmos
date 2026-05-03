package com.github.ma1co.pmcademo.app;

import android.util.Log;
import java.io.*;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BundleManager {
    private static final String TAG = "JPEG.CAM_BUNDLE";
    private static final int BUFFER_SIZE = 8192;
    private static boolean isRunning = false;
    private static final Set<String> processed = new HashSet<String>();

    public static synchronized void extractAllBundles() {
        if (isRunning) return;
        isRunning = true;
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    processed.clear();
                    // Use proven app directories from Filepaths.java
                    File appDir = Filepaths.getAppDir();
                    File recipeDir = Filepaths.getRecipeDir();
                    
                    scanDir(appDir);
                    scanDir(recipeDir);
                } finally {
                    isRunning = false;
                }
            }
        }).start();
    }

    private static void scanDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (f.isFile() && name.endsWith(".cam") && !name.startsWith(".")) {
                try {
                    String id = f.getCanonicalPath();
                    if (processed.contains(id)) continue;
                    processed.add(id);
                    doExtract(f);
                } catch (IOException e) {}
            }
        }
    }

    private static void doExtract(File zip) {
        Log.d(TAG, "--- Unpacking Bundle: " + zip.getName() + " ---");
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)));
            ZipEntry entry;
            int counter = 0;

            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                String lowerName = entryName.toLowerCase();
                
                if (entry.isDirectory() || lowerName.contains("__macosx") || entryName.contains("/.") || entryName.startsWith(".")) {
                    zis.closeEntry();
                    continue;
                }

                // Determine target directory using proven Filepaths logic
                File targetDir = Filepaths.getRecipeDir(); // Default
                if (lowerName.endsWith(".cube") || lowerName.endsWith(".cub")) {
                    targetDir = Filepaths.getLutDir();
                } else if (lowerName.endsWith(".png")) {
                    targetDir = Filepaths.getGrainDir();
                }

                if (!targetDir.exists()) targetDir.mkdirs();

                // Get filename only, ignore zip folders
                String simpleName = new File(entryName).getName();
                File destFile = new File(targetDir, simpleName);
                
                // VFAT Workaround: 8.3 TMP file in the actual target folder
                File tempFile = new File(targetDir, String.format("T%03d.TMP", counter++));

                FileOutputStream fos = null;
                boolean writeOk = false;
                try {
                    fos = new FileOutputStream(tempFile);
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int count;
                    while ((count = zis.read(buffer, 0, BUFFER_SIZE)) != -1) {
                        fos.write(buffer, 0, count);
                    }
                    fos.flush();
                    try { fos.getFD().sync(); } catch (Exception e) {}
                    writeOk = true;
                } catch (Exception e) {
                    Log.e(TAG, "    Write failed: " + simpleName + " -> " + e.getMessage());
                } finally {
                    if (fos != null) try { fos.close(); } catch (Exception e) {}
                }

                if (writeOk && tempFile.exists()) {
                    if (destFile.exists()) destFile.delete();
                    if (tempFile.renameTo(destFile)) {
                        Log.d(TAG, "    Extracted: " + destFile.getName());
                    } else {
                        // Last ditch fallback
                        manualCopy(tempFile, destFile);
                        tempFile.delete();
                    }
                }
                zis.closeEntry();
            }
            
            zis.close();
            zis = null;
            if (zip.delete()) {
                Log.d(TAG, "Bundle deleted.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Extraction failed: " + e.getMessage());
        } finally {
            if (zis != null) try { zis.close(); } catch (Exception e) {}
        }
    }

    private static void manualCopy(File src, File dst) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[BUFFER_SIZE];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            out.flush();
            try { out.getFD().sync(); } catch (Exception e) {}
            Log.d(TAG, "    Fallback copy success: " + dst.getName());
        } catch (Exception e) {
            Log.e(TAG, "    Fallback copy failed: " + e.getMessage());
        } finally {
            try { if (in != null) in.close(); } catch (Exception e) {}
            try { if (out != null) out.close(); } catch (Exception e) {}
        }
    }
}
