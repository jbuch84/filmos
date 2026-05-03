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
            // Ignore log errors
        }
    }

    public static void extractAllBundles() {
        for (File root : Filepaths.getStorageRoots()) {
            File appDir = new File(root, "JPEGCAM");
            if (appDir.exists() && appDir.isDirectory()) {
                // Scan JPEGCAM root
                extractBundlesInDir(appDir, appDir);
                // Scan JPEGCAM/RECIPES
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
                logToFile(targetRootDir, "Starting Bundle: " + file.getName() + " on drive " + targetRootDir.getAbsolutePath());
                extractBundle(file, targetRootDir);
            }
        }
    }

    private static void extractBundle(final File zipFile, final File targetRootDir) {
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
                        
                        // 1. Skip Directories and Mac Metadata
                        if (entry.isDirectory() || lowerName.contains("__macosx") || entryName.contains("/.") || entryName.startsWith(".")) {
                            zis.closeEntry();
                            continue;
                        }

                        // 2. Build Destination Path (Respecting ZIP folders)
                        File destFile = new File(targetRootDir, entryName);
                        File parentDir = destFile.getParentFile();
                        if (parentDir != null && !parentDir.exists()) {
                            parentDir.mkdirs();
                        }

                        // 3. Sony VFAT Bug Workaround: Write to 8.3 TMP file first
                        File tempFile = new File(parentDir, String.format("B%07d.TMP", fileCounter++));
                        logToFile(targetRootDir, "  - Unzipping " + entryName + " -> " + tempFile.getName());

                        FileOutputStream fos = null;
                        boolean writeSuccess = false;
                        try {
                            fos = new FileOutputStream(tempFile);
                            byte[] buffer = new byte[BUFFER_SIZE];
                            int count;
                            while ((count = zis.read(buffer, 0, BUFFER_SIZE)) != -1) {
                                fos.write(buffer, 0, count);
                            }
                            fos.flush();
                            try { fos.getFD().sync(); } catch (Exception e) {}
                            writeSuccess = true;
                        } catch (Exception e) {
                            logToFile(targetRootDir, "    WRITE ERROR: " + e.getMessage());
                        } finally {
                            if (fos != null) try { fos.close(); } catch (Exception e) {}
                        }

                        // 4. Rename to Final Long Filename
                        if (writeSuccess && tempFile.exists()) {
                            if (destFile.exists()) destFile.delete();
                            if (tempFile.renameTo(destFile)) {
                                logToFile(targetRootDir, "    OK: Renamed to " + destFile.getName());
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
                    zipFile.delete();
                    logToFile(targetRootDir, "Bundle Complete.");

                } catch (Exception e) {
                    logToFile(targetRootDir, "FATAL BUNDLE ERROR: " + e.getMessage());
                } finally {
                    if (zis != null) try { zis.close(); } catch (Exception e) {}
                }
            }
        }).start();
    }

    private static void copyFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[BUFFER_SIZE];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
            try { out.getFD().sync(); } catch (Exception e) {}
        } finally {
            in.close();
            out.close();
        }
    }
}
