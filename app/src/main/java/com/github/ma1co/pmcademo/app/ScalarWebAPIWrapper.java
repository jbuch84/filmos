package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.hardware.Camera;
import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class ScalarWebAPIWrapper {
    private Object scalarInstance;
    private Method getIntMethod;
    private Method getFocusAreasMethod;

    public ScalarWebAPIWrapper(Context context) {
        try {
            // Aggressively hunt for the Sony IPC Daemon
            Class<?> scalarClass = null;
            try {
                scalarClass = context.getClassLoader().loadClass("com.sony.scalar.sysutil.ScalarWebAPI");
            } catch (Exception e1) {
                scalarClass = Class.forName("com.sony.scalar.sysutil.ScalarWebAPI");
            }

            if (scalarClass != null) {
                Constructor<?> ctor = scalarClass.getDeclaredConstructor(Context.class);
                ctor.setAccessible(true);
                scalarInstance = ctor.newInstance(context.getApplicationContext());

                getIntMethod = scalarClass.getMethod("getInt", String.class);
                getFocusAreasMethod = scalarClass.getMethod("getFocusAreas");
                Log.i("COOKBOOK_AF", "SUCCESS: ScalarWebAPI manually hooked via Wrapper!");
            }
        } catch (Exception e) {
            Log.e("COOKBOOK_AF", "Manual Wrapper failed: " + e.getMessage());
        }
    }

    public boolean isAvailable() {
        return scalarInstance != null;
    }

    public int getInt(String key) {
        if (scalarInstance == null || getIntMethod == null) return 0;
        try {
            return (Integer) getIntMethod.invoke(scalarInstance, key);
        } catch (Exception e) {
            return 0;
        }
    }

    public Camera.Area[] getFocusAreas() {
        if (scalarInstance == null || getFocusAreasMethod == null) return null;
        try {
            // Get the proprietary Sony array
            Object[] objArray = (Object[]) getFocusAreasMethod.invoke(scalarInstance);
            if (objArray == null || objArray.length == 0) return null;

            // Map Sony's proprietary rects to standard Android rects so the compiler doesn't crash
            Camera.Area[] areas = new Camera.Area[objArray.length];
            for (int i = 0; i < objArray.length; i++) {
                Object areaObj = objArray[i];
                Object rectObj = areaObj.getClass().getField("rect").get(areaObj);
                
                int left = rectObj.getClass().getField("left").getInt(rectObj);
                int top = rectObj.getClass().getField("top").getInt(rectObj);
                int right = rectObj.getClass().getField("right").getInt(rectObj);
                int bottom = rectObj.getClass().getField("bottom").getInt(rectObj);
                
                areas[i] = new Camera.Area(new android.graphics.Rect(left, top, right, bottom), 1000);
            }
            return areas;
        } catch (Exception e) {
            return null;
        }
    }
}