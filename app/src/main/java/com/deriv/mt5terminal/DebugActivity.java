package com.deriv.mt5terminal;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Minimal diagnostics screen declared in AndroidManifest.xml
 * (.DebugActivity) but never launched from anywhere in MainActivity.
 * It was missing from source entirely, which — like SketchApplication —
 * is safe as long as nothing ever starts it, but leaves a dangling
 * manifest reference. Filled in with a basic device/app info screen
 * so the reference is real and it's actually useful if you wire up
 * a way to open it later (e.g. long-press on the splash logo).
 */
public class DebugActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView tv = new TextView(this);
        tv.setPadding(32, 32, 32, 32);
        tv.setTextIsSelectable(true);
        tv.setText(
            "DerivMT5Terminal — Debug Info\n\n" +
            "Package: " + getPackageName() + "\n" +
            "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
            "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
        );

        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);
        setContentView(scroll);
    }
}
