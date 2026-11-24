package com.muse.questcompanion;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 42;
    private TextView status;
    private MediaProjectionManager mpm;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = 48;
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Noa Capture — Phase 1 prototype");
        title.setTextSize(20);
        root.addView(title);

        status = new TextView(this);
        status.setText("Ready. Tap Start, then approve the screen-share prompt.\nShots are saved every 5 seconds to Pictures/NoaCapture.");
        status.setPadding(0, 32, 0, 32);
        root.addView(status);

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

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_CAPTURE) {
            if (result == RESULT_OK && data != null) {
                Intent svc = new Intent(this, CaptureService.class);
                svc.putExtra("resultCode", result);
                svc.putExtra("data", data);
                startForegroundService(svc);
                status.setText("Capturing... switch to your game. Shots land in Pictures/NoaCapture every 5 s.");
            } else {
                status.setText("Consent was not given, so nothing was captured.");
            }
        }
    }
}
