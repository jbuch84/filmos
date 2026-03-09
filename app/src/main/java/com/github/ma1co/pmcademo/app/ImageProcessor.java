package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;

public class ImageProcessor {
    public interface ProcessorCallback {
        void onPreloadStarted(); void onPreloadFinished(boolean success);
        void onProcessStarted(); void onProcessFinished(String resultPath);
    }

    private Context mContext;
    private ProcessorCallback mCallback;

    public ImageProcessor(Context context, ProcessorCallback callback) {
        this.mContext = context;
        this.mCallback = callback;
    }

    public void triggerLutPreload(String lutPath, String name) {
        new PreloadLutTask().execute(lutPath);
    }

    public void processJpeg(String inPath, String outDir, int qualityIndex, RTLProfile profile) {
        new ProcessTask(inPath, outDir, qualityIndex, profile).execute();
    }

    private class PreloadLutTask extends AsyncTask<String, Void, Boolean> {
        @Override protected Boolean doInBackground(String... params) { return LutEngine.loadLut(params[0]); }
    }

    private class ProcessTask extends AsyncTask<Void, Void, String> {
        private String inPath, outDir;
        private int qualityIndex;
        private RTLProfile profile;

        public ProcessTask(String in, String out, int q, RTLProfile p) {
            this.inPath = in; this.outDir = out; this.qualityIndex = q; this.profile = p;
        }

        @Override protected void onPreExecute() { if (mCallback != null) mCallback.onProcessStarted(); }

        @Override protected String doInBackground(Void... voids) {
            try {
                File dir = new File(outDir);
                if (!dir.exists()) dir.mkdirs();

                // 8.3 naming
                String timeTag = Long.toHexString(System.currentTimeMillis() / 1000).toUpperCase();
                String finalOutPath = new File(dir, "FL" + timeTag.substring(timeTag.length()-6) + ".JPG").getAbsolutePath();

                // Java creates the "Shell" file so Sony's OS doesn't block C++
                FileOutputStream touch = new FileOutputStream(finalOutPath);
                touch.write(1);
                touch.close();

                Log.d("filmOS", "Passing to Native. QualityIdx: " + qualityIndex);
                
                // We pass qualityIndex (0 for Proxy, 1 for High, 2 for Ultra)
                boolean success = LutEngine.processImageNative(
                        inPath, finalOutPath, qualityIndex,
                        profile.opacity, profile.grain, profile.grainSize,
                        profile.vignette, profile.rollOff
                );

                if (success) {
                    mContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(new File(finalOutPath))));
                    return finalOutPath;
                }
            } catch (Exception e) { Log.e("filmOS", "Java Error: " + e.getMessage()); }
            return null;
        }

        @Override protected void onPostExecute(String res) { if (mCallback != null) mCallback.onProcessFinished(res); }
    }
}