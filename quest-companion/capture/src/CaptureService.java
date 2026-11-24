package com.muse.questcompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CaptureService extends Service {
    private static final String CHANNEL = "noa_capture";
    private static final long INTERVAL_MS = 5000;

    private MediaProjection projection;
    private VirtualDisplay vdisplay;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private int width, height, density;
    private int shotCount = 0;
    private volatile boolean running = false;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Noa Capture is running")
                .setContentText("Saving a screenshot every 5 seconds")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }

        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("data");
        if (data == null) { stopSelf(); return START_NOT_STICKY; }

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display disp = wm.getDefaultDisplay();
        android.graphics.Point size = new android.graphics.Point();
        disp.getRealSize(size);
        // Cap the capture at 1024 px wide to keep files small and load low.
        double scale = Math.min(1.0, 1024.0 / Math.max(1, size.x));
        width = Math.max(2, (int) (size.x * scale));
        height = Math.max(2, (int) (size.y * scale));
        DisplayMetrics dm = new DisplayMetrics();
        disp.getMetrics(dm);
        density = dm.densityDpi;

        thread = new HandlerThread("capture");
        thread.start();
        handler = new Handler(thread.getLooper());

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopCapture(); }
        }, handler);

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        vdisplay = projection.createVirtualDisplay("NoaCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, handler);

        running = true;
        handler.postDelayed(this::tick, 1500);
        return START_STICKY;
    }

    private void tick() {
        if (!running) return;
        try { saveOneFrame(); } catch (Exception ignored) {}
        handler.postDelayed(this::tick, INTERVAL_MS);
    }

    private void saveOneFrame() {
        Image img = reader.acquireLatestImage();
        if (img == null) return;
        try {
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;
            Bitmap full = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(buf);
            Bitmap bmp = (rowPadding == 0) ? full : Bitmap.createBitmap(full, 0, 0, width, height);

            String name = "cap_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NoaCapture");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, os);
                }
                shotCount++;
                pruneOldShots();
            }
            if (bmp != full) bmp.recycle();
            full.recycle();
        } catch (Exception ignored) {
        } finally {
            img.close();
        }
    }

    // Keep only the newest MAX_KEEP shots; older ones are deleted automatically.
    private static final int MAX_KEEP = 20;

    private void pruneOldShots() {
        try {
            String[] proj = {MediaStore.Images.Media._ID};
            String sel = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
            String[] args = {"%NoaCapture%"};
            try (android.database.Cursor c = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (c == null) return;
                int idx = 0;
                while (c.moveToNext()) {
                    idx++;
                    if (idx > MAX_KEEP) {
                        long id = c.getLong(0);
                        Uri item = android.content.ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                        getContentResolver().delete(item, null, null);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void stopCapture() {
        running = false;
        if (vdisplay != null) { vdisplay.release(); vdisplay = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (projection != null) { projection.stop(); projection = null; }
    }

    @Override public void onDestroy() {
        stopCapture();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Noa Capture", NotificationManager.IMPORTANCE_LOW));
        }
    }
}
