package com.github.ma1co.pmcademo.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;

public class LutCooker {
    private int lutSize = 0;
    private int[] lutPixels;

    // 1. READ THE .CUBE FILE (Bulletproof Version)
    public boolean loadLut(File cubeFile) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(cubeFile));
            String line;
            ArrayList<Integer> colors = new ArrayList<Integer>();

            while ((line = br.readLine()) != null) {
                line = line.trim();
                
                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#")) continue;

                // Find the size of the 3D grid
                if (line.startsWith("LUT_3D_SIZE")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length > 1) {
                        lutSize = Integer.parseInt(parts[1]);
                    }
                    continue;
                }
                
                // CRITICAL FIX: Skip any other text headers (TITLE, DOMAIN_MIN, etc.)
                if (line.matches("^[a-zA-Z_]+.*")) {
                    continue;
                }

                // If it survived the checks, it should be an R G B line
                String[] rgb = line.split("\\s+");
                if (rgb.length >= 3) {
                    try {
                        int r = (int) (Float.parseFloat(rgb[0]) * 255);
                        int g = (int) (Float.parseFloat(rgb[1]) * 255);
                        int b = (int) (Float.parseFloat(rgb[2]) * 255);
                        
                        // Clamp values to prevent weird color wrapping
                        r = Math.max(0, Math.min(255, r));
                        g = Math.max(0, Math.min(255, g));
                        b = Math.max(0, Math.min(255, b));
                        
                        colors.add(Color.rgb(r, g, b));
                    } catch (Exception e) {
                        // If parsing the numbers fails, safely ignore the line
                    }
                }
            }
            br.close();

            // Validate that we got all the colors we need for the grid
            int expectedColors = lutSize * lutSize * lutSize;
            if (lutSize > 0 && colors.size() >= expectedColors) {
                lutPixels = new int[expectedColors];
                for (int i = 0; i < expectedColors; i++) {
                    lutPixels[i] = colors.get(i);
                }
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    // 2. APPLY THE RECIPE TO THE PHOTO
    public Bitmap applyLut(Bitmap src) {
        if (lutPixels == null || lutSize == 0) return src;

        int width = src.getWidth();
        int height = src.getHeight();
        int[] pixels = new int[width * height];
        
        // Grab all pixels at once for speed
        src.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int r = Color.red(pixel);
            int g = Color.green(pixel);
            int b = Color.blue(pixel);

            // Find nearest color in the LUT grid
            int lutX = (r * (lutSize - 1)) / 255;
            int lutY = (g * (lutSize - 1)) / 255;
            int lutZ = (b * (lutSize - 1)) / 255;

            // 3D array flattened into 1D array indexing
            int lutIndex = lutX + (lutY * lutSize) + (lutZ * lutSize * lutSize);

            // Safety check
            if (lutIndex >= 0 && lutIndex < lutPixels.length) {
                pixels[i] = lutPixels[lutIndex];
            }
        }

        // Create a fresh bitmap with the cooked pixels
        Bitmap cooked = Bitmap.createBitmap(width, height, src.getConfig());
        cooked.setPixels(pixels, 0, width, 0, 0, width, height);
        return cooked;
    }
}