package com.github.ma1co.pmcademo.app;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BundleManager {
    private static final String TAG = "JPEG.CAM_BUNDLE";
    private static final int BUFFER_SIZE = 8192;
    private static boolean isExtracting = false;
    private static final Set<String> processedBundles = new HashSet<String>();

    private static synchronized void logToFile(File rootDir, String msg) {
        Log.e(TAG, msg);
        try {
            if (rootDir != null) {
                if (!rootDir.exists()) rootDir.mkdirs();
                File logFile = new File(rootDir, "BUNDLE_DEBUG.TXT");
                FileWriter fw = new FileWriter(logFile, true);
                fw.write(msg + "\n");
                fw.close();
            }
        } catch (IOException e) {}
    }

    public static synchronized void extractAllBundles() {
        if (isExtracting) return;
        isExtracting = true;
        processedBundles.clear();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (File root : Filepaths.getStorageRoots()) {
                        File appDir = new File(root, "JPEGCAM");
                        if (appDir.exists() && appDir.isDirectory()) {
                            scanAndExtract(appDir, appDir);
                            scanAndExtract(new File(appDir, "RECIPES"), appDir);
                        }
                    }
                } finally {
                    isExtracting = false;
                }
            }
        }).start();
    }

    private static void scanAndExtract(File scanDir, File targetRootDir) {
        if (!scanDir.exists()) return;
        File[] files = scanDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            String name = file.getName().toLowerCase();
            if (file.isFile() && name.endsWith(".cam") && !name.startsWith(".")) {
                try {
                    String canonicalPath = file.getCanonicalPath();
                    if (processedBundles.contains(canonicalPath)) continue;
                    processedBundles.add(canonicalPath);
                    
                    logToFile(targetRootDir, "--- Processing Bundle: " + file.getName() + " ---");
                    doExtract(file, targetRootDir);
                } catch (IOException e) {
                    logToFile(targetRootDir, "Path error: " + e.getMessage());
                }
            }
        }
    }

    private static void doExtract(File zipFile, File targetRootDir) {
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)));
            ZipEntry entry;
            int counter = 0;

            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                String lowerName = entryName.toLowerCase();

                if (entry.isDirectory() || lowerName.contains("__macosx") || entryName.contains("/.") || entryName.startsWith(".")) {
                    zis.closeEntry();
                    continue;
                }

                File destFile = new File(targetRootDir, entryName);
                File parentDir = destFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

                // 8.3 Temp file for VFAT compatibility
                File tempFile = new File(parentDir, String.format("B%07d.TMP", counter++));
                logToFile(targetRootDir, "  Extracting " + entryName);

                FileOutputStream fos = null;
                boolean success = false;
                try {
                    fos = new FileOutputStream(tempFile);
                    byte[] buf = new byte[BUFFER_SIZE];
                    int len;
                    while ((len = zis.read(buf, 0, BUFFER_SIZE)) != -1) {
                        fos.write(buf, 0, len);
                    }
                    fos.flush();
                    try { fos.getFD().sync(); } catch (Exception e) {}
                    success = true;
                } catch (IOException e) {
                    logToFile(targetRootDir, "    WRITE ERROR: " + e.getMessage());
                } finally {
                    if (fos != null) try { fos.close(); } catch (Exception e) {}
                }

                if (success && tempFile.exists()) {
                    if (destFile.exists()) destFile.delete();
                    if (tempFile.renameTo(destFile)) {
                        logToFile(targetRootDir, "    Saved: " + destFile.getName());
                    } else {
                        logToFile(targetRootDir, "    RENAME FAILED: " + destFile.getName() + " (Attempting fallback copy)");
                        // Final fallback: byte-for-byte copy
                        copyFile(tempFile, destFile);
                        tempFile.delete();
                    }
                }
                zis.closeEntry();
            }
            zis.close();
            zis = null;
            if (zipFile.delete()) {
                logToFile(targetRootDir, "Bundle Deleted Successfully.");
            }
        } catch (Exception e) {
            logToFile(targetRootDir, "FATAL: " + e.getMessage());
        } finally {
            if (zis != null) try { zis.close(); } catch (Exception e) {}
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[BUFFER_SIZE];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            out.flush();
            try { out.getFD().sync(); } catch (Exception e) {}
        } finally {
            in.close();
            out.close();
        }
    }
}
