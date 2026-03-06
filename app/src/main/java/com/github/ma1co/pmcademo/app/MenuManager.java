package com.github.ma1co.pmcademo.app;

import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Arrays;

/**
 * filmOS Manager: Menu UI Logic
 * Handles Page navigation and Value labels for the 4-page settings menu.
 * Ensures 100% feature parity with the original settings list.
 */
public class MenuManager {
    private int currentPage = 1;
    private int selection = 0;
    private int itemCount = 0;

    private final String[] intensityLabels = {"OFF", "LOW", "LOW+", "MID", "MID+", "HIGH"};
    private final String[] grainSizeLabels = {"SM", "MED", "LG"};
    private final String[] wbLabels = {"AUTO", "DAY", "SHD", "CLD", "INC", "FLR"};
    private final String[] droLabels = {"OFF", "AUTO", "LV1", "LV2", "LV3", "LV4", "LV5"};

    public int getCurrentPage() { return currentPage; }
    public int getSelection() { return selection; }
    public void setPage(int page) { this.currentPage = page; }
    
    public void moveSelection(int d) {
        selection += d;
        if (selection < -1) selection = itemCount - 1;
        if (selection >= itemCount) selection = -1;
    }

    public void cyclePage(int d) {
        currentPage = (currentPage + d + 3) % 4 + 1;
        selection = -1; // Keep focus on header when switching pages
    }

    /**
     * Maps RecipeManager data to the UI rows.
     * Re-integrates all variables: WB Shift, DRO, Contrast, Saturation, Sharpness, etc.
     */
    public void render(TextView title, TextView[] pages, LinearLayout[] rows, 
                       TextView[] labels, TextView[] values, RecipeManager recipes, ConnectivityManager conn) {
        
        RTLProfile p = recipes.getCurrentProfile();
        
        // Highlight current page number
        for (int i = 0; i < 4; i++) {
            pages[i].setTextColor((currentPage == i + 1) ? Color.rgb(230, 50, 15) : Color.WHITE);
        }

        // Reset rows
        for (LinearLayout row : rows) row.setVisibility(View.GONE);

        if (currentPage == 1) { // RTL Base
            title.setText("RTL (Base)");
            itemCount = 7;
            setupRow(0, "RTL Slot", String.valueOf(recipes.getCurrentSlot() + 1), labels, values, rows);
            setupRow(1, "LUT", recipes.getRecipeNames().get(p.lutIndex), labels, values, rows);
            setupRow(2, "Opacity", p.opacity + "%", labels, values, rows);
            setupRow(3, "Grain", intensityLabels[p.grain], labels, values, rows);
            setupRow(4, "Grain Size", grainSizeLabels[p.grainSize], labels, values, rows);
            setupRow(5, "Roll-off", intensityLabels[p.rollOff], labels, values, rows);
            setupRow(6, "Vignette", intensityLabels[p.vignette], labels, values, rows);
        } 
        else if (currentPage == 2) { // Color & Detail
            title.setText("RTL (Color)");
            itemCount = 7;
            setupRow(0, "White Balance", p.whiteBalance, labels, values, rows);
            setupRow(1, "WB Shift A-B", formatSign(p.wbShift), labels, values, rows);
            setupRow(2, "WB Shift G-M", formatSign(p.wbShiftGM), labels, values, rows);
            setupRow(3, "DRO", p.dro, labels, values, rows);
            setupRow(4, "Contrast", formatSign(p.contrast), labels, values, rows);
            setupRow(5, "Saturation", formatSign(p.saturation), labels, values, rows);
            setupRow(6, "Sharpness", formatSign(p.sharpness), labels, values, rows);
        }
        else if (currentPage == 3) { // Global
            title.setText("Global Settings");
            itemCount = 5;
            String[] qLabels = {"PROXY (1.5MP)", "HIGH (6MP)", "ULTRA (24MP)"};
            setupRow(0, "Quality", qLabels[recipes.getQualityIndex()], labels, values, rows);
            setupRow(1, "Base Scene", "PASM", labels, values, rows);
            setupRow(2, "Focus Meter", "N/A", labels, values, rows); // Logic handled in Activity
            setupRow(3, "Cinema Matte", "N/A", labels, values, rows);
            setupRow(4, "Grid Lines", "N/A", labels, values, rows);
        }
        else if (currentPage == 4) { // Connections
            title.setText("Connections");
            itemCount = 3;
            setupRow(0, "Hotspot", conn.getConnStatusHotspot(), labels, values, rows);
            setupRow(1, "Wi-Fi", conn.getConnStatusWifi(), labels, values, rows);
            setupRow(2, "Stop All", "", labels, values, rows);
        }

        // Apply selection color
        for (int i = 0; i < itemCount; i++) {
            rows[i].setBackgroundColor((i == selection) ? Color.rgb(230, 50, 15) : Color.TRANSPARENT);
        }
    }

    private void setupRow(int i, String label, String value, TextView[] labels, TextView[] values, LinearLayout[] rows) {
        labels[i].setText(label);
        values[i].setText(value);
        rows[i].setVisibility(View.VISIBLE);
    }

    private String formatSign(int val) { return val > 0 ? "+" + val : String.valueOf(val); }
}