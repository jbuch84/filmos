package com.github.ma1co.pmcademo.app;

import android.view.KeyEvent;
import com.sony.scalar.sysutil.ScalarInput;

/**
 * filmOS Manager: Input Mapping
 * Translates raw Sony hardware scan codes into application commands.
 */
public class InputManager {

    public interface InputListener {
        void onShutterHalfPressed();
        void onShutterHalfReleased();
        void onDeletePressed();
        void onMenuPressed();
        void onEnterPressed();
        void onUpPressed();
        void onDownPressed();
        void onLeftPressed();
        void onRightPressed();
        void onDialRotated(int direction);
    }

    private InputListener listener;

    public InputManager(InputListener listener) {
        this.listener = listener;
    }

    /**
     * Processes key down events from the Sony hardware.
     * Maps scan codes to listener methods.
     */
    public boolean handleKeyDown(int keyCode, KeyEvent event) {
        int sc = event.getScanCode();
        
        // --- S1 SHUTTER (HALF-PRESS) ---
        if (sc == ScalarInput.ISV_KEY_S1_1 && event.getRepeatCount() == 0) {
            listener.onShutterHalfPressed();
            return true;
        }

        // --- CORE NAVIGATION & DIALS ---
        if (sc == ScalarInput.ISV_KEY_DELETE) {
            listener.onDeletePressed();
            return true;
        }
        if (sc == ScalarInput.ISV_KEY_MENU) {
            listener.onMenuPressed();
            return true;
        }
        if (sc == ScalarInput.ISV_KEY_ENTER) {
            listener.onEnterPressed();
            return true;
        }
        if (sc == ScalarInput.ISV_KEY_UP) {
            listener.onUpPressed();
            return true;
        }
        if (sc == ScalarInput.ISV_KEY_DOWN) {
            listener.onDownPressed();
            return true;
        }
        if (sc == ScalarInput.ISV_KEY_LEFT) {
            listener.onLeftPressed();
            return true;
        }
        if (sc == ScalarInput.ISV_KEY_RIGHT) {
            listener.onRightPressed();
            return true;
        }

        // --- DIAL ROTATION ---
        if (sc == ScalarInput.ISV_DIAL_1_CLOCKWISE) {
            listener.onDialRotated(1);
            return true;
        }
        if (sc == ScalarInput.ISV_DIAL_1_COUNTERCW) {
            listener.onDialRotated(-1);
            return true;
        }

        return false;
    }

    /**
     * Processes key up events.
     */
    public boolean handleKeyUp(int keyCode, KeyEvent event) {
        int sc = event.getScanCode();
        if (sc == ScalarInput.ISV_KEY_S1_1) {
            listener.onShutterHalfReleased();
            return true;
        }
        return false;
    }
}