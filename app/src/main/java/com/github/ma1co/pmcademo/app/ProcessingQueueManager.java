package com.github.ma1co.pmcademo.app;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;

public class ProcessingQueueManager {
    private static final String TAG = "JPEG.CAM_QUEUE";
    public static final int MODE_AUTO = 0;
    public static final int MODE_MANUAL = -1;
    private final File queueFile;
    private final ArrayList<Entry> entries = new ArrayList<Entry>();

    public static class Entry {
        public String originalPath;
        public String outDirPath;
        public String lutPath;
        public String lutName;
        public int qualityIndex;
        public int jpegQuality;
        public boolean applyCrop;
        public boolean isDiptych;
        public long scannerStartedMs;
        public long detectedMs;
        public long stableMs;
        public int scannerAttempts;
        public int queueMode = MODE_MANUAL;
        public RTLProfile profile;
    }

    public ProcessingQueueManager() {
        queueFile = new File(Filepaths.getAppDir(), "QUEUE.TXT");
        load();
    }

    public synchronized int getCount() {
        return entries.size();
    }

    public synchronized int getCountForMode(int mode) {
        int count = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (entryMatchesMode(entries.get(i), mode)) count++;
        }
        return count;
    }

    public synchronized Entry peek() {
        if (entries.isEmpty()) return null;
        return entries.get(0);
    }

    public synchronized Entry peekForMode(int mode) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entryMatchesMode(entry, mode)) return entry;
        }
        return null;
    }

    public synchronized ArrayList<Entry> getEntries() {
        ArrayList<Entry> copy = new ArrayList<Entry>();
        for (int i = 0; i < entries.size(); i++) {
            copy.add(copyEntry(entries.get(i)));
        }
        return copy;
    }

    public synchronized ArrayList<Entry> getEntriesForMode(int mode) {
        ArrayList<Entry> copy = new ArrayList<Entry>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entryMatchesMode(entry, mode)) copy.add(copyEntry(entry));
        }
        return copy;
    }

    public synchronized Entry getEntry(int index) {
        if (index < 0 || index >= entries.size()) return null;
        return copyEntry(entries.get(index));
    }

    public synchronized Entry getEntryForMode(int index, int mode) {
        if (index < 0) return null;
        int visible = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (!entryMatchesMode(entry, mode)) continue;
            if (visible == index) return copyEntry(entry);
            visible++;
        }
        return null;
    }

    public synchronized void add(Entry entry) {
        if (entry == null || entry.originalPath == null || entry.originalPath.length() == 0) return;
        entries.add(copyEntry(entry));
        save();
    }

    public synchronized void removeFirst() {
        if (!entries.isEmpty()) {
            entries.remove(0);
            save();
        }
    }

    public synchronized void removeFirstForMode(int mode) {
        for (int i = 0; i < entries.size(); i++) {
            if (entryMatchesMode(entries.get(i), mode)) {
                entries.remove(i);
                save();
                return;
            }
        }
    }

    public synchronized void clear() {
        entries.clear();
        save();
    }

    public synchronized void clearForMode(int mode) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entryMatchesMode(entries.get(i), mode)) entries.remove(i);
        }
        save();
    }

    public synchronized int moveSelectedToFront(boolean[] selected) {
        if (selected == null || selected.length == 0 || entries.isEmpty()) return 0;

        ArrayList<Entry> selectedEntries = new ArrayList<Entry>();
        ArrayList<Entry> remainingEntries = new ArrayList<Entry>();
        for (int i = 0; i < entries.size(); i++) {
            Entry copy = copyEntry(entries.get(i));
            if (i < selected.length && selected[i]) selectedEntries.add(copy);
            else remainingEntries.add(copy);
        }

        if (selectedEntries.isEmpty()) return 0;
        entries.clear();
        entries.addAll(selectedEntries);
        entries.addAll(remainingEntries);
        save();
        return selectedEntries.size();
    }

    public synchronized int moveSelectedToFrontForMode(boolean[] selected, int mode) {
        if (selected == null || selected.length == 0 || entries.isEmpty()) return 0;

        ArrayList<Entry> selectedEntries = new ArrayList<Entry>();
        ArrayList<Entry> remainingEntries = new ArrayList<Entry>();
        int visibleIndex = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry copy = copyEntry(entries.get(i));
            if (entryMatchesMode(copy, mode)) {
                if (visibleIndex < selected.length && selected[visibleIndex]) selectedEntries.add(copy);
                else remainingEntries.add(copy);
                visibleIndex++;
            } else {
                remainingEntries.add(copy);
            }
        }

        if (selectedEntries.isEmpty()) return 0;
        entries.clear();
        entries.addAll(selectedEntries);
        entries.addAll(remainingEntries);
        save();
        return selectedEntries.size();
    }

    public synchronized int removeSelectedForMode(boolean[] selected, int mode) {
        if (selected == null || selected.length == 0 || entries.isEmpty()) return 0;

        int removed = 0;
        int visibleIndex = 0;
        ArrayList<Entry> remainingEntries = new ArrayList<Entry>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entryMatchesMode(entry, mode)) {
                if (visibleIndex < selected.length && selected[visibleIndex]) {
                    removed++;
                } else {
                    remainingEntries.add(copyEntry(entry));
                }
                visibleIndex++;
            } else {
                remainingEntries.add(copyEntry(entry));
            }
        }

        if (removed > 0) {
            entries.clear();
            entries.addAll(remainingEntries);
            save();
        }
        return removed;
    }

    private boolean entryMatchesMode(Entry entry, int mode) {
        if (entry == null) return false;
        if (mode == MODE_MANUAL) return entry.queueMode == MODE_MANUAL;
        return entry.queueMode != MODE_MANUAL;
    }

    private void load() {
        entries.clear();
        if (!queueFile.exists() || queueFile.length() == 0) return;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(queueFile);
            byte[] data = new byte[(int) queueFile.length()];
            int offset = 0;
            while (offset < data.length) {
                int read = fis.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset <= 0) return;
            JSONObject root = new JSONObject(new String(data, 0, offset, "UTF-8"));
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                Entry entry = entryFromJson(arr.optJSONObject(i));
                if (entry != null && entry.originalPath != null && entry.originalPath.length() > 0) {
                    entries.add(entry);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load queue: " + e.getMessage());
            entries.clear();
        } finally {
            try { if (fis != null) fis.close(); } catch (Exception ignored) {}
        }
    }

    private void save() {
        if (entries.isEmpty()) {
            if (queueFile.exists()) queueFile.delete();
            return;
        }

        FileOutputStream fos = null;
        try {
            File parent = queueFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            JSONObject root = new JSONObject();
            root.put("version", 1);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < entries.size(); i++) {
                arr.put(entryToJson(entries.get(i)));
            }
            root.put("items", arr);
            fos = new FileOutputStream(queueFile);
            fos.write(root.toString().getBytes("UTF-8"));
            fos.flush();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save queue: " + e.getMessage());
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }

    private JSONObject entryToJson(Entry entry) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("originalPath", safe(entry.originalPath));
        obj.put("outDirPath", safe(entry.outDirPath));
        obj.put("lutPath", safe(entry.lutPath));
        obj.put("lutName", safe(entry.lutName));
        obj.put("qualityIndex", entry.qualityIndex);
        obj.put("jpegQuality", entry.jpegQuality);
        obj.put("applyCrop", entry.applyCrop);
        obj.put("isDiptych", entry.isDiptych);
        obj.put("scannerStartedMs", entry.scannerStartedMs);
        obj.put("detectedMs", entry.detectedMs);
        obj.put("stableMs", entry.stableMs);
        obj.put("scannerAttempts", entry.scannerAttempts);
        obj.put("queueMode", entry.queueMode);
        obj.put("profile", profileToJson(entry.profile));
        return obj;
    }

    private Entry entryFromJson(JSONObject obj) {
        if (obj == null) return null;
        Entry entry = new Entry();
        entry.originalPath = obj.optString("originalPath", "");
        entry.outDirPath = obj.optString("outDirPath", Filepaths.getGradedDir().getAbsolutePath());
        entry.lutPath = obj.optString("lutPath", "NONE");
        entry.lutName = obj.optString("lutName", "OFF");
        entry.qualityIndex = obj.optInt("qualityIndex", 1);
        entry.jpegQuality = obj.optInt("jpegQuality", 95);
        entry.applyCrop = obj.optBoolean("applyCrop", false);
        entry.isDiptych = obj.optBoolean("isDiptych", false);
        entry.scannerStartedMs = obj.optLong("scannerStartedMs", 0);
        entry.detectedMs = obj.optLong("detectedMs", 0);
        entry.stableMs = obj.optLong("stableMs", 0);
        entry.scannerAttempts = obj.optInt("scannerAttempts", 0);
        entry.queueMode = obj.has("queueMode") ? obj.optInt("queueMode", MODE_MANUAL) : MODE_MANUAL;
        entry.profile = profileFromJson(obj.optJSONObject("profile"));
        return entry;
    }

    private JSONObject profileToJson(RTLProfile p) throws Exception {
        RTLProfile profile = p != null ? p : new RTLProfile();
        JSONObject obj = new JSONObject();
        obj.put("profileName", safe(profile.profileName));
        obj.put("lutIndex", profile.lutIndex);
        obj.put("opacity", profile.opacity);
        obj.put("grain", profile.grain);
        obj.put("grainSize", profile.grainSize);
        obj.put("rollOff", profile.rollOff);
        obj.put("vignette", profile.vignette);
        obj.put("advancedGrainExperimental", profile.advancedGrainExperimental);
        obj.put("whiteBalance", safe(profile.whiteBalance));
        obj.put("wbShift", profile.wbShift);
        obj.put("wbShiftGM", profile.wbShiftGM);
        obj.put("dro", safe(profile.dro));
        obj.put("contrast", profile.contrast);
        obj.put("saturation", profile.saturation);
        obj.put("sharpness", profile.sharpness);
        obj.put("colorMode", safe(profile.colorMode));
        obj.put("colorDepthRed", profile.colorDepthRed);
        obj.put("colorDepthGreen", profile.colorDepthGreen);
        obj.put("colorDepthBlue", profile.colorDepthBlue);
        obj.put("colorDepthCyan", profile.colorDepthCyan);
        obj.put("colorDepthMagenta", profile.colorDepthMagenta);
        obj.put("colorDepthYellow", profile.colorDepthYellow);
        JSONArray matrix = new JSONArray();
        int[] adv = profile.advMatrix != null ? profile.advMatrix : new int[] {100, 0, 0, 0, 100, 0, 0, 0, 100};
        for (int i = 0; i < 9; i++) matrix.put(i < adv.length ? adv[i] : (i % 4 == 0 ? 100 : 0));
        obj.put("advMatrix", matrix);
        obj.put("proColorMode", safe(profile.proColorMode));
        obj.put("pictureEffect", safe(profile.pictureEffect));
        obj.put("peToyCameraTone", safe(profile.peToyCameraTone));
        obj.put("vignetteHardware", profile.vignetteHardware);
        obj.put("softFocusLevel", profile.softFocusLevel);
        obj.put("shadingRed", profile.shadingRed);
        obj.put("shadingBlue", profile.shadingBlue);
        obj.put("sharpnessGain", profile.sharpnessGain);
        obj.put("colorChrome", profile.colorChrome);
        obj.put("chromeBlue", profile.chromeBlue);
        obj.put("shadowToe", profile.shadowToe);
        obj.put("subtractiveSat", profile.subtractiveSat);
        obj.put("halation", profile.halation);
        obj.put("bloom", profile.bloom);
        return obj;
    }

    private RTLProfile profileFromJson(JSONObject obj) {
        RTLProfile p = new RTLProfile();
        if (obj == null) return p;
        p.profileName = obj.optString("profileName", "RECIPE");
        p.lutIndex = obj.optInt("lutIndex", 0);
        p.opacity = obj.optInt("opacity", 100);
        p.grain = obj.optInt("grain", 0);
        p.grainSize = obj.optInt("grainSize", 1);
        p.rollOff = obj.optInt("rollOff", 0);
        p.vignette = obj.optInt("vignette", 0);
        p.advancedGrainExperimental = obj.optInt("advancedGrainExperimental", 1);
        p.whiteBalance = obj.optString("whiteBalance", "AUTO");
        p.wbShift = obj.optInt("wbShift", 0);
        p.wbShiftGM = obj.optInt("wbShiftGM", 0);
        p.dro = obj.optString("dro", "OFF");
        p.contrast = obj.optInt("contrast", 0);
        p.saturation = obj.optInt("saturation", 0);
        p.sharpness = obj.optInt("sharpness", 0);
        p.colorMode = obj.optString("colorMode", "standard");
        p.colorDepthRed = obj.optInt("colorDepthRed", 0);
        p.colorDepthGreen = obj.optInt("colorDepthGreen", 0);
        p.colorDepthBlue = obj.optInt("colorDepthBlue", 0);
        p.colorDepthCyan = obj.optInt("colorDepthCyan", 0);
        p.colorDepthMagenta = obj.optInt("colorDepthMagenta", 0);
        p.colorDepthYellow = obj.optInt("colorDepthYellow", 0);
        p.advMatrix = new int[] {100, 0, 0, 0, 100, 0, 0, 0, 100};
        JSONArray matrix = obj.optJSONArray("advMatrix");
        if (matrix != null && matrix.length() == 9) {
            for (int i = 0; i < 9; i++) p.advMatrix[i] = matrix.optInt(i, p.advMatrix[i]);
        }
        p.proColorMode = obj.optString("proColorMode", "off");
        p.pictureEffect = obj.optString("pictureEffect", "off");
        p.peToyCameraTone = obj.optString("peToyCameraTone", "normal");
        p.vignetteHardware = obj.optInt("vignetteHardware", 0);
        p.softFocusLevel = obj.optInt("softFocusLevel", 1);
        p.shadingRed = obj.optInt("shadingRed", 0);
        p.shadingBlue = obj.optInt("shadingBlue", 0);
        p.sharpnessGain = obj.optInt("sharpnessGain", 0);
        p.colorChrome = obj.optInt("colorChrome", 0);
        p.chromeBlue = obj.optInt("chromeBlue", 0);
        p.shadowToe = obj.optInt("shadowToe", 0);
        p.subtractiveSat = obj.optInt("subtractiveSat", 0);
        p.halation = obj.optInt("halation", 0);
        p.bloom = obj.optInt("bloom", 0);
        return p;
    }

    public static Entry copyEntry(Entry source) {
        Entry copy = new Entry();
        if (source == null) return copy;
        copy.originalPath = source.originalPath;
        copy.outDirPath = source.outDirPath;
        copy.lutPath = source.lutPath;
        copy.lutName = source.lutName;
        copy.qualityIndex = source.qualityIndex;
        copy.jpegQuality = source.jpegQuality;
        copy.applyCrop = source.applyCrop;
        copy.isDiptych = source.isDiptych;
        copy.scannerStartedMs = source.scannerStartedMs;
        copy.detectedMs = source.detectedMs;
        copy.stableMs = source.stableMs;
        copy.scannerAttempts = source.scannerAttempts;
        copy.queueMode = source.queueMode;
        copy.profile = copyProfile(source.profile);
        return copy;
    }

    public static RTLProfile copyProfile(RTLProfile source) {
        RTLProfile copy = new RTLProfile();
        if (source == null) return copy;
        copy.profileName = source.profileName;
        copy.lutIndex = source.lutIndex;
        copy.opacity = source.opacity;
        copy.grain = source.grain;
        copy.grainSize = source.grainSize;
        copy.rollOff = source.rollOff;
        copy.vignette = source.vignette;
        copy.advancedGrainExperimental = source.advancedGrainExperimental;
        copy.whiteBalance = source.whiteBalance;
        copy.wbShift = source.wbShift;
        copy.wbShiftGM = source.wbShiftGM;
        copy.dro = source.dro;
        copy.contrast = source.contrast;
        copy.saturation = source.saturation;
        copy.sharpness = source.sharpness;
        copy.colorMode = source.colorMode;
        copy.colorDepthRed = source.colorDepthRed;
        copy.colorDepthGreen = source.colorDepthGreen;
        copy.colorDepthBlue = source.colorDepthBlue;
        copy.colorDepthCyan = source.colorDepthCyan;
        copy.colorDepthMagenta = source.colorDepthMagenta;
        copy.colorDepthYellow = source.colorDepthYellow;
        copy.advMatrix = new int[9];
        int[] adv = source.advMatrix != null ? source.advMatrix : new int[] {100, 0, 0, 0, 100, 0, 0, 0, 100};
        for (int i = 0; i < 9; i++) copy.advMatrix[i] = i < adv.length ? adv[i] : (i % 4 == 0 ? 100 : 0);
        copy.proColorMode = source.proColorMode;
        copy.pictureEffect = source.pictureEffect;
        copy.peToyCameraTone = source.peToyCameraTone;
        copy.vignetteHardware = source.vignetteHardware;
        copy.softFocusLevel = source.softFocusLevel;
        copy.shadingRed = source.shadingRed;
        copy.shadingBlue = source.shadingBlue;
        copy.sharpnessGain = source.sharpnessGain;
        copy.colorChrome = source.colorChrome;
        copy.chromeBlue = source.chromeBlue;
        copy.shadowToe = source.shadowToe;
        copy.subtractiveSat = source.subtractiveSat;
        copy.halation = source.halation;
        copy.bloom = source.bloom;
        return copy;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
