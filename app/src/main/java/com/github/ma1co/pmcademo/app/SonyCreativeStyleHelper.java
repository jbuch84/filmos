package com.github.ma1co.pmcademo.app;

import android.hardware.Camera;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Discovers and applies native Sony Creative Style / Color Mode values.
 *
 * Purpose:
 * - Separate UI labels from actual hardware tokens
 * - Prefer native creative-style when available
 * - Fall back to color-mode when needed
 * - Verify what the camera really accepted
 */
public final class SonyCreativeStyleHelper {

    private static final String TAG = "JPEG.CAM";
    private static final String DEBUG_FILENAME = "creative-style-debug.txt";
    private static final String DEBUG_FILENAME_SHORT = "CSDEBUG.TXT";

    public static final class StyleOption {
        public final String label;
        public final String token;

        public StyleOption(String label, String token) {
            this.label = label;
            this.token = token;
        }
    }

    public static final class DiscoveryResult {
        public final boolean hasCreativeStyle;
        public final boolean hasColorMode;
        public final List<StyleOption> options;
        public final String preferredKey;

        public DiscoveryResult(boolean hasCreativeStyle,
                               boolean hasColorMode,
                               List<StyleOption> options,
                               String preferredKey) {
            this.hasCreativeStyle = hasCreativeStyle;
            this.hasColorMode = hasColorMode;
            this.options = options;
            this.preferredKey = preferredKey;
        }
    }

    private SonyCreativeStyleHelper() {}

    public static DiscoveryResult discover(Camera.Parameters p) {
        if (p == null) {
            return new DiscoveryResult(false, false, new ArrayList<StyleOption>(), null);
        }

        boolean hasCreativeStyle = p.get("creative-style") != null;
        boolean hasColorMode = p.get("color-mode") != null;
        String preferredKey = hasCreativeStyle ? "creative-style" : (hasColorMode ? "color-mode" : null);

        List<String> rawTokens = new ArrayList<String>();
        addCsvIfPresent(rawTokens, p.get("creative-style-values"));
        addCsvIfPresent(rawTokens, p.get("creative-style-supported"));
        addCsvIfPresent(rawTokens, p.get("color-mode-values"));
        addCsvIfPresent(rawTokens, p.get("color-mode-supported"));

        if (rawTokens.isEmpty()) {
            rawTokens.addAll(Arrays.asList(
                    "standard",
                    "vivid",
                    "neutral",
                    "clear",
                    "deep",
                    "light",
                    "portrait",
                    "landscape",
                    "sunset",
                    "night-view",
                    "autumn-leaves",
                    "black-and-white",
                    "sepia"
            ));
        }

        Map<String, StyleOption> deduped = new LinkedHashMap<String, StyleOption>();
        for (String token : rawTokens) {
            if (token == null) {
                continue;
            }
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String norm = trimmed.toLowerCase(Locale.US);
            if (!deduped.containsKey(norm)) {
                deduped.put(norm, new StyleOption(makeLabel(trimmed), trimmed));
            }
        }

        List<StyleOption> options = new ArrayList<StyleOption>(deduped.values());

        logDebug("CreativeStyle discover:"
                + " hasCreativeStyle=" + hasCreativeStyle
                + " hasColorMode=" + hasColorMode
                + " preferredKey=" + preferredKey
                + " options=" + toDebugList(options));

        return new DiscoveryResult(hasCreativeStyle, hasColorMode, options, preferredKey);
    }

    public static String resolveTokenFromLabel(Camera.Parameters p, String uiLabel) {
        DiscoveryResult result = discover(p);
        if (uiLabel == null) {
            return null;
        }

        String normalizedLabel = normalize(uiLabel);
        for (StyleOption option : result.options) {
            if (normalize(option.label).equals(normalizedLabel)) {
                return option.token;
            }
        }

        String legacyAlias = mapLegacyLabelToToken(normalizedLabel);
        if (legacyAlias != null) {
            for (StyleOption option : result.options) {
                if (normalize(option.token).equals(normalize(legacyAlias))) {
                    return option.token;
                }
            }
        }

        String guess = uiLabel.trim()
                .toLowerCase(Locale.US)
                .replace("&", "and")
                .replace(" ", "-");
        for (StyleOption option : result.options) {
            if (normalize(option.token).equals(normalize(guess))) {
                return option.token;
            }
        }

        return null;
    }

    public static void applyNativeStyle(Camera.Parameters p, String token) {
        if (p == null || token == null || token.length() == 0) {
            return;
        }

        // v1.6 wrote the same token to both legacy Sony keys when present.
        // Preserve that behavior so existing recipes keep the same capture base.
        if (p.get("creative-style") != null) p.set("creative-style", token);
        if (p.get("color-mode") != null) p.set("color-mode", token);
    }

    public static String getCurrentNativeStyle(Camera.Parameters p) {
        if (p == null) {
            return null;
        }
        String cs = p.get("creative-style");
        if (cs != null && cs.length() > 0) {
            return cs;
        }
        String cm = p.get("color-mode");
        if (cm != null && cm.length() > 0) {
            return cm;
        }
        return null;
    }

    private static void addCsvIfPresent(List<String> out, String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return;
        }
        String[] parts = csv.split(",");
        for (String part : parts) {
            if (part != null) {
                out.add(part.trim());
            }
        }
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.trim()
                .toLowerCase(Locale.US)
                .replace("&", "and")
                .replace("_", "-")
                .replace(" ", "-");
    }

    private static String mapLegacyLabelToToken(String normalizedLabel) {
        if ("autumn-leaves".equals(normalizedLabel)) {
            return "red-leaves";
        }
        if ("night-scene".equals(normalizedLabel) || "night-view".equals(normalizedLabel)) {
            return "night";
        }
        if ("black-and-white".equals(normalizedLabel)) {
            return "mono";
        }
        return null;
    }

    private static String makeLabel(String token) {
        String t = token.trim().replace('_', ' ').replace('-', ' ');
        String lower = t.toLowerCase(Locale.US);

        if ("black and white".equals(lower)) {
            return "Black & White";
        }
        if ("night view".equals(lower)) {
            return "Night Scene";
        }

        String[] words = lower.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (words[i].isEmpty()) {
                continue;
            }
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(words[i].charAt(0)));
            if (words[i].length() > 1) {
                sb.append(words[i].substring(1));
            }
        }
        return sb.toString();
    }

    private static String toDebugList(List<StyleOption> options) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(options.get(i).label).append("=").append(options.get(i).token);
        }
        sb.append("]");
        return sb.toString();
    }

    public static void logDebug(String message) {
        Log.d(TAG, message);
    }

    public static void logWarn(String message) {
        Log.w(TAG, message);
    }

    public static void logError(String message, Throwable t) {
        Log.e(TAG, message, t);
    }
}
