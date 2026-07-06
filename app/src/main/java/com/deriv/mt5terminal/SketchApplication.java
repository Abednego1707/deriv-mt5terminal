package com.deriv.mt5terminal;

import android.app.Application;

/**
 * Application entry point declared in AndroidManifest.xml
 * (android:name=".SketchApplication").
 *
 * Android instantiates this class before any Activity — including
 * MainActivity — so if it doesn't exist on the classpath the app
 * crashes immediately at process start with:
 *   ClassNotFoundException: com.deriv.mt5terminal.SketchApplication
 *
 * This was the cause of the "initialization fails" crash: the manifest
 * referenced this class, but it had never been created in source.
 */
public class SketchApplication extends Application {

    private static SketchApplication instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    /** Optional global context accessor, in case other classes need it
     *  (e.g. background work outside any Activity). */
    public static SketchApplication getInstance() {
        return instance;
    }
}
