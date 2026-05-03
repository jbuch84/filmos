package com.github.ma1co.pmcademo.app;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BundleManager {
    private static final String TAG = "JPEG.CAM_BUNDLE";
    private static final int BUFFER_SIZE = 8192;

    private static void logToFile(File rootDir, String msg) {
        Log.e(TAG, msg);
        try {
            if (rootDir != null) {
                if (!rootDir.exists()) rootDir.mkdirs();
                File logFile = new File(rootDir, "BUNDLE_DEBUG.TXT");
                FileWriter fw = new FileWriter(logFile, true);
                fw.write(msg + "\n");
                fw.close();
            }
        } catch (IOException e) {
            // Ignore log errors to prevent crashing the app
        }
    }

    public static void extractAllBundles() {
        for (File root : Filepaths.getStorageRoots()) {
            File appDir = new File(root, "JPEGCAM");
            if (appDir.exists() && appDir.isDirectory()) {
                extractBundlesInDir(appDir, appDir);
                File recipeDir = new File(appDir, "RECIPES");
                if (recipeDir.exists() && recipeDir.isDirectory()) {
                    extractBundlesInDir(recipeDir, appDir);
                }
            }
        }
    }

    private static void extractBundlesInDir(File scanDir, File targetRootDir) {
        File[] files = scanDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            String name = file.getName().toLowerCase();
            if (file.isFile() && name.endsWith(".cam") && !name.startsWith(".")) {
                logToFile(targetRootDir, "Found bundle in " + scanDir.getName() + ": " + file.getName());
                extractBundle(file, targetRootDir);
            }
        }
    }

    private static void extractBundle(final File zipFile, final File targetRootDir) {
        // Run in background to avoid UI lockup and filesystem race conditions during boot
        new Thread(new Runnable() {
            @Override
            public void run() {
                ZipInputStream zis = null;
                try {
                    zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)));
                    ZipEntry entry;
                    int fileCounter = 0;

                    while ((entry = zis.getNextEntry()) != null) {
                        String entryName = entry.getName();
                        String lowerName = entryName.toLowerCase();
                        
                        // 1. Skip Directories and Mac Junk
                        if (entry.isDirectory() || lowerName.contains("__macosx") || entryName.contains("/.") || entryName.startsWith(".")) {
                            continue;
                        }

                        // 2. Determine Destination Folder based on Extension (Flattening the ZIP paths)
                        File destDir = targetRootDir;
                        if (lowerName.endsWith(".txt")) {
                            destDir = new File(targetRootDir, "RECIPES");
                        } else if (lowerName.endsWith(".cube") || lowerName.endsWith(".cub")) {
                            destDir = new File(targetRootDir, "LUTS");
                        } else if (lowerName.endsWith(".png")) {
                            destDir = new File(targetRootDir, "GRAIN");
                        }

                        if (!destDir.exists()) destDir.mkdirs();

                        // Get just the filename (e.g. "R_MYLOOK.TXT") ignoring the "RECIPES/" prefix in zip
                        String simpleName = new File(entryName).getName();
                        File destFile = new File(destDir, simpleName);
                        
                        // 3. Sony Android 2.3.7 VFAT bug: write to 8.3 temp file first
                        File tempFile = new File(destDir, String.format("B%07d.TMP", fileCounter++));

                        logToFile(targetRootDir, "Unpacking: " + simpleName + " via " + tempFile.getName());

                        FileOutputStream fos = null;
                        try {
                            fos = new FileOutputStream(tempFile);
                            byte[] buffer = new byte[BUFFER_SIZE];
                            int count;
                            while ((count = zis.read(buffer, 0, BUFFER_SIZE)) != -1) {
                                fos.write(buffer, 0, count);
                            }
                            fos.flush();
                            try { fos.getFD().sync(); } catch (Exception e) {}
                        } catch (Exception e) {
                            logToFile(targetRootDir, "Write failed: " + simpleName + " - " + e.getMessage());
                        } finally {
                            try { if (fos != null) fos.close(); } catch (Exception e) {}
                        }

                        // 4. Finalize via Rename
                        if (tempFile.exists()) {
                            if (destFile.exists()) destFile.delete();
                            if (tempFile.renameTo(destFile)) {
                                logToFile(targetRootDir, "Successfully extracted: " + destFile.getName());
                            } else {
                                logToFile(targetRootDir, "Rename failed for " + destFile.getName() + " - file may be locked.");
                                tempFile.delete(); // Cleanup failed attempt
                            }
                        }
                        zis.closeEntry();
                    }
                    
                    logToFile(targetRootDir, "Bundle Complete: " + zipFile.getName());
                    zis.close();
                    zis = null;
                    zipFile.delete();

                } catch (Exception e) {
                    logToFile(targetRootDir, "Bundle Error: " + zipFile.getName() + " - " + e.getMessage());
                } finally {
                    try { if (zis != null) zis.close(); } catch (Exception e) {}
                }
            }
        }).start();
    }
}
