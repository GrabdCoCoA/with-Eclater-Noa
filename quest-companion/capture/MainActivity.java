package com.muse.questcompanion;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 42;
    public static final String PREFS = "noa_prefs";
    public static final String KEY_TOKEN = "gh_token";

    private TextView status;
    private EditText tokenInput;
    private CheckBox uploadCheck;
    private MediaProjectionManager mpm;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = 48;
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Noa Capture — Phase 3");
        title.setTextSize(20);
        root.addView(title);

        TextView voiceStatus = new TextView(this);
        String vs = prefs.getString("voice_status", "not checked yet");
        voiceStatus.setText("Voice: " + vs + "\n(Noa's voice messages play automatically while capture runs.)");
        root.addView(voiceStatus);

        status = new TextView(this);
        status.setText("Shots are saved every 5 s to Pictures/NoaCapture (newest 20 kept).\nIf upload is on, the newest shot is also sent to GitHub every 30 s.");
        status.setPadding(0, 24, 0, 24);
        root.addView(status);

        tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String saved = prefs.getString(KEY_TOKEN, "");
        // Never echo a saved token back into the field: it would show in screenshots.
        tokenInput.setHint(saved.isEmpty()
                ? "GitHub token (fine-grained, this repo only)"
                : "Token is saved. Enter a new one only to replace it.");
        root.addView(tokenInput);

        Button saveToken = new Button(this);
        saveToken.setText("Save token");
        saveToken.setOnClickListener(v -> {
            String t = tokenInput.getText().toString().trim();
            if (t.isEmpty()) {
                status.setText("Field is empty, so the saved token was kept.");
                return;
            }
            prefs.edit().putString(KEY_TOKEN, t).apply();
            tokenInput.setText("");
            tokenInput.setHint("Token is saved. Enter a new one only to replace it.");
            status.setText("Token saved on this device only.");
        });
        root.addView(saveToken);

        uploadCheck = new CheckBox(this);
        uploadCheck.setText("Upload newest shot to GitHub");
        uploadCheck.setChecked(!saved.isEmpty());
        root.addView(uploadCheck);

        Button voiceTest = new Button(this);
        voiceTest.setText("Check engines (diagnostic, no sound)");
        voiceTest.setOnClickListener(v -> runVoiceTest());
        root.addView(voiceTest);

        Button start = new Button(this);
        start.setText("Start capture");
        start.setOnClickListener(v -> {
            status.setText("Asking for your consent to share the screen...");
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop capture");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            status.setText("Stopped.");
        });
        root.addView(stop);

        setContentView(root);
    }

    private android.speech.tts.TextToSpeech tts;

    private void runVoiceTest() {
        StringBuilder sb = new StringBuilder();
        boolean srAvail = android.speech.SpeechRecognizer.isRecognitionAvailable(this);
        sb.append("Speech recognition available: ").append(srAvail ? "YES" : "NO").append("\n");
        java.util.List<android.content.pm.ResolveInfo> ttsEngines = getPackageManager()
                .queryIntentServices(new Intent(android.speech.tts.TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0);
        sb.append("TTS engines installed: ").append(ttsEngines.size()).append("\n");
        java.util.List<android.content.pm.ResolveInfo> services = getPackageManager()
                .queryIntentServices(new Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0);
        sb.append("Recognition services: ").append(services.size()).append("\n");
        status.setText(sb.toString() + "Checking speech output...");
        if (tts != null) { speakTest(sb); return; }
        tts = new android.speech.tts.TextToSpeech(this, statusCode -> {
            if (statusCode == android.speech.tts.TextToSpeech.SUCCESS) {
                int ja = tts.isLanguageAvailable(java.util.Locale.JAPAN);
                sb.append("TTS engine: ").append(tts.getDefaultEngine()).append("\n");
                sb.append("Japanese TTS: ").append(ja >= 0 ? "YES" : "NO").append("\n");
                int jaVoices = 0, total = 0;
                try {
                    java.util.Set<android.speech.tts.Voice> vs = tts.getVoices();
                    if (vs != null) {
                        total = vs.size();
                        for (android.speech.tts.Voice v : vs) {
                            if (v.getLocale() != null && "ja".equals(v.getLocale().getLanguage())) jaVoices++;
                        }
                    }
                } catch (Exception ignored) {}
                sb.append("Voices total: ").append(total).append(" (JA: ").append(jaVoices).append(")\n");
                speakTest(sb);
            } else {
                sb.append("TTS init FAILED\n");
                status.setText(sb.toString());
            }
        });
    }

    private void speakTest(StringBuilder sb) {
        try {
            int ja = tts.isLanguageAvailable(java.util.Locale.JAPAN);
            if (ja >= 0) {
                tts.setLanguage(java.util.Locale.JAPAN);
                tts.speak("こんにちは。私はエクレッターのあです。声が聞こえていますか？",
                        android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "ja1");
            }
            tts.setLanguage(java.util.Locale.US);
            tts.speak("Hello, this is Noa speaking.",
                    android.speech.tts.TextToSpeech.QUEUE_ADD, null, "en1");
            sb.append("Speaking now (JA then EN)...\n");
        } catch (Exception e) {
            sb.append("Speak error: ").append(e.getMessage()).append("\n");
        }
        status.setText(sb.toString());
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_CAPTURE) {
            if (result == RESULT_OK && data != null) {
                Intent svc = new Intent(this, CaptureService.class);
                svc.putExtra("resultCode", result);
                svc.putExtra("data", data);
                svc.putExtra("upload", uploadCheck.isChecked());
                startForegroundService(svc);
                status.setText("Capturing... switch to your game.");
            } else {
                status.setText("Consent was not given, so nothing was captured.");
            }
        }
    }
}
