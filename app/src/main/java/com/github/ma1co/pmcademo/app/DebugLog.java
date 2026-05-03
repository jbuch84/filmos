package com.github.ma1co.pmcademo.app;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Lightweight SD-card debug logger for JPEG.CAM.
 *
 * Writes timestamped lines to JPEGCAM/DEBUG.TXT on the SD card so users
 * can share the file when reporting issues — no ADB or developer tools needed.
 *
 * The file auto-rotates (deletes and restarts) once it exceeds MAX_BYTES.
 * All writes are append-only and thread-safe.
 *
 * Usage:
 *   DebugLog.write("something happened");  // safe to call from any thread
 *   DebugLog.clear();                      // let the user wipe it from the menu
 */
public class DebugLog {

    private static final String TAG       = "JPEG.CAM";
    private static final String FILENAME  = "DEBUG.TXT";
    private static final int    MAX_BYTES = 60 * 1024; // 60 KB — rotate above this

    private static final Object sLock = new Object();
    private static File sFile = null;

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /** Appends one timestamped line. Safe to call from any thread at any time. */
    public static void write(String msg) {
        synchronized (sLock) {
            File f = getFile();
            if (f == null) return;
            try {
                String ts   = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
                String line = ts + "  " + msg + "\n";
                FileOutputStream fos = new FileOutputStream(f, /*append=*/true);
                fos.write(line.getBytes("UTF-8"));
                fos.close();
            } catch (Exception e) {
                Log.e(TAG, "DebugLog write error: " + e.getMessage());
            }
        }
    }

    /**
     * Writes the app-startup banner (device info, SD root, app version).
     * Call once from RecipeManager constructor so it appears at the top of every session.
     */
    public static void writeStartupBanner() {
        synchronized (sLock) {
            rotateIfNeeded();
        }
        write("========================================");
        write("JPEG.CAM  Android " + android.os.Build.VERSION.RELEASE
                + "  Model: " + android.os.Build.MODEL);
        write("SD root: " + Filepaths.getStorageRoot().getAbsolutePath());
        write("App dir: " + Filepaths.getAppDir().getAbsolutePath());
        write("========================================");
    }

    /** Deletes the log file (call when user taps a "Clear debug log" menu item). */
    public static void clear() {
        synchronized (sLock) {
            File f = getFile();
            if (f != null && f.exists()) f.delete();
            sFile = null;
        }
        write("Log cleared by user.");
    }

    /** Returns the log File so the web server or a menu can expose it. */
    public static File getLogFile() {
        synchronized (sLock) {
            return getFile();
        }
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    private static File getFile() {
        if (sFile == null) {
            try {
                File dir = Filepaths.getAppDir();
                sFile = new File(dir, FILENAME);
            } catch (Exception e) {
                return null;
            }
        }
        return sFile;
    }

    private static void rotateIfNeeded() {
        File f = getFile();
        if (f != null && f.exists() && f.length() > MAX_BYTES) {
            f.delete();
            sFile = null; // force re-resolve on next write
        }
    }
}
