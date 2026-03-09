package com.github.ma1co.pmcademo.app;

import java.io.File;

public class SonyFileScanner {
    private String dcimRoot;
    private ScannerCallback mCallback;
    private String lastSeenFilePath = "";

    public interface ScannerCallback {
        void onNewPhotoDetected(String filePath);
        boolean isReadyToProcess(); 
    }

    public SonyFileScanner(String path, ScannerCallback callback) {
        File f = new File(path);
        this.dcimRoot = (f.getParent() != null) ? f.getParent() : path;
        this.mCallback = callback;
        findNewestFile(false); 
    }

    public void start() {
    }

    public void stop() {
    }

    public void checkNow() {
        findNewestFile(true);
    }

    private void findNewestFile(boolean triggerCallback) {
        File dcimDir = new File(dcimRoot);
        if (!dcimDir.exists() || !dcimDir.isDirectory()) return;

        File newestFile = null;
        long maxModified = 0;

        File[] subDirs = dcimDir.listFiles();
        if (subDirs != null) {
            for (File dir : subDirs) {
                if (dir.isDirectory() && dir.getName().toUpperCase().endsWith("MSDCF")) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            String name = f.getName().toUpperCase();
                            if (name.endsWith(".JPG") && !name.startsWith("FILM_") && !name.startsWith("PRCS")) {
                                if (f.lastModified() > maxModified) {
                                    maxModified = f.lastModified();
                                    newestFile = f;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (newestFile != null) {
            String currentPath = newestFile.getAbsolutePath();
            if (!currentPath.equals(lastSeenFilePath)) {
                lastSeenFilePath = currentPath;
                if (triggerCallback && mCallback != null && mCallback.isReadyToProcess()) {
                    mCallback.onNewPhotoDetected(currentPath);
                }
            }
        }
    }
}