package com.github.ma1co.pmcademo.app;

import android.hardware.Camera;
import android.util.Log;
import android.util.Pair;
import android.view.SurfaceHolder;

import com.sony.scalar.hardware.CameraEx;

import java.util.List;

/**
 * filmOS Manager: Sony Camera Hardware
 * Manages the CameraEx lifecycle, listeners, and system state protection.
 */
public class SonyCameraManager {
    private CameraEx cameraEx;
    private Camera camera;
    
    // selective Snapshot (The Walled Garden)
    private String origSceneMode, origFocusMode, origWhiteBalance, origDroMode, origDroLevel, origSonyDro;
    private String origContrast, origSaturation, origSharpness, origWbShiftMode, origWbShiftLb, origWbShiftCc;

    public interface CameraEventListener {
        void onCameraReady();
        void onShutterSpeedChanged();
        void onApertureChanged();
        void onIsoChanged();
        void onFocusPositionChanged(float ratio);
    }

    private CameraEventListener listener;

    public SonyCameraManager(CameraEventListener listener) {
        this.listener = listener;
    }

    public Camera getCamera() { return camera; }
    public CameraEx getCameraEx() { return cameraEx; }

    /**
     * Opens the Sony Camera extension and captures the original system state.
     */
    public void open(SurfaceHolder holder) {
        if (cameraEx == null) {
            try {
                cameraEx = CameraEx.open(0, null);
                camera = cameraEx.getNormalCamera();
                cameraEx.startDirectShutter();

                // WALLED GARDEN: Capture system state once
                if (origSceneMode == null && camera != null) {
                    Camera.Parameters p = camera.getParameters();
                    origSceneMode = p.getSceneMode();
                    origFocusMode = p.getFocusMode();
                    origWhiteBalance = p.getWhiteBalance();
                    origDroMode = p.get("dro-mode");
                    origDroLevel = p.get("dro-level");
                    origSonyDro = p.get("sony-dro");
                    origContrast = p.get("contrast");
                    origSaturation = p.get("saturation");
                    origSharpness = p.get("sharpness");
                    origWbShiftMode = p.get("white-balance-shift-mode");
                    origWbShiftLb = p.get("white-balance-shift-lb");
                    origWbShiftCc = p.get("white-balance-shift-cc");
                }

                setupNativeListeners();
                
                camera.setPreviewDisplay(holder);
                camera.startPreview();
                
                if (listener != null) listener.onCameraReady();
            } catch (Exception e) {
                Log.e("filmOS", "Failed to open camera: " + e.getMessage());
            }
        }
    }

    /**
     * Restores only the parameters we touched, then releases the hardware.
     */
    public void close() {
        if (camera != null && origSceneMode != null) {
            try {
                Camera.Parameters p = camera.getParameters();
                p.setSceneMode(origSceneMode);
                p.setFocusMode(origFocusMode);
                p.setWhiteBalance(origWhiteBalance);
                if (origDroMode != null) p.set("dro-mode", origDroMode);
                if (origDroLevel != null) p.set("dro-level", origDroLevel);
                if (origSonyDro != null) p.set("sony-dro", origSonyDro);
                if (origContrast != null) p.set("contrast", origContrast);
                if (origSaturation != null) p.set("saturation", origSaturation);
                if (origSharpness != null) p.set("sharpness", origSharpness);
                if (origWbShiftMode != null) p.set("white-balance-shift-mode", origWbShiftMode);
                if (origWbShiftLb != null) p.set("white-balance-shift-lb", origWbShiftLb);
                if (origWbShiftCc != null) p.set("white-balance-shift-cc", origWbShiftCc);
                camera.setParameters(p);
            } catch (Exception e) {}
        }
        if (cameraEx != null) {
            cameraEx.release();
            cameraEx = null;
            camera = null;
        }
    }

    /**
     * Uses Java reflection to bind to Sony firmware listeners for real-time HUD updates.
     */
    private void setupNativeListeners() {
        // Shutter Listener
        cameraEx.setShutterSpeedChangeListener(new CameraEx.ShutterSpeedChangeListener() {
            @Override public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo i, CameraEx c) {
                if (listener != null) listener.onShutterSpeedChanged();
            }
        });

        // Aperture Proxy
        try {
            Class<?> lClass = Class.forName("com.sony.scalar.hardware.CameraEx$ApertureChangeListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{lClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) {
                        if (m.getName().equals("onApertureChange") && listener != null) listener.onApertureChanged();
                        return null;
                    }
                });
            cameraEx.getClass().getMethod("setApertureChangeListener", lClass).invoke(cameraEx, proxy);
        } catch (Exception e) {}

        // ISO Proxy
        try {
            Class<?> lClass = Class.forName("com.sony.scalar.hardware.CameraEx$AutoISOSensitivityListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{lClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) {
                        if (m.getName().equals("onChanged") && listener != null) listener.onIsoChanged();
                        return null;
                    }
                });
            cameraEx.getClass().getMethod("setAutoISOSensitivityListener", lClass).invoke(cameraEx, proxy);
        } catch (Exception e) {}

        // Focus Drive Proxy
        try {
            Class<?> lClass = Class.forName("com.sony.scalar.hardware.CameraEx$FocusDriveListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{lClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) throws Throwable {
                        if (m.getName().equals("onChanged") && a != null && a.length == 2) {
                            Object pos = a[0];
                            int cur = pos.getClass().getField("currentPosition").getInt(pos);
                            int max = pos.getClass().getField("maxPosition").getInt(pos);
                            if (max > 0 && listener != null) listener.onFocusPositionChanged((float) cur / max);
                        }
                        return null;
                    }
                });
            cameraEx.getClass().getMethod("setFocusDriveListener", lClass).invoke(cameraEx, proxy);
        } catch (Exception e) {}
    }
}