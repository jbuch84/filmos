package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.view.Gravity;
import android.graphics.Typeface;
import android.os.Handler;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * JPEG.CAM Controller: Heads-Up Display (HUD)
 *
 * Owns all state, views, and logic for the 11-mode parameter overlay system.
 * Extracted from MainActivity as part of the God Class decomposition (Phase 5).
 *
 * HUD Modes:
 *   0 - BIONZ RGB Color Matrix (9-cell grid + preset scrolling)
 *   1 - 6-Axis Color Depth
 *   2 - White Balance Grid (special: cursor-based, not cell-based)
 *   3 - Tone & Style (contrast / saturation / sharpness)
 *   4 - Edge Shading (lens shading correction)
 *   5 - Picture Effect Parameters
 *   6 - Foundation Base (creative style + micro-contrast)
 *   7 - White Balance (Kelvin temperature)
 *   8 - Picture Effect selector
 *   9 - DRO (Dynamic Range Optimizer)
 *  10 - Recipe Vault Manager
 */
public class HudController {
    private static final int SEL_BACK = -2;
    private static final int SEL_MATRIX_SAVED = -1;
    private static final int SEL_MATRIX_SAVE_NEW = -3;

    // -----------------------------------------------------------------------
    // Host callback
    // -----------------------------------------------------------------------
    public interface HostCallback {
        RecipeManager    getRecipeManager();
        MatrixManager    getMatrixManager();
        MenuController   getMenuController();
        TextView         getTvTopStatus();
        Handler          getUiHandler();
        Typeface         getDigitalFont();
        void             onHudClosed();           // restore menu, re-render
        void             onLutPreloadNeeded();
        void             scheduleHardwareApply(); // delayed applyHardwareRecipe
        void             applyHardwareRecipeNow();
    }

    // -----------------------------------------------------------------------
    // Owned state
    // -----------------------------------------------------------------------
    private boolean  active              = false;
    private int      selection           = 0;
    private int      mode                = 0;
    private boolean  updatePending       = false;
    private boolean  valueEditing        = false;
    private boolean  flashVisible        = true;
    private long     adjustingUntilMs    = 0L;
    private static final int FLASH_INTERVAL_MS = 420;
    private static final int ADJUST_CONFIRM_MS = 650;

    // Matrix scrolling helpers
    private boolean  isScrollingMatrices = false;
    private int      activeMatrixIndex   = 0;

    // Vault state
    private List<RecipeManager.VaultItem> vaultItems = new ArrayList<>();
    private int      vaultIndex          = 0;
    private boolean  recipeLoadBrowser   = false;
    private boolean  recipeDeleteBrowser = false;
    private int      recipeListOffset    = 0;

    // -----------------------------------------------------------------------
    // Owned views
    // -----------------------------------------------------------------------
    private final LinearLayout   overlay;          // 9-cell row
    private final LinearLayout[] cells  = new LinearLayout[9];
    private final TextView[]     cellLabels = new TextView[9];
    private final TextView[]     cellValues = new TextView[9];
    private final LinearLayout   header;
    private final TextView       headerBack;
    private final TextView       headerTitle;
    private final TextView       tooltip;
    private final LinearLayout   matrixActions;
    private final TextView       matrixSavedAction;
    private final TextView       matrixSaveAction;
    private final FrameLayout    wbGrid;
    private final View           wbCursor;
    private final TextView       wbValueText;

    private final HostCallback host;
    private final Runnable flashRunnable = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (isAdjustingNow()) {
                flashVisible = true;
                refresh();
                host.getUiHandler().postDelayed(this, 120);
                return;
            }
            flashVisible = !flashVisible;
            refresh();
            host.getUiHandler().postDelayed(this, FLASH_INTERVAL_MS);
        }
    };

    // -----------------------------------------------------------------------
    // Constructor — builds HUD view tree and injects into mainUIContainer
    // -----------------------------------------------------------------------
    public HudController(Context ctx, FrameLayout mainUIContainer, HostCallback host) {
        this.host = host;
        Typeface font = host.getDigitalFont();

        header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(10, 8, 10, 0);
        header.setVisibility(View.GONE);

        headerBack = new TextView(ctx);
        headerBack.setText("BACK");
        headerBack.setGravity(Gravity.CENTER);
        headerBack.setPadding(14, 7, 14, 7);
        UiTheme.applyStatusText(headerBack, 13, font);
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(96, -2);
        backLp.setMargins(0, 0, 12, 0);
        header.addView(headerBack, backLp);

        headerTitle = new TextView(ctx);
        headerTitle.setGravity(Gravity.CENTER_VERTICAL);
        headerTitle.setPadding(8, 7, 8, 7);
        UiTheme.applyStatusText(headerTitle, 15, font);
        header.addView(headerTitle, new LinearLayout.LayoutParams(0, -2, 1.0f));

        FrameLayout.LayoutParams headerLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        headerLp.setMargins(8, 8, 8, 0);
        mainUIContainer.addView(header, headerLp);

        // 9-cell horizontal overlay (pinned to bottom)
        overlay = new LinearLayout(ctx);
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        UiTheme.panel(overlay);
        overlay.setPadding(12, 14, 12, 14);
        overlay.setVisibility(View.GONE);
        for (int i = 0; i < 9; i++) {
            cells[i] = new LinearLayout(ctx);
            cells[i].setOrientation(LinearLayout.VERTICAL);
            cells[i].setGravity(Gravity.CENTER);
            cells[i].setPadding(6, 8, 6, 8);
            cellLabels[i] = new TextView(ctx); cellLabels[i].setTextColor(UiTheme.TEXT_MUTED); cellLabels[i].setTextSize(13);
            if (font != null) cellLabels[i].setTypeface(font); else cellLabels[i].setTypeface(Typeface.DEFAULT_BOLD);
            cellValues[i] = new TextView(ctx); cellValues[i].setTextColor(UiTheme.TEXT); cellValues[i].setTextSize(18);
            if (font != null) cellValues[i].setTypeface(font); else cellValues[i].setTypeface(Typeface.DEFAULT_BOLD);
            cells[i].addView(cellLabels[i]); cells[i].addView(cellValues[i]);
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, -2, 1.0f);
            cellLp.setMargins(3, 0, 3, 0);
            overlay.addView(cells[i], cellLp);
        }
        FrameLayout.LayoutParams overlayLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        overlayLp.setMargins(0, 0, 0, 25);
        mainUIContainer.addView(overlay, overlayLp);

        // Tooltip text (above overlay)
        tooltip = new TextView(ctx);
        tooltip.setTextColor(UiTheme.TEXT_MUTED); tooltip.setTextSize(12);
        tooltip.setGravity(Gravity.CENTER); tooltip.setPadding(14, 9, 14, 9);
        UiTheme.softPanel(tooltip);
        tooltip.setVisibility(View.GONE);
        FrameLayout.LayoutParams ttLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        ttLp.setMargins(0, 0, 0, 188);
        mainUIContainer.addView(tooltip, ttLp);

        matrixActions = new LinearLayout(ctx);
        matrixActions.setOrientation(LinearLayout.HORIZONTAL);
        matrixActions.setGravity(Gravity.CENTER);
        matrixActions.setPadding(12, 0, 12, 0);
        matrixActions.setVisibility(View.GONE);
        matrixSavedAction = makeMatrixAction(ctx, "SAVED MATRIX\nNONE", font);
        matrixSaveAction = makeMatrixAction(ctx, "SAVE NEW\nNAME", font);
        LinearLayout.LayoutParams savedActionLp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        savedActionLp.setMargins(5, 0, 5, 0);
        matrixActions.addView(matrixSavedAction, savedActionLp);
        LinearLayout.LayoutParams saveActionLp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        saveActionLp.setMargins(5, 0, 5, 0);
        matrixActions.addView(matrixSaveAction, saveActionLp);
        FrameLayout.LayoutParams matrixActionsLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        matrixActionsLp.setMargins(0, 0, 0, 130);
        mainUIContainer.addView(matrixActions, matrixActionsLp);

        // WB grid (mode 2 — special cursor UI)
        wbGrid = new FrameLayout(ctx);
        UiTheme.panel(wbGrid);
        wbGrid.setVisibility(View.GONE);
        View vAxis = new View(ctx); vAxis.setBackgroundColor(UiTheme.BORDER);
        wbGrid.addView(vAxis, new FrameLayout.LayoutParams(2, 280, Gravity.CENTER));
        View hAxis = new View(ctx); hAxis.setBackgroundColor(UiTheme.BORDER);
        wbGrid.addView(hAxis, new FrameLayout.LayoutParams(280, 2, Gravity.CENTER));
        TextView lG = makeLabel(ctx,"G"); wbGrid.addView(lG, new FrameLayout.LayoutParams(-2,-2, Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        TextView lM = makeLabel(ctx,"M"); wbGrid.addView(lM, new FrameLayout.LayoutParams(-2,-2, Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));
        TextView lB = makeLabel(ctx,"B"); FrameLayout.LayoutParams pB = new FrameLayout.LayoutParams(-2,-2,Gravity.LEFT|Gravity.CENTER_VERTICAL); pB.setMargins(10,0,0,0); wbGrid.addView(lB, pB);
        TextView lA = makeLabel(ctx,"A"); FrameLayout.LayoutParams pA = new FrameLayout.LayoutParams(-2,-2,Gravity.RIGHT|Gravity.CENTER_VERTICAL); pA.setMargins(0,0,10,0); wbGrid.addView(lA, pA);
        wbValueText = new TextView(ctx); wbValueText.setTextColor(UiTheme.ACCENT); wbValueText.setTextSize(16);
        if (font != null) wbValueText.setTypeface(font); else wbValueText.setTypeface(Typeface.DEFAULT_BOLD);
        FrameLayout.LayoutParams pVal = new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT); pVal.setMargins(0,10,15,0);
        wbGrid.addView(wbValueText, pVal);
        wbCursor = new View(ctx); wbCursor.setBackgroundColor(UiTheme.ACCENT);
        FrameLayout.LayoutParams cursorLp = new FrameLayout.LayoutParams(14,14,Gravity.TOP|Gravity.LEFT); cursorLp.setMargins(153,153,0,0);
        wbGrid.addView(wbCursor, cursorLp);
        mainUIContainer.addView(wbGrid, new FrameLayout.LayoutParams(320,320,Gravity.CENTER));
    }

    private TextView makeLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx); tv.setText(text); tv.setTextColor(UiTheme.TEXT); return tv;
    }

    private TextView makeMatrixAction(Context ctx, String text, Typeface font) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(10, 7, 10, 7);
        tv.setSingleLine(false);
        tv.setTextColor(UiTheme.TEXT_MUTED);
        tv.setTextSize(13);
        tv.setShadowLayer(2, 0, 0, UiTheme.SHADOW);
        tv.setTypeface(font != null ? font : Typeface.DEFAULT_BOLD);
        UiTheme.actionPanel(tv, UiTheme.ACCENT, false, true);
        return tv;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------
    /** View accessors — used by MainActivity for visibility coordination. */
    public LinearLayout getOverlay()    { return overlay; }
    public LinearLayout getHeader()     { return header; }
    public FrameLayout  getWbGrid()     { return wbGrid; }
    public View         getWbCursor()   { return wbCursor; }
    public TextView     getWbValueText(){ return wbValueText; }
    public TextView     getTooltip()    { return tooltip; }

    public boolean isActive()    { return active; }
    public int     getMode()     { return mode; }
    public int     getSelection(){ return selection; }
    public void    setSelection(int sel) { selection = sel; markNavigating(); }
    public boolean isValueEditing() { return valueEditing; }
    public boolean isMatrixSaveAction() { return mode == 0 && selection == SEL_MATRIX_SAVE_NEW; }

    /** Public immediate refresh (for use from MainActivity enter/exit logic). */
    public void update()         { refresh(); }

    /** Vault accessors — used by MainActivity onEnterPressed mode-10 block. */
    public List<RecipeManager.VaultItem> getVaultItems() { return vaultItems; }
    public int  getVaultIndex()   { return vaultIndex; }
    public void setVaultIndex(int idx) { vaultIndex = idx; }
    public void refreshVaultItems() {
        vaultItems = host.getRecipeManager().getVaultItems();
        if (vaultIndex >= vaultItems.size() || vaultIndex < 0) vaultIndex = 0;
    }

    public boolean isRecipeLoadBrowser() { return recipeLoadBrowser; }
    public boolean isRecipeDeleteBrowser() { return recipeDeleteBrowser; }

    public int getVaultRecipeCount() {
        if (vaultItems == null || vaultItems.isEmpty()) refreshVaultItems();
        if (vaultItems == null || vaultItems.isEmpty()) return 0;
        if (vaultItems.size() == 1 && "NONE".equals(vaultItems.get(0).filename)) return 0;
        return vaultItems.size();
    }

    public int getRecipeBrowserSelectedVaultIndex() {
        refreshVaultItems();
        int count = getVaultRecipeCount();
        if (count <= 0) return -1;
        if (host.getMenuController().isConfirmingDelete()) {
            return vaultIndex >= 0 && vaultIndex < count ? vaultIndex : -1;
        }
        if (selection >= 0 && selection < count) return selection;
        if (vaultIndex >= 0 && vaultIndex < count) return vaultIndex;
        return 0;
    }

    public void openRecipeLoadBrowser() {
        recipeLoadBrowser = true;
        recipeDeleteBrowser = false;
        valueEditing = false;
        refreshVaultItems();
        selection = getVaultRecipeCount() > 0 ? Math.max(0, Math.min(vaultIndex, getVaultRecipeCount() - 1)) : 0;
        recipeListOffset = 0;
        clampRecipeBrowserWindow();
        previewRecipeBrowserSelection();
        markNavigating();
        refresh();
    }

    public void closeRecipeLoadBrowser() {
        recipeLoadBrowser = false;
        valueEditing = false;
        selection = 1;
        markNavigating();
        refresh();
    }

    public void openRecipeDeleteBrowser() {
        recipeDeleteBrowser = true;
        recipeLoadBrowser = false;
        valueEditing = false;
        refreshVaultItems();
        selection = getVaultRecipeCount() > 0 ? Math.max(0, Math.min(vaultIndex, getVaultRecipeCount() - 1)) : 0;
        recipeListOffset = 0;
        clampRecipeBrowserWindow();
        markNavigating();
        refresh();
    }

    public void closeRecipeDeleteBrowser() {
        recipeDeleteBrowser = false;
        valueEditing = false;
        selection = 3;
        markNavigating();
        refresh();
    }

    public void beginRecipeDeleteConfirm() {
        int idx = getRecipeBrowserSelectedVaultIndex();
        if (idx >= 0) vaultIndex = idx;
        selection = 0;
        valueEditing = false;
        markNavigating();
        refresh();
    }

    public void refreshRecipeBrowserAfterDelete() {
        refreshVaultItems();
        int count = getVaultRecipeCount();
        if (count <= 0) {
            vaultIndex = 0;
            selection = 0;
            recipeListOffset = 0;
        } else {
            if (vaultIndex >= count) vaultIndex = count - 1;
            if (vaultIndex < 0) vaultIndex = 0;
            selection = vaultIndex;
            clampRecipeBrowserWindow();
            if (recipeLoadBrowser) previewRecipeBrowserSelection();
        }
        markNavigating();
        refresh();
    }

    /** Open a HUD mode at selection 0. */
    public void launch(int hudMode)                     { launch(hudMode, 0); }

    /** Open a HUD mode at a specific default selection. */
    public void launch(int hudMode, int defaultSel) {
        active    = true;
        mode      = hudMode;
        selection = defaultSel;
        valueEditing = false;
        recipeLoadBrowser = false;
        recipeDeleteBrowser = false;
        recipeListOffset = 0;
        markNavigating();
        host.getMenuController().setConfirmingDelete(false);
        host.getMenuController().getContainer().setVisibility(View.GONE);
        if (host.getTvTopStatus() != null) host.getTvTopStatus().setVisibility(View.GONE);
        header.setVisibility(View.VISIBLE);

        // NEW: Sync the vault cursor to your currently active recipe
        if (hudMode == 10) {
            refreshVaultItems();
            String activeName = host.getRecipeManager().getCurrentProfile().profileName;
            vaultIndex = 0;
            for (int i = 0; i < vaultItems.size(); i++) {
                if (vaultItems.get(i).profileName.equals(activeName)) {
                    vaultIndex = i;
                    break;
                }
            }
        }

        if (hudMode == 2) {
            overlay.setVisibility(View.GONE);
            if (tooltip != null) tooltip.setVisibility(View.GONE);
            wbGrid.setVisibility(View.VISIBLE);
        } else {
            overlay.setVisibility(View.VISIBLE);
            wbGrid.setVisibility(View.GONE);
        }
        refresh();
        startFlash();
    }

    /** Close HUD cleanly, restoring menu behind it. */
    public void close() {
        active = false;
        valueEditing = false;
        recipeLoadBrowser = false;
        recipeDeleteBrowser = false;
        recipeListOffset = 0;
        stopFlash();
        header.setVisibility(View.GONE);
        overlay.setVisibility(View.GONE);
        if (tooltip != null) tooltip.setVisibility(View.GONE);
        matrixActions.setVisibility(View.GONE);
        wbGrid.setVisibility(View.GONE);
        host.onHudClosed();
    }

    /** Reset HUD state without triggering onHudClosed — used when menu opens over an active HUD. */
    public void reset() {
        active = false;
        valueEditing = false;
        recipeLoadBrowser = false;
        recipeDeleteBrowser = false;
        recipeListOffset = 0;
        stopFlash();
        header.setVisibility(View.GONE);
        overlay.setVisibility(View.GONE);
        if (tooltip != null) tooltip.setVisibility(View.GONE);
        matrixActions.setVisibility(View.GONE);
        wbGrid.setVisibility(View.GONE);
    }

    /** Hide all HUD overlays without triggering close callback (for updateMainHUD). */
    public void hideOverlays() {
        stopFlash();
        header.setVisibility(View.GONE);
        overlay.setVisibility(View.GONE);
        if (tooltip != null) tooltip.setVisibility(View.GONE);
        matrixActions.setVisibility(View.GONE);
        wbGrid.setVisibility(View.GONE);
    }

    /** Schedule a debounced refresh (100ms) — used from hardware state callbacks. */
    public void requestUpdate() {
        if (!updatePending) {
            updatePending = true;
            host.getUiHandler().postDelayed(new Runnable() {
                @Override public void run() { updatePending = false; refresh(); }
            }, 100);
        }
    }

    // -----------------------------------------------------------------------
    // Input handlers — return true if consumed
    // -----------------------------------------------------------------------
    public boolean handleUp() {
        if (!active) return false;
        if (valueEditing) {
            if (mode == 2) handleWbAdjustment(0, 1);
            else handleAdjustment(1);
            return true;
        }
        moveSelection(-1);
        return true;
    }

    public boolean handleDown() {
        if (!active) return false;
        if (valueEditing) {
            if (mode == 2) handleWbAdjustment(0, -1);
            else handleAdjustment(-1);
            return true;
        }
        moveSelection(1);
        return true;
    }

    public boolean handleLeft() {
        if (!active) return false;
        if (valueEditing) {
            if (mode == 2) handleWbAdjustment(-1, 0);
            else handleAdjustment(-1);
            return true;
        }
        moveSelection(-1);
        return true;
    }

    public boolean handleRight() {
        if (!active) return false;
        if (valueEditing) {
            if (mode == 2) handleWbAdjustment(1, 0);
            else handleAdjustment(1);
            return true;
        }
        moveSelection(1);
        return true;
    }

    public boolean handleDial(int dir) {
        if (!active) return false;
        if (!valueEditing) {
            moveSelection(dir > 0 ? 1 : -1);
            return true;
        }
        if (mode == 2) { handleWbAdjustment(dir, 0); return true; }
        handleAdjustment(dir); return true;
    }

    public boolean handleEnter() {
        if (!active) return false;
        if (selection == -2) {
            close();
            return true;
        }
        if (mode == 0 && selection == SEL_MATRIX_SAVE_NEW) return false;
        if (mode == 10) return false;
        valueEditing = !valueEditing;
        if (valueEditing) markEditing();
        else {
            markNavigating();
            host.scheduleHardwareApply();
        }
        refresh();
        return true;
    }

    private void moveSelection(int delta) {
        int maxIdx = maxSelectionForMode();
        if (mode == 0) {
            moveMatrixSelection(delta);
        } else if (selection == -2) {
            selection = delta > 0 ? 0 : maxIdx;
        } else {
            selection += delta;
            if (selection < 0) selection = -2;
            if (selection > maxIdx) selection = -2;
        }
        if (mode == 10 && recipeLoadBrowser) {
            clampRecipeBrowserWindow();
            previewRecipeBrowserSelection();
        } else if (mode == 10 && recipeDeleteBrowser) {
            clampRecipeBrowserWindow();
        }
        markNavigating();
        refresh();
    }

    private void moveMatrixSelection(int delta) {
        int[] order = new int[] {
                SEL_BACK, SEL_MATRIX_SAVED, SEL_MATRIX_SAVE_NEW,
                0, 1, 2, 3, 4, 5, 6, 7, 8
        };
        int pos = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == selection) {
                pos = i;
                break;
            }
        }
        pos = (pos + (delta > 0 ? 1 : -1) + order.length) % order.length;
        selection = order[pos];
    }

    private int maxSelectionForMode() {
        if      (mode == 0)                         return 8;
        else if (mode == 1)                         return 5;
        else if (mode == 3)                         return 2;
        else if (mode == 10)                        return (recipeLoadBrowser || recipeDeleteBrowser) ? Math.max(0, getVaultRecipeCount() - 1) : 3;
        else if (mode == 4 || mode == 6)            return 1;
        else if (mode == 5) {
            String eff = host.getRecipeManager().getCurrentProfile().pictureEffect;
            return (eff != null && eff.equals("toy-camera")) ? 1 : 0;
        }
        return 0;
    }

    private void clampRecipeBrowserWindow() {
        int count = getVaultRecipeCount();
        if (count <= 0) {
            recipeListOffset = 0;
            vaultIndex = 0;
            return;
        }
        if (selection >= 0 && selection < count) vaultIndex = selection;
        if (vaultIndex < 0) vaultIndex = 0;
        if (vaultIndex >= count) vaultIndex = count - 1;
        int anchor = selection >= 0 && selection < count ? selection : vaultIndex;
        if (anchor < recipeListOffset) recipeListOffset = anchor;
        if (anchor >= recipeListOffset + 5) recipeListOffset = anchor - 4;
        int maxOffset = Math.max(0, count - 5);
        if (recipeListOffset > maxOffset) recipeListOffset = maxOffset;
        if (recipeListOffset < 0) recipeListOffset = 0;
    }

    private void previewRecipeBrowserSelection() {
        int idx = getRecipeBrowserSelectedVaultIndex();
        if (idx < 0 || idx >= getVaultRecipeCount()) return;
        vaultIndex = idx;
        RecipeManager.VaultItem item = vaultItems.get(idx);
        if (item == null || item.filename == null || "NONE".equals(item.filename)) return;
        host.getRecipeManager().previewVaultToSlot(item.filename);
        host.onLutPreloadNeeded();
        host.applyHardwareRecipeNow();
    }

    /** Save the current advMatrix to SD card under the given name. */
    public void saveCustomMatrix(String customName) {
        MatrixManager mm = host.getMatrixManager();
        if (mm == null) return;
        String finalName = customName;
        for (String existing : mm.getNames()) if (existing.equalsIgnoreCase(finalName)) finalName += "+";
        RTLProfile p = host.getRecipeManager().getCurrentProfile();
        mm.saveMatrix(finalName, p.advMatrix, "Saved directly from camera UI.");
        mm.scanMatrices();
        isScrollingMatrices = false;
        refresh();
    }

    // -----------------------------------------------------------------------
    // Private — data adjustment
    // -----------------------------------------------------------------------
    private void handleWbAdjustment(int dAb, int dGm) {
        RTLProfile p = host.getRecipeManager().getCurrentProfile();
        p.wbShift   = Math.max(-7, Math.min(7, p.wbShift   + dAb));
        p.wbShiftGM = Math.max(-7, Math.min(7, p.wbShiftGM + dGm));
        markAdjusting();
        refresh();
        host.scheduleHardwareApply();
    }

    private void handleAdjustment(int dir) {
        RTLProfile p = host.getRecipeManager().getCurrentProfile();
        MatrixManager mm = host.getMatrixManager();
        markAdjusting();

        if (mode == 0) {
            if (selection == SEL_MATRIX_SAVED) {
                if (mm != null && mm.getCount() > 0) {
                    isScrollingMatrices = true;
                    activeMatrixIndex = ((activeMatrixIndex + dir) % mm.getCount() + mm.getCount()) % mm.getCount();
                    int[] v = mm.getValues(activeMatrixIndex);
                    for (int i = 0; i < 9; i++) p.advMatrix[i] = v[i];
                }
            } else if (selection >= 0 && selection < p.advMatrix.length) {
                isScrollingMatrices = false;
                p.advMatrix[selection] = Math.max(-200, Math.min(200, p.advMatrix[selection] + dir * 5));
            }
        } else if (mode == 1) {
            if      (selection == 0) p.colorDepthRed     = Math.max(-7,Math.min(7,p.colorDepthRed     + dir));
            else if (selection == 1) p.colorDepthGreen   = Math.max(-7,Math.min(7,p.colorDepthGreen   + dir));
            else if (selection == 2) p.colorDepthBlue    = Math.max(-7,Math.min(7,p.colorDepthBlue    + dir));
            else if (selection == 3) p.colorDepthCyan    = Math.max(-7,Math.min(7,p.colorDepthCyan    + dir));
            else if (selection == 4) p.colorDepthMagenta = Math.max(-7,Math.min(7,p.colorDepthMagenta + dir));
            else if (selection == 5) p.colorDepthYellow  = Math.max(-7,Math.min(7,p.colorDepthYellow  + dir));
        } else if (mode == 3) {
            if      (selection == 0) p.contrast   = Math.max(-3,Math.min(3,p.contrast   + dir));
            else if (selection == 1) p.saturation = Math.max(-3,Math.min(3,p.saturation + dir));
            else if (selection == 2) p.sharpness  = Math.max(-3,Math.min(3,p.sharpness  + dir));
        } else if (mode == 4) {
            if      (selection == 0) p.shadingRed  = Math.max(-7,Math.min(7,p.shadingRed  + dir));
            else if (selection == 1) p.shadingBlue = Math.max(-7,Math.min(7,p.shadingBlue + dir));
        } else if (mode == 5) {
            String eff = p.pictureEffect != null ? p.pictureEffect : "off";
            if (selection == 0) {
                if ("soft-focus".equals(eff)||"hdr-art".equals(eff)||"illust".equals(eff)||"watercolor".equals(eff)) {
                    p.softFocusLevel = Math.max(1,Math.min(3,p.softFocusLevel+dir));
                } else {
                    String[] opts = {"normal"};
                    if ("toy-camera".equals(eff))  opts = new String[]{"normal","cool","warm","green","magenta"};
                    else if ("part-color".equals(eff)) opts = new String[]{"red","green","blue","yellow"};
                    else if ("miniature".equals(eff))  opts = new String[]{"auto","left","vcenter","right","upper","hcenter","lower"};
                    if (opts.length > 1) { int idx=0; for(int i=0;i<opts.length;i++) if(opts[i].equals(p.peToyCameraTone)) idx=i; p.peToyCameraTone=opts[(idx+dir+opts.length)%opts.length]; }
                }
            } else if (selection == 1 && "toy-camera".equals(eff)) { p.vignetteHardware = Math.max(-16,Math.min(16,p.vignetteHardware+dir)); }
        } else if (mode == 6) {
            if (selection == 0) {
                // Use the dynamic hardware list from the MenuController instead of a hardcoded array!
                String[] styles = host.getMenuController().getSupportedColorModes();
                int idx = 0;
                for (int i = 0; i < styles.length; i++) {
                    if (styles[i].equalsIgnoreCase(p.colorMode)) idx = i;
                }
                p.colorMode = styles[(idx + dir + styles.length) % styles.length].toLowerCase();
            } else if (selection == 1) {
                p.sharpnessGain = Math.max(-10, Math.min(10, p.sharpnessGain + dir));
            }
        } else if (mode == 7) { p.whiteBalance = MenuController.cycleKelvin(p.whiteBalance, dir);
        } else if (mode == 8) {
            if (selection == 0) { String[] eff={"off","toy-camera","pop-color","posterization","retro-photo","soft-high-key","part-color","rough-mono","soft-focus","hdr-art","richtone-mono","miniature","watercolor","illust"}; int idx=0; for(int i=0;i<eff.length;i++) if(eff[i].equals(p.pictureEffect)) idx=i; p.pictureEffect=eff[(idx+dir+eff.length)%eff.length]; }
        } else if (mode == 9) {
            if (selection == 0) { String[] dro={"OFF","AUTO","LVL 1","LVL 2","LVL 3","LVL 4","LVL 5"}; int idx=0; for(int i=0;i<dro.length;i++) if(dro[i].equalsIgnoreCase(p.dro)) idx=i; p.dro=dro[(idx+dir+dro.length)%dro.length]; }
        }
        refresh();
        host.scheduleHardwareApply();
    }

    // -----------------------------------------------------------------------
    // Private — rendering
    // -----------------------------------------------------------------------
    private void renderHeader(String title) {
        header.setVisibility(View.VISIBLE);
        UiTheme.pageTabPanel(headerBack, UiTheme.ACCENT, selection == -2, false);
        headerBack.setTextColor(selection == -2 ? UiTheme.TEXT : UiTheme.TEXT_MUTED);
        headerBack.setText("BACK");
        headerTitle.setText(title != null ? title : "");
        headerTitle.setTextColor(UiTheme.TEXT);
        headerTitle.setShadowLayer(2, 0, 0, UiTheme.SHADOW);
        UiTheme.titlePanel(headerTitle, UiTheme.ACCENT);
    }

    private void refresh() {
        if (!active) return; // Never render when HUD is not open
        RTLProfile p   = host.getRecipeManager().getCurrentProfile();
        MatrixManager mm = host.getMatrixManager();
        TextView tvTop = host.getTvTopStatus();
        if (tvTop != null) tvTop.setVisibility(View.GONE);
        MenuController mc = host.getMenuController();
        String tip = "";
        int activeCells = 0;
        int selectedCell = selection;
        String[] labels = new String[9]; String[] values = new String[9];

        // --- MODE 2: WB GRID ---
        if (mode == 2) {
            matrixActions.setVisibility(View.GONE);
            renderHeader("WHITE BALANCE SHIFT");
            if (tvTop != null) {
                tvTop.setText("< BACK    WHITE BALANCE SHIFT");
                tvTop.setTextColor(selection == -2 ? selectedNavigationColor() : UiTheme.TEXT);
                tvTop.setVisibility(View.VISIBLE);
            }
            int ab = p.wbShift; int gm = p.wbShiftGM;
            FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) wbCursor.getLayoutParams();
            cp.setMargins(153 + ab * 20, 153 - gm * 20, 0, 0);
            wbCursor.setLayoutParams(cp);
            String abStr = ab==0?"0":(ab<0?"B"+Math.abs(ab):"A"+ab);
            String gmStr = gm==0?"0":(gm<0?"M"+Math.abs(gm):"G"+gm);
            wbValueText.setText(abStr + ", " + gmStr);
            wbValueText.setTextColor(valueEditing ? UiTheme.WARN : UiTheme.ACCENT);
            wbCursor.setBackgroundColor(valueEditing ? UiTheme.WARN : UiTheme.ACCENT);
            if (selection == 0 && valueEditing) {
                UiTheme.activeOutlinePanel(wbGrid, UiTheme.ACCENT);
            } else if (selection == 0) {
                UiTheme.pageTabPanel(wbGrid, UiTheme.ACCENT, true, true);
            } else {
                UiTheme.panel(wbGrid);
            }
            if (tvTop != null) tvTop.setVisibility(View.GONE);
            return;
        }

        matrixActions.setVisibility(mode == 0 ? View.VISIBLE : View.GONE);

        if (mode != 0 && mode != 10) renderHeader(hudTitle());
        if (tvTop != null && mode != 0 && mode != 10) {
            tvTop.setText("< BACK    " + hudTitle());
            tvTop.setTextColor(selection == -2 ? selectedNavigationColor() : UiTheme.TEXT);
            tvTop.setVisibility(View.VISIBLE);
        }

        if (mode == 0) {
            matrixActions.setVisibility(View.VISIBLE);
            activeCells = 9;
            labels = new String[]{"R-R","G-R","B-R","R-G","G-G","B-G","R-B","G-B","B-B"};
            int rBal=p.advMatrix[0]+p.advMatrix[1]+p.advMatrix[2];
            int gBal=p.advMatrix[3]+p.advMatrix[4]+p.advMatrix[5];
            int bBal=p.advMatrix[6]+p.advMatrix[7]+p.advMatrix[8];
            String balText = String.format(" [ R:%d%% | G:%d%% | B:%d%% ]",rBal,gBal,bBal);
            String currentName="CUSTOM (UNSAVED)"; String matrixNote="Use D-Pad to cycle SD Card matrices.";
            if (mm != null && mm.getCount() > 0) {
                if (isScrollingMatrices) { currentName=mm.getNames().get(activeMatrixIndex); matrixNote=mm.getNote(activeMatrixIndex);
                } else { for(int f=0;f<mm.getCount();f++){ int[] ld=mm.getValues(f); boolean m=true; for(int i=0;i<9;i++) if(p.advMatrix[i]!=ld[i]){m=false;break;} if(m){activeMatrixIndex=f;currentName=mm.getNames().get(f);matrixNote=mm.getNote(f);break;} } }
            }
            if (mc.isNamingMode()) {
                StringBuilder sb=new StringBuilder("NAME: "); char[] buf=mc.getNameBuffer(); int pos=mc.getNameCursorPos();
                for(int i=0;i<buf.length;i++) { if(i==pos) sb.append("[").append(buf[i]).append("]"); else sb.append(buf[i]); }
                renderHeader(sb.toString());
                headerTitle.setTextColor(UiTheme.WARN);
            } else {
                renderHeader("MATRIX: " + currentName);
            }
            if (tvTop != null) {
                if (mc.isNamingMode()) {
                    StringBuilder sb=new StringBuilder("NAME: "); char[] buf=mc.getNameBuffer(); int pos=mc.getNameCursorPos();
                    for(int i=0;i<buf.length;i++) { if(i==pos) sb.append("[").append(buf[i]).append("]"); else sb.append(buf[i]); }
                    tvTop.setText(sb.toString()); tvTop.setTextColor(UiTheme.WARN);
                } else {
                    tvTop.setText("< BACK    MATRIX: " + currentName);
                    tvTop.setTextColor(selection == -2 || selection == SEL_MATRIX_SAVED || selection == SEL_MATRIX_SAVE_NEW ? selectedNavigationColor() : UiTheme.TEXT);
                }
                tvTop.setVisibility(View.VISIBLE);
            }
            matrixSavedAction.setText("SELECT SAVED\n" + ((mm != null && mm.getCount() > 0) ? currentName : "NONE"));
            matrixSaveAction.setText("SAVE + NAME\nCUSTOM");
            styleMatrixAction(matrixSavedAction, selection == SEL_MATRIX_SAVED, valueEditing && selection == SEL_MATRIX_SAVED);
            styleMatrixAction(matrixSaveAction, selection == SEL_MATRIX_SAVE_NEW, false);
            if (selection==SEL_MATRIX_SAVED) tip=(valueEditing ? "Cycle saved matrices with the D-Pad. Press ENTER to confirm.\n" : "Press ENTER to choose from saved matrices.\n") + "FILE: "+matrixNote+"\n"+balText;
            else if (selection==SEL_MATRIX_SAVE_NEW) tip="Name and save the current RGB matrix to the SD card.\n"+balText;
            else { String[] t={"Red sensitivity to real-world Red light (Primary - baseline is 100)","Pushes Green light into Red channel (baseline is 0)","Pushes Blue light into Red channel (baseline is 0)","Pushes Red light into Green channel (baseline is 0)","Green sensitivity to real-world Green light (Primary - baseline is 100)","Pushes Blue light into Green channel (baseline is 0)","Pushes Red light into Blue channel (baseline is 0)","Pushes Green light into Blue channel (baseline is 0)","Blue sensitivity to real-world Blue light (Primary - baseline is 100)."}; if(selection>=0&&selection<t.length) tip=t[selection]+"\n"+balText; }
            for (int i=0;i<9;i++) values[i]=p.advMatrix[i]+"%";

        } else if (mode == 1) {
            activeCells=6; labels=new String[]{"RED","GRN","BLU","CYN","MAG","YEL"};
            int[] d={p.colorDepthRed,p.colorDepthGreen,p.colorDepthBlue,p.colorDepthCyan,p.colorDepthMagenta,p.colorDepthYellow};
            for(int i=0;i<6;i++) values[i]=d[i]==0?"0":String.format("%+d",d[i]);
            tip="Alters the luminance and depth of the target color phase";
        } else if (mode == 3) {
            activeCells=3; labels=new String[]{"CONTRAST","SATURATION","SHARPNESS"};
            int[] v={p.contrast,p.saturation,p.sharpness}; for(int i=0;i<3;i++) values[i]=v[i]==0?"0":String.format("%+d",v[i]);
            if(selection==2) tip="Standard hardware sharpness (Micro-Contrast is stronger)";
        } else if (mode == 4) {
            activeCells=2; labels=new String[]{"SHADE RED","SHADE BLUE"};
            values[0]=p.shadingRed==0?"0":String.format("%+d",p.shadingRed); values[1]=p.shadingBlue==0?"0":String.format("%+d",p.shadingBlue);
            tip="Injects color shifts into the corners to simulate vintage lens tinting";
        } else if (mode == 5) {
            String eff=p.pictureEffect!=null?p.pictureEffect:"off"; String g=p.peToyCameraTone!=null?p.peToyCameraTone.toUpperCase():"NORM";
            if("toy-camera".equals(eff)){activeCells=2;labels=new String[]{"TOY-TONE","HW-VIGNETTE"};values[0]=g.equals("NORMAL")?"NORM":(g.equals("MAGENTA")?"MAG":g);values[1]=p.vignetteHardware==0?"0":String.format("%+d",p.vignetteHardware);}
            else if("soft-focus".equals(eff)||"hdr-art".equals(eff)||"illust".equals(eff)||"watercolor".equals(eff)){activeCells=1;labels=new String[]{"LEVEL"};values[0]=String.valueOf(p.softFocusLevel);}
            else if("part-color".equals(eff)){activeCells=1;labels=new String[]{"COLOR"};values[0]=g.equals("NORMAL")?"RED":g;}
            else if("miniature".equals(eff)){activeCells=1;labels=new String[]{"AREA"};values[0]=g.equals("NORMAL")?"AUTO":g;}
            else{activeCells=1;labels=new String[]{"EFFECT"};values[0]="NO PARAMS";}
        } else if (mode == 6) {
            activeCells=2; labels=new String[]{"STYLE","MICRO-CONTRAST"};
            values[0]=p.colorMode!=null?p.colorMode.toUpperCase():"STD"; values[1]=p.sharpnessGain==0?"0":String.format("%+d",p.sharpnessGain);
            if(selection==1) tip="Aggressive frequency enhancement (Affects film grain texture)";
        } else if (mode == 7) {
            activeCells=1;labels=new String[]{"WHITE BALANCE"};values[0]=p.whiteBalance!=null?p.whiteBalance.toUpperCase():"AUTO";
            tip="Adjust Kelvin Temperature (2500K - 9900K)";
        } else if (mode == 8) {
            activeCells=1;labels=new String[]{"EFFECT"};values[0]=p.pictureEffect!=null?p.pictureEffect.toUpperCase():"OFF";
        } else if (mode == 9) {
            activeCells=1;labels=new String[]{"DYNAMIC RANGE"};values[0]=p.dro!=null?p.dro.toUpperCase():"OFF";
            tip="Dynamic Range Optimizer: Recovers shadow detail in high-contrast scenes";
        } else if (mode == 10) {
            if(mc.isConfirmingDelete()){activeCells=2;labels=new String[]{"DELETE RECIPE?","CANCEL"};values[0]="[ CONFIRM DELETE ]";values[1]="[ GO BACK ]";if(selection==0) tip="WARNING: This will permanently delete the recipe from the SD card."; else tip="Cancel and return to the recipe list.";}
            else if(recipeLoadBrowser || recipeDeleteBrowser){
                refreshVaultItems();
                int count = getVaultRecipeCount();
                if(count<=0){
                    activeCells=1;labels[0]="NO RECIPES";values[0]="EMPTY";selectedCell=selection == -2 ? -2 : 0;tip="No saved recipes were found on the SD card.";
                } else {
                    clampRecipeBrowserWindow();
                    int visible = Math.min(5, count - recipeListOffset);
                    for(int i=0;i<visible;i++){
                        int idx = recipeListOffset + i;
                        String name = vaultItems.get(idx).profileName != null ? vaultItems.get(idx).profileName : "RECIPE " + (idx + 1);
                        if(name.length()>14) name=name.substring(0,12)+"..";
                        labels[i]="RECIPE "+(idx+1);
                        values[i]=name;
                    }
                    activeCells = visible;
                    selectedCell = selection - recipeListOffset;
                    if(selection == -2) selectedCell = -2;
                    else if(selectedCell < 0 || selectedCell >= activeCells) selectedCell = 0;
                    tip = recipeDeleteBrowser ? "Press ENTER to confirm deleting the highlighted recipe." : "Move to preview recipes. Press ENTER to load the highlighted recipe.";
                }
            } else{
                activeCells=4;labels=new String[]{"SAVE + NAME","LOAD RECIPE","RESET SLOT","DELETE RECIPE"};
                refreshVaultItems();
                int count = getVaultRecipeCount();
                values[0]="CURRENT SLOT"; values[1]=count+" SAVED"; values[2]="DEFAULT"; values[3]=count+" SAVED";
                if(selection==0) tip="Name and save the current recipe settings.";
                else if(selection==1) tip="Open saved recipes, preview them, then load.";
                else if(selection==2) tip="Clear the active slot and return it to default settings.";
                else if(selection==3) tip="Open saved recipes and delete the ones you no longer want.";
            }
            if(mc.isNamingMode()){
                StringBuilder sb=new StringBuilder("NAME: ");char[] buf=mc.getNameBuffer();int pos=mc.getNameCursorPos();for(int i=0;i<buf.length;i++){if(i==pos)sb.append("[").append(buf[i]).append("]");else sb.append(buf[i]);}
                renderHeader(sb.toString());
                headerTitle.setTextColor(UiTheme.WARN);
            } else {
                renderHeader(recipeDeleteBrowser ? "DELETE RECIPE" : (recipeLoadBrowser ? "LOAD RECIPE" : "RECIPE MANAGER    SLOT "+(host.getRecipeManager().getCurrentSlot()+1)));
            }
            if(tvTop!=null){
                if(mc.isNamingMode()){StringBuilder sb=new StringBuilder("NAME: ");char[] buf=mc.getNameBuffer();int pos=mc.getNameCursorPos();for(int i=0;i<buf.length;i++){if(i==pos)sb.append("[").append(buf[i]).append("]");else sb.append(buf[i]);}tvTop.setText(sb.toString());tvTop.setTextColor(UiTheme.WARN);}
                else{
                    tvTop.setText(recipeDeleteBrowser ? "< BACK    DELETE RECIPE" : (recipeLoadBrowser ? "< BACK    LOAD RECIPE" : "< BACK    RECIPE MANAGER    SLOT "+(host.getRecipeManager().getCurrentSlot()+1)));
                    tvTop.setTextColor(selection == -2 ? selectedNavigationColor() : UiTheme.TEXT);
                }
                tvTop.setVisibility(View.VISIBLE);
            }
        }

        // Render cells
        for(int i=0;i<9;i++){
            if(i<activeCells){cells[i].setVisibility(View.VISIBLE);cellLabels[i].setText(labels[i]);cellValues[i].setText(values[i]);
                UiTheme.actionPanel(cells[i], UiTheme.ACCENT, i==selectedCell, true);
                if(i==selectedCell){
                    cellLabels[i].setTextColor(UiTheme.TEXT);
                    cellValues[i].setTextColor(selectedValueColor());
                }
                else{cellLabels[i].setTextColor(UiTheme.TEXT_MUTED);cellValues[i].setTextColor(UiTheme.TEXT);}
            } else {
                cells[i].setVisibility(View.GONE);
                UiTheme.clear(cells[i]);
            }
        }
        if(tooltip!=null){
            tooltip.setText(tip);
            tooltip.setVisibility(tip.isEmpty()?View.GONE:View.VISIBLE);
            if(!tip.isEmpty()){
                if(selection == SEL_MATRIX_SAVED || selection == SEL_MATRIX_SAVE_NEW){
                    UiTheme.actionPanel(tooltip, UiTheme.ACCENT, true, true);
                    tooltip.setTextColor(UiTheme.TEXT);
                } else {
                    UiTheme.softPanel(tooltip);
                    tooltip.setTextColor(UiTheme.TEXT_MUTED);
                }
            }
        }
        if (tvTop != null) tvTop.setVisibility(View.GONE);
    }

    private void styleMatrixAction(TextView view, boolean selected, boolean editing) {
        UiTheme.actionPanel(view, UiTheme.ACCENT, selected, true);
        if (editing) view.setTextColor(UiTheme.WARN);
        else view.setTextColor(selected ? UiTheme.TEXT : UiTheme.TEXT_MUTED);
        view.setShadowLayer(selected ? 2 : 0, 0, 0, UiTheme.SHADOW);
    }

    private boolean isAdjustingNow() {
        return System.currentTimeMillis() < adjustingUntilMs;
    }

    private int selectedNavigationColor() {
        return UiTheme.TEXT;
    }

    private int selectedValueColor() {
        if (valueEditing || isAdjustingNow()) return UiTheme.WARN;
        return UiTheme.TEXT;
    }

    private void markNavigating() {
        valueEditing = false;
        adjustingUntilMs = 0L;
        flashVisible = true;
        if (active) startFlash();
    }

    private void markEditing() {
        adjustingUntilMs = 0L;
        flashVisible = true;
        host.getUiHandler().removeCallbacks(flashRunnable);
    }

    private void markAdjusting() {
        adjustingUntilMs = valueEditing ? 0L : System.currentTimeMillis() + ADJUST_CONFIRM_MS;
        flashVisible = true;
        if (active && !valueEditing) startFlash();
    }

    private void startFlash() {
        host.getUiHandler().removeCallbacks(flashRunnable);
        if (active) host.getUiHandler().postDelayed(flashRunnable, FLASH_INTERVAL_MS);
    }

    private void stopFlash() {
        host.getUiHandler().removeCallbacks(flashRunnable);
        flashVisible = true;
        adjustingUntilMs = 0L;
        valueEditing = false;
    }

    private String hudTitle() {
        if      (mode == 1)  return "COLOR DEPTH";
        else if (mode == 3)  return "TONE & STYLE";
        else if (mode == 4)  return "EDGE SHADING";
        else if (mode == 5)  return "EFFECT TWEAKER";
        else if (mode == 6)  return "FOUNDATION BASE";
        else if (mode == 7)  return "WHITE BALANCE";
        else if (mode == 8)  return "PICTURE EFFECT";
        else if (mode == 9)  return "DYNAMIC RANGE";
        return "SETTINGS";
    }
}
