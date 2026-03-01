package com.github.ma1co.pmcademo.app;

import android.content.Intent;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.sony.scalar.hardware.CameraEx;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BaseActivity implements SurfaceHolder.Callback {

    private SurfaceHolder mSurfaceHolder;
    private CameraEx mCameraEx;
    private Camera mNormalCamera;
    
    private TextView mRecipeText;
    private TextView mStatusText;
    
    private boolean mIsTakingPicture = false;

    // LUT Variables
    private List<String> availableLuts = new ArrayList<>();
    private String selectedLutPath = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Build a clean, full-screen UI programmatically
        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.BLACK);

        SurfaceView surfaceView = new SurfaceView(this);
        mSurfaceHolder = surfaceView.getHolder();
        mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        mSurfaceHolder.addCallback(this);
        layout.addView(surfaceView);

        // Status Text (e.g., "Cooking...")
        mStatusText = new TextView(this);
        mStatusText.setTextColor(Color.WHITE);
        mStatusText.setTextSize(24);
        mStatusText.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        mStatusText.setVisibility(View.GONE);
        
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        statusParams.gravity = android.view.Gravity.CENTER;
        mStatusText.setLayoutParams(statusParams);
        layout.addView(mStatusText);

        // Recipe Overlay Text
        mRecipeText = new TextView(this);
        mRecipeText.setTextColor(Color.parseColor("#ff5000")); // Sony Alpha Orange
        mRecipeText.setTextSize(18);
        mRecipeText.setPadding(20, 20, 20, 20);
        
        FrameLayout.LayoutParams recipeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        recipeParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
        mRecipeText.setLayoutParams(recipeParams);
        layout.addView(mRecipeText);

        setContentView(layout);

        loadAvailableLUTs();
    }

    private void loadAvailableLUTs() {
        File lutDirectory = new File(Environment.getExternalStorageDirectory(), "LUTs");
        if (!lutDirectory.exists()) {
            lutDirectory.mkdirs(); 
        }

        File[] files = lutDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().toLowerCase().endsWith(".cube")) {
                    availableLuts.add(file.getAbsolutePath());
                }
            }
        }
        
        updateRecipeUI();
    }

    private void updateRecipeUI() {
        if (availableLuts.isEmpty()) {
            mRecipeText.setText("Recipe: Standard (No LUTs found)");
        } else {
            selectedLutPath = availableLuts.get(0);
            File lutFile = new File(selectedLutPath);
            mRecipeText.setText("Recipe: " + lutFile.getName().replace(".cube", ""));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            mCameraEx = CameraEx.open(0, null);
            mNormalCamera = mCameraEx.getNormalCamera();

            Camera.Parameters params = mNormalCamera.getParameters();
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            params.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO);
            mNormalCamera.setParameters(params);

        } catch (Exception e) {
            Toast.makeText(this, "Camera Error", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mNormalCamera != null) {
            mNormalCamera.stopPreview();
            mCameraEx.release();
            mCameraEx = null;
            mNormalCamera = null;
        }
    }

    // --- HARDWARE BUTTON INTERCEPTS ---

    @Override
    protected boolean onShutterKeyDown() {
        if (mNormalCamera != null && !mIsTakingPicture) {
            mIsTakingPicture = true;
            
            mStatusText.setText("Cooking Image...");
            mStatusText.setVisibility(View.VISIBLE);
            mRecipeText.setVisibility(View.GONE);
            
            mNormalCamera.takePicture(null, null, mPictureCallback);
        }
        return true;
    }

    @Override
    protected boolean onShutterKeyUp() {
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        
        // Half-Press: Trigger Autofocus
        if (keyCode == KeyEvent.KEYCODE_FOCUS || keyCode == KeyEvent.KEYCODE_CAMERA_HALF) {
            if (mNormalCamera != null && !mIsTakingPicture) {
                mNormalCamera.autoFocus(null); 
            }
            return true;
        }

        // D-Pad Left/Right: Cycle Recipes
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (!availableLuts.isEmpty()) {
                int currentIndex = availableLuts.indexOf(selectedLutPath);
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    currentIndex = (currentIndex + 1) % availableLuts.size();
                } else {
                    currentIndex = (currentIndex - 1 + availableLuts.size()) % availableLuts.size();
                }
                selectedLutPath = availableLuts.get(currentIndex);
                File lutFile = new File(selectedLutPath);
                mRecipeText.setText("Recipe: " + lutFile.getName().replace(".cube", ""));
            }
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    // --- THE BAKER CALLBACK ---
    private Camera.PictureCallback mPictureCallback = new Camera.PictureCallback() {
        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            
            // TODO: Phase 2 NDK Hook
            
            File dir = new File(Environment.getExternalStorageDirectory(), "DCIM/COOKBOOK");
            if (!dir.exists()) dir.mkdirs();
            
            File file = new File(dir, "DSC_" + System.currentTimeMillis() + ".JPG");
            try {
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(data);
                fos.close();
            } catch (Exception e) {
                e.printStackTrace();
            }

            mStatusText.setVisibility(View.GONE);
            mRecipeText.setVisibility(View.VISIBLE);
            camera.startPreview();
            mIsTakingPicture = false;
        }
    };

    // --- SURFACE CALLBACKS ---
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            if (mNormalCamera != null) {
                mNormalCamera.setPreviewDisplay(holder);
                mNormalCamera.startPreview();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) {}
}