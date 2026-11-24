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

        uploadEnabled = intent.getBooleanExtra("upload", false);
        token = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getString(MainActivity.KEY_TOKEN, "");
        if (uploadEnabled && !token.isEmpty()) {
            handler.postDelayed(this::uploadTick, 20000);
        }
        // Voice player runs whenever we have a token (independent of capture state flag order:
        // `running` is set true below before the thread reads it).

        running = true;
        handler.postDelayed(this::tick, 1500);
        if (!token.isEmpty()) startVoicePlayer();
        return START_STICKY;
    }

    // ---- Phase 2: upload the newest shot to GitHub, no FTP / Tailscale ----

    private boolean uploadEnabled = false;
    private String token = "";
    private volatile byte[] lastJpeg = null;
    private volatile long lastUploadedStamp = 0;
    private volatile long lastShotStamp = 0;
    private String cachedSha = null;

    private static final String GH_URL =
            "https://api.github.com/repos/GrabdCoCoA/with-Eclater-Noa/contents/captures/latest.jpg";

    private void uploadTick() {
        if (!running) return;
        final byte[] jpeg = lastJpeg;
        final long stamp = lastShotStamp;
        if (jpeg != null && stamp != lastUploadedStamp) {
            new Thread(() -> {
                if (uploadToGitHub(jpeg)) lastUploadedStamp = stamp;
            }).start();
        }
        handler.postDelayed(this::uploadTick, 30000);
    }

    private boolean uploadToGitHub(byte[] jpeg) {
        try {
            if (cachedSha == null) cachedSha = fetchSha();
            StringBuilder body = new StringBuilder();
            body.append("{\"message\":\"capture update\",\"branch\":\"main\",\"content\":\"");
            body.append(android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP));
            body.append("\"");
            if (cachedSha != null) body.append(",\"sha\":\"").append(cachedSha).append("\"");
            body.append("}");
            java.net.HttpURLConnection con = (java.net.HttpURLConnection)
                    new java.net.URL(GH_URL).openConnection();
            con.setRequestMethod("PUT");
            con.setRequestProperty("Authorization", "Bearer " + token);
            con.setRequestProperty("Accept", "application/vnd.github+json");
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);
            byte[] payload = body.toString().getBytes("UTF-8");
            con.getOutputStream().write(payload);
            int code = con.getResponseCode();
            if (code == 200 || code == 201) {
                String resp = readAll(con.getInputStream());
                cachedSha = extractSha(resp);
                return true;
            }
            if (code == 409 || code == 422) cachedSha = null; // stale sha; refetch next time
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private String fetchSha() {
        try {
            java.net.HttpURLConnection con = (java.net.HttpURLConnection)
                    new java.net.URL(GH_URL + "?ref=main").openConnection();
            con.setRequestProperty("Authorization", "Bearer " + token);
            con.setRequestProperty("Accept", "application/vnd.github+json");
            if (con.getResponseCode() != 200) return null;
            return extractSha(readAll(con.getInputStream()));
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractSha(String json) {
        if (json == null) return null;
        String key = "\"sha\": \"";
        int i = json.indexOf(key);
        if (i < 0) { key = "\"sha\":\""; i = json.indexOf(key); }
        if (i < 0) return null;
        int s = i + key.length();
        int e = json.indexOf('"', s);
        return e > s ? json.substring(s, e) : null;
    }

    // ---- Phase 3: voice playback. Noa renders speech on her side and puts
    // say_<stamp>.mp3.b64 files in the repo's voice/ folder; this player polls
    // every 10 s, plays new ones in order, then deletes them from the repo. ----

    private Thread voiceThread;

    private void startVoicePlayer() {
        voiceThread = new Thread(() -> {
            while (running) {
                try { pollAndPlay(); } catch (Exception ignored) {}
                try { Thread.sleep(10000); } catch (InterruptedException e) { return; }
            }
        });
        voiceThread.start();
    }

    // Find the string value of `field` at or after position `from`, tolerating
    // both compact ("name":"x") and spaced ("name": "x") JSON.
    private static int[] findJsonString(String json, String field, int from) {
        String key = "\"" + field + "\":";
        int i = json.indexOf(key, from);
        if (i < 0) return null;
        int s = i + key.length();
        while (s < json.length() && (json.charAt(s) == ' ' || json.charAt(s) == '\t')) s++;
        if (s >= json.length() || json.charAt(s) != '"') return null;
        s++;
        int e = json.indexOf('"', s);
        if (e < 0) return null;
        return new int[]{s, e};
    }

    private void setVoiceStatus(String s) {
        try {
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                    .edit().putString("voice_status", s).apply();
        } catch (Exception ignored) {}
    }

    private void pollAndPlay() throws Exception {
        String listing = httpGet("https://api.github.com/repos/GrabdCoCoA/with-Eclater-Noa/contents/voice?ref=main");
        if (listing == null) { setVoiceStatus("poll failed (network or token)"); return; }
        // Sequential scan: each entry has "name" then "sha".
        java.util.List<String[]> files = new java.util.ArrayList<>();
        int idx = 0;
        while (true) {
            int[] nr = findJsonString(listing, "name", idx);
            if (nr == null) break;
            String name = listing.substring(nr[0], nr[1]);
            int[] sr = findJsonString(listing, "sha", nr[1]);
            if (sr == null) break;
            String sha = listing.substring(sr[0], sr[1]);
            idx = sr[1];
            if (name.startsWith("say_") && name.endsWith(".mp3.b64")) {
                files.add(new String[]{name, sha});
            }
        }
        if (files.isEmpty()) { setVoiceStatus("no voice messages waiting"); return; }
        java.util.Collections.sort(files, (a, b) -> a[0].compareTo(b[0]));
        android.content.SharedPreferences prefs =
                getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String lastPlayed = prefs.getString("last_say", "");
        for (String[] f : files) {
            if (!running) return;
            if (f[0].compareTo(lastPlayed) <= 0) continue;
            try {
                playOneVoiceFile(f, prefs);
                lastPlayed = f[0];
            } catch (Exception e) {
                setVoiceStatus("error on " + f[0] + ": " + e.getMessage());
            }
        }
    }

    private String httpGetRaw(String url) {
        try {
            java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            con.setRequestProperty("Authorization", "Bearer " + token);
            con.setRequestProperty("Accept", "application/vnd.github.raw+json");
            if (con.getResponseCode() != 200) return null;
            return readAll(con.getInputStream());
        } catch (Exception e) {
            return null;
        }
    }

    private void playOneVoiceFile(String[] f, android.content.SharedPreferences prefs) throws Exception {
            // Download the file's raw bytes (the .b64 text itself), avoiding the
            // JSON envelope parse entirely.
            String b64 = httpGetRaw("https://api.github.com/repos/GrabdCoCoA/with-Eclater-Noa/contents/voice/" + f[0] + "?ref=main");
            if (b64 == null) { setVoiceStatus("download failed: " + f[0]); return; }
            String clean = b64.replace("\n", "").replace("\r", "").replace(" ", "").replace("\t", "");
            byte[] mp3 = android.util.Base64.decode(clean, android.util.Base64.DEFAULT);
            String head = mp3.length >= 4
                    ? String.format("%02X%02X%02X%02X", mp3[0], mp3[1], mp3[2], mp3[3]) : "????";
            setVoiceStatus("fetched b64=" + clean.length() + " mp3=" + mp3.length + " head=" + head);
            java.io.File tmp = new java.io.File(getCacheDir(), "say.mp3");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) { fos.write(mp3); }
            setVoiceStatus("playing " + f[0]);
            boolean ok = playFile(tmp);
            if (ok) {
                deleteFromGitHub(f[0], f[1]);
                prefs.edit().putString("last_say", f[0]).apply();
            }
            // On failure the file stays in the repo for diagnosis/retry.
    }

    // Returns true only if playback actually completed; records diagnostics
    // into voice_status either way.
    private boolean playFile(java.io.File f) {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int focus = am.requestAudioFocus(null, android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        if (focus != android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            setVoiceStatus("audio focus denied (" + focus + ")");
            return false;
        }
        android.media.MediaPlayer mp = new android.media.MediaPlayer();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final boolean[] completed = {false};
        final int[] errorWhat = {0};
        try {
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnCompletionListener(m -> { completed[0] = true; latch.countDown(); });
            mp.setOnErrorListener((m, w, e) -> { errorWhat[0] = w; latch.countDown(); return true; });
            mp.prepare();
            int durMs = mp.getDuration();
            mp.start();
            boolean inTime = latch.await(90, java.util.concurrent.TimeUnit.SECONDS);
            if (completed[0]) {
                setVoiceStatus("played OK, " + (durMs / 1000.0) + "s");
                return true;
            }
            setVoiceStatus("playback failed: error=" + errorWhat[0] + " timeout=" + !inTime + " dur=" + durMs);
            return false;
        } catch (Exception e) {
            setVoiceStatus("playback exception: " + e.getMessage());
            return false;
        } finally {
            try { mp.release(); } catch (Exception ignored) {}
            am.abandonAudioFocus(null);
        }
    }

    private String httpGet(String url) {
        try {
            java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            con.setRequestProperty("Authorization", "Bearer " + token);
            con.setRequestProperty("Accept", "application/vnd.github+json");
            if (con.getResponseCode() != 200) return null;
            return readAll(con.getInputStream());
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteFromGitHub(String name, String sha) {
        try {
            String url = "https://api.github.com/repos/GrabdCoCoA/with-Eclater-Noa/contents/voice/" + name;
            java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            con.setRequestMethod("DELETE");
            con.setRequestProperty("Authorization", "Bearer " + token);
            con.setRequestProperty("Accept", "application/vnd.github+json");
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);
            String body = "{\"message\":\"played\",\"branch\":\"main\",\"sha\":\"" + sha + "\"}";
            con.getOutputStream().write(body.getBytes("UTF-8"));
            con.getResponseCode();
        } catch (Exception ignored) {}
    }

    private static String extractJsonString(String json, String field) {
        String key = "\"" + field + "\": \"";
        int i = json.indexOf(key);
        if (i < 0) { key = "\"" + field + "\":\""; i = json.indexOf(key); }
        if (i < 0) return null;
        int s = i + key.length();
        int e = json.indexOf('"', s);
        return e > s ? json.substring(s, e) : null;
    }

    private static String readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
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
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos);
            byte[] jpeg = baos.toByteArray();
            if (uri != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(jpeg);
                }
                shotCount++;
                pruneOldShots();
            }
            lastJpeg = jpeg;
            lastShotStamp = System.currentTimeMillis();
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
        if (voiceThread != null) voiceThread.interrupt();
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
