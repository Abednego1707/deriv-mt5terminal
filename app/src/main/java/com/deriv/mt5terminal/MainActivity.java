package com.deriv.mt5terminal;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.Executor;

import androidx.fragment.app.FragmentActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

public class MainActivity extends FragmentActivity {

    // ╔══════════════════════════════════════════╗
    //   CONFIG
    // ╚══════════════════════════════════════════╝
    private static final String WEBSITE_URL  = "https://abednego1707.github.io/deriv-mt5terminal/";
    private static final String APP_TAGLINE  = "Designed to perfection";
    private static final String APP_VERSION  = "v12.5.0";
    private static final int    SPLASH_DELAY = 3000;
    private static final long   AD_CHECK_INTERVAL = 3000;
    private static final String CHANNEL_ID   = "mt5_terminal_alerts";
    // ════════════════════════════════════════════

    private View splashLayout, mainLayout, noInternetLayout, splashCenter, statusPill;
    private View logoFrame, splashLogo, scanLine, statusDot, dividerLine, bracketTL, bracketTR;
    private TextView splashTagline, splashVersion, statusText;
    private WebView webview1;
    private ProgressBar webProgress;
    private View retryBtn;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ObjectAnimator scanAnim;
    private ValueAnimator dividerAnim;
    private Runnable adRemovalRunnable;

    private final String[] STATUS_MSGS = { "Initializing...", "Connecting...", "Loading assets...", "Almost ready..." };
    private int statusIndex = 0;

    private static final String[] AD_DOMAINS = {
        "doubleclick.net", "googleadservices.com", "googlesyndication.com",
        "adservice.google.com", "googletagmanager.com", "google-analytics.com",
        "googletagservices.com", "pagead2.googlesyndication.com",
        "securepubads.g.doubleclick.net", "pubads.g.doubleclick.net",
        "ads.pubmatic.com", "ad.360yield.com", "adserver.adtechus.com", "ads.contextweb.com"
    };

    private static final String AD_REMOVAL_SCRIPT =
        "javascript:(function(){function r(){['div[class*=\"ad-\"]','.adsbygoogle','ins.adsbygoogle','[class*=\"sponsored\"]','amp-ad','div[id^=\"google_ads\"]','div[id^=\"div-gpt-ad\"]'].forEach(function(s){try{document.querySelectorAll(s).forEach(function(e){if(e.parentNode)e.parentNode.removeChild(e);});}catch(x){}});}r();if(window._ari)clearInterval(window._ari);window._ari=setInterval(r," + AD_CHECK_INTERVAL + ");})()";

    // ══════════════════════════════════════════════════
    // TERMINAL BRIDGE — window.TerminalBridge in the web app
    // (sounds, vibration, notifications, storage, orientation, biometrics)
    // ══════════════════════════════════════════════════
    public class TerminalBridge {
        @JavascriptInterface
        public void playSound(String type) {
            try {
                String res = "terminal_" + type;
                int id = getResources().getIdentifier(res, "raw", getPackageName());
                if (id == 0) return;
                MediaPlayer mp = MediaPlayer.create(MainActivity.this, id);
                if (mp != null) { mp.setOnCompletionListener(MediaPlayer::release); mp.start(); }
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null) return;
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(ms);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public boolean sendLocalNotification(String title, String message) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm == null) return false;
                if (Build.VERSION.SDK_INT >= 26) {
                    nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "MT5 Terminal Alerts", NotificationManager.IMPORTANCE_HIGH));
                }
                Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(MainActivity.this, CHANNEL_ID)
                    : new Notification.Builder(MainActivity.this);
                b.setContentTitle(title).setContentText(message)
                 .setSmallIcon(android.R.drawable.stat_notify_chat).setAutoCancel(true);
                nm.notify((int) (System.currentTimeMillis() % 100000), b.build());
                return true;
            } catch (Exception e) { return false; }
        }

        private File terminalDir() {
            File base = new File(Environment.getExternalStorageDirectory(),
                "Android/media/" + getPackageName() + "/terminal");
            if (!base.exists() && base.mkdirs()) {
                new File(base, "config").mkdirs();
                new File(base, "templates").mkdirs();
                new File(base, "MQL5/Indicators").mkdirs();
                new File(base, "MQL5/Experts").mkdirs();
                new File(base, "MQL5/Scripts").mkdirs();
                new File(base, "logs").mkdirs();
                try {
                    FileOutputStream fos = new FileOutputStream(new File(base, "logs/terminal.log"));
                    fos.write(("Deriv MT5 terminal data folder created " + new java.util.Date() + "\n").getBytes("UTF-8"));
                    fos.close();
                } catch (Exception ignored) {}
            }
            return base;
        }

        @JavascriptInterface
        public boolean hasStorageAccess() {
            if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void requestStoragePermission() {
            runOnUiThread(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        if (!Environment.isExternalStorageManager()) {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                            Toast.makeText(MainActivity.this,
                                "Enable 'Allow access to manage all files' for Deriv MT5",
                                Toast.LENGTH_LONG).show();
                        }
                    } else if (Build.VERSION.SDK_INT >= 23) {
                        requestPermissions(new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4002);
                    }
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public String listDeviceStorage(String relPath) {
            try {
                if (!hasStorageAccess()) return "{\"error\":\"no_permission\"}";
                File root = Environment.getExternalStorageDirectory();
                File dir = (relPath == null || relPath.isEmpty() || relPath.equals("/"))
                    ? root : new File(root, relPath);
                if (!dir.getCanonicalPath().startsWith(root.getCanonicalPath())) return "[]";
                if (!dir.exists() || !dir.isDirectory()) return "[]";
                File[] items = dir.listFiles();
                if (items == null) return "[]";
                StringBuilder sb = new StringBuilder("[");
                int count = 0;
                for (File f : items) {
                    if (count >= 400) break;
                    if (count > 0) sb.append(",");
                    sb.append("{\"name\":\"").append(f.getName().replace("\\", "\\\\").replace("\"", "\\\""))
                      .append("\",\"dir\":").append(f.isDirectory())
                      .append(",\"size\":").append(f.length())
                      .append(",\"modified\":").append(f.lastModified()).append("}");
                    count++;
                }
                return sb.append("]").toString();
            } catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface
        public String readDeviceFile(String relPath) {
            try {
                if (!hasStorageAccess()) return "";
                File root = Environment.getExternalStorageDirectory();
                File f = new File(root, relPath);
                if (!f.getCanonicalPath().startsWith(root.getCanonicalPath())) return "";
                if (!f.exists() || f.isDirectory() || f.length() > 524288) return "";
                java.io.FileInputStream fis = new java.io.FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int read = fis.read(buf);
                fis.close();
                return new String(buf, 0, Math.max(read, 0), "UTF-8");
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public boolean saveTextFile(String relPath, String content) {
            try {
                File out = new File(terminalDir(), relPath);
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                FileOutputStream fos = new FileOutputStream(out);
                fos.write(content.getBytes("UTF-8"));
                fos.close();
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public String listFiles(String relPath) {
            try {
                File dir = (relPath == null || relPath.isEmpty() || relPath.equals("/"))
                    ? terminalDir() : new File(terminalDir(), relPath);
                if (!dir.exists() || !dir.isDirectory()) return "[]";
                File[] items = dir.listFiles();
                if (items == null) return "[]";
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < items.length; i++) {
                    File f = items[i];
                    if (i > 0) sb.append(",");
                    sb.append("{\"name\":\"").append(f.getName().replace("\"", "\\\""))
                      .append("\",\"dir\":").append(f.isDirectory())
                      .append(",\"size\":").append(f.length())
                      .append(",\"modified\":").append(f.lastModified()).append("}");
                }
                return sb.append("]").toString();
            } catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface
        public String readTextFile(String relPath) {
            try {
                File f = new File(terminalDir(), relPath);
                if (!f.exists() || f.isDirectory() || f.length() > 524288) return "";
                java.io.FileInputStream fis = new java.io.FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int read = fis.read(buf);
                fis.close();
                return new String(buf, 0, Math.max(read, 0), "UTF-8");
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public boolean deleteFile(String relPath) {
            try { return new File(terminalDir(), relPath).delete(); }
            catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public String getStorageUsage() {
            long used = dirSize(terminalDir());
            return "{\"used\":" + used + ",\"path\":\"/sdcard/Android/media/"
                + getPackageName() + "/terminal\"}";
        }
        private long dirSize(File dir) {
            long total = 0;
            File[] items = dir.listFiles();
            if (items == null) return 0;
            for (File f : items) total += f.isDirectory() ? dirSize(f) : f.length();
            return total;
        }

        @JavascriptInterface
        public String getPermissionStatus() {
            boolean notif = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean storage = Build.VERSION.SDK_INT >= 30
                ? Environment.isExternalStorageManager()
                : checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            return "{\"internet\":true,\"network_state\":true,\"notifications\":" + notif +
                ",\"vibrate\":true,\"location_fine\":" + fine + ",\"location_coarse\":" + coarse +
                ",\"storage\":" + storage + ",\"manage_all_files\":" + (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) +
                ",\"wake_lock\":true,\"foreground_service\":true,\"api_level\":" + Build.VERSION.SDK_INT +
                ",\"storage_path\":\"/sdcard/Android/media/" + getPackageName() + "/terminal\"}";
        }

        @JavascriptInterface
        public void requestAllPermissions() {
            runOnUiThread(() -> {
                java.util.ArrayList<String> perms = new java.util.ArrayList<>();
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    perms.add(Manifest.permission.POST_NOTIFICATIONS);
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                    perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                    perms.add(Manifest.permission.READ_MEDIA_IMAGES);
                if (!perms.isEmpty() && Build.VERSION.SDK_INT >= 23)
                    requestPermissions(perms.toArray(new String[0]), 4001);
            });
        }

        @JavascriptInterface
        public void requestManageAllFiles() {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void openAppSettings() {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void setOrientation(String target) {
            runOnUiThread(() -> setRequestedOrientation(
                "landscape".equals(target)
                    ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT));
        }

        // ── NATIVE BIOMETRICS (App Lock fingerprint) ─────────────
        // Uses androidx.biometric, NOT the raw android.hardware.biometrics
        // framework API. The raw framework class fails silently on some
        // OEM builds (Samsung One UI) — androidx.biometric ships the
        // compatibility shims that fix this.
        /** Auto-detect: true when the device has enrolled biometrics. */
        @JavascriptInterface
        public boolean canUseBiometrics() {
            try {
                BiometricManager bm = BiometricManager.from(MainActivity.this);
                int result = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
                return result == BiometricManager.BIOMETRIC_SUCCESS;
            } catch (Exception ignored) {}
            return false;
        }

        /** Show the system biometric prompt. Result is delivered to the page:
         *  window.dispatchEvent(new CustomEvent('terminal-biometric-result',
         *      { detail: { ok: true|false } }))                            */
        @JavascriptInterface
        public void requestBiometricAuth(String title) {
            runOnUiThread(() -> {
                try {
                    Executor executor = ContextCompat.getMainExecutor(MainActivity.this);
                    BiometricPrompt prompt = new BiometricPrompt(MainActivity.this, executor,
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                super.onAuthenticationSucceeded(result);
                                sendBiometricResult(true);
                            }
                            @Override
                            public void onAuthenticationError(int errorCode, CharSequence errString) {
                                super.onAuthenticationError(errorCode, errString);
                                sendBiometricResult(false);
                            }
                            @Override
                            public void onAuthenticationFailed() {
                                super.onAuthenticationFailed();
                                // Single failed scan — androidx keeps the prompt
                                // open for retries; only error/success end the flow.
                            }
                        });

                    BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(title == null || title.isEmpty() ? "Unlock Deriv MT5" : title)
                        .setNegativeButtonText("Cancel")
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                        .build();

                    prompt.authenticate(promptInfo);
                } catch (Exception e) { sendBiometricResult(false); }
            });
        }
    }

    private void sendBiometricResult(boolean ok) {
        if (webview1 == null) return;
        webview1.post(() -> webview1.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('terminal-biometric-result',{detail:{ok:" + ok + "}}))", null));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (webview1 != null)
            webview1.evaluateJavascript(
                "window.dispatchEvent(new Event('terminal-permissions-changed'))", null);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getResources().getIdentifier("main", "layout", getPackageName()));
        bindViews();
        setupStatusBar();
        setupWebView();
        initSplash();
    }

    private View v(String id) {
        int resId = getResources().getIdentifier(id, "id", getPackageName());
        return resId == 0 ? null : findViewById(resId);
    }

    private void bindViews() {
        splashLayout = v("splashLayout"); mainLayout = v("mainLayout");
        noInternetLayout = v("noInternetLayout"); splashCenter = v("splashCenter");
        statusPill = v("statusPill"); logoFrame = v("logoFrame");
        splashLogo = v("splashLogo"); scanLine = v("scanLine");
        statusDot = v("statusDot"); dividerLine = v("dividerLine");
        bracketTL = v("bracketTL"); bracketTR = v("bracketTR");
        splashTagline = (TextView) v("splashTagline");
        splashVersion = (TextView) v("splashVersion");
        statusText = (TextView) v("statusText");
        webview1 = (WebView) v("webview1");
        webProgress = (ProgressBar) v("webProgress");
        retryBtn = v("retryBtn");
    }

    private void setupStatusBar() {
        if (Build.VERSION.SDK_INT >= 21) {
            Window w = getWindow();
            w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(0xFF0D3A72);
        }
    }

    private void initSplash() {
        if (splashTagline != null) splashTagline.setText(APP_TAGLINE);
        if (splashVersion != null) splashVersion.setText(APP_VERSION);
        if (retryBtn != null) retryBtn.setOnClickListener(view -> retryConnection());

        View[] brackets = { bracketTL, bracketTR };
        for (View b : brackets) {
            if (b == null) continue;
            b.setAlpha(0f);
            b.animate().alpha(1f).setDuration(600).setStartDelay(100)
                .setInterpolator(new DecelerateInterpolator()).start();
        }
        if (logoFrame != null) {
            logoFrame.setScaleX(0f); logoFrame.setScaleY(0f);
            logoFrame.animate().scaleX(1f).scaleY(1f).setDuration(700)
                .setInterpolator(new OvershootInterpolator(1.3f)).start();
            if (scanLine != null) {
                logoFrame.post(() -> {
                    scanAnim = ObjectAnimator.ofFloat(scanLine, "translationY", 0f, logoFrame.getHeight());
                    scanAnim.setDuration(1400);
                    scanAnim.setRepeatCount(ValueAnimator.INFINITE);
                    scanAnim.setInterpolator(new LinearInterpolator());
                    scanAnim.start();
                });
            }
        }
        if (splashLogo != null) {
            ObjectAnimator f = ObjectAnimator.ofFloat(splashLogo, "translationY", 0f, -4f, 0f);
            f.setDuration(2600); f.setRepeatCount(ValueAnimator.INFINITE);
            f.setInterpolator(new AccelerateDecelerateInterpolator()); f.start();
        }
        if (splashCenter != null) {
            splashCenter.setAlpha(0f); splashCenter.setTranslationY(20f);
            splashCenter.animate().alpha(1f).translationY(0f).setDuration(700)
                .setInterpolator(new DecelerateInterpolator()).start();
            if (dividerLine != null) {
                dividerLine.post(() -> {
                    dividerAnim = ValueAnimator.ofInt(0, Math.max(0, splashCenter.getWidth() - 96));
                    dividerAnim.setDuration(800); dividerAnim.setStartDelay(500);
                    dividerAnim.addUpdateListener(a -> {
                        ViewGroup.LayoutParams lp = dividerLine.getLayoutParams();
                        lp.width = (int) a.getAnimatedValue();
                        dividerLine.setLayoutParams(lp);
                    });
                    dividerAnim.start();
                });
            }
        }
        if (statusDot != null) {
            ObjectAnimator blink = ObjectAnimator.ofFloat(statusDot, "alpha", 1f, 0.1f, 1f);
            blink.setDuration(800); blink.setRepeatCount(ValueAnimator.INFINITE); blink.start();
        }
        if (statusText != null) {
            Runnable cycler = new Runnable() {
                @Override public void run() {
                    if (statusText == null) return;
                    statusIndex = (statusIndex + 1) % STATUS_MSGS.length;
                    statusText.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                        statusText.setText(STATUS_MSGS[statusIndex]);
                        statusText.animate().alpha(1f).setDuration(200).start();
                    }).start();
                    handler.postDelayed(this, 900);
                }
            };
            handler.postDelayed(cycler, 900);
        }
        if (statusPill != null) {
            statusPill.setTranslationY(20f); statusPill.setAlpha(0f);
            statusPill.animate().translationY(0f).alpha(1f).setDuration(500).setStartDelay(400).start();
        }
        if (webview1 != null) webview1.loadUrl(WEBSITE_URL);
        checkNetwork();
    }

    private void setupWebView() {
        if (webview1 == null) return;
        WebSettings ws = webview1.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setGeolocationEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setSupportZoom(false);
        // OFFLINE SUPPORT: serve cached bundle when network is unavailable
        ws.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setUserAgentString(ws.getUserAgentString() + " Web4App/1.0");
        webview1.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);

        // ── DEVICE DARK MODE IMMUNITY ────────────────────────────
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                WebSettings.class.getMethod("setAlgorithmicDarkeningAllowed", boolean.class)
                    .invoke(ws, false);
            } else if (Build.VERSION.SDK_INT >= 29) {
                WebSettings.class.getMethod("setForceDark", int.class).invoke(ws, 0);
            }
        } catch (Exception ignored) {}

        // Expose the native bridge → window.TerminalBridge
        webview1.addJavascriptInterface(new TerminalBridge(), "TerminalBridge");

        webview1.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap fav) {
                if (webProgress != null) { webProgress.setVisibility(View.VISIBLE); webProgress.setProgress(0); }
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (webProgress != null) webProgress.setVisibility(View.GONE);
                injectAdRemovalScript(); startAdRemovalRecurring();
            }
            @Override public void onReceivedError(WebView view, int code, String desc, String url) {
                if (url != null && url.equals(WEBSITE_URL)) view.loadData(offlinePage(), "text/html; charset=utf-8", "UTF-8");
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler h, SslError e) { h.proceed(); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (isAdUrl(url)) return true;
                if (url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("whatsapp:") ||
                    url.startsWith("intent:") || url.startsWith("upi:")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString().toLowerCase();
                if (isAdUrl(url) || url.contains("ads.js") || url.contains("adloader"))
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream("".getBytes()));
                return super.shouldInterceptRequest(view, request);
            }
        });

        webview1.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int p) {
                if (webProgress != null) { webProgress.setProgress(p); if (p >= 100) webProgress.setVisibility(View.GONE); }
            }
            @Override public boolean onJsAlert(WebView view, String url, String msg, JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg)
                    .setPositiveButton("OK", (d, w) -> r.confirm()).setCancelable(false).show();
                return true;
            }
            @Override public boolean onJsConfirm(WebView view, String url, String msg, JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg)
                    .setPositiveButton("Yes", (d, w) -> r.confirm())
                    .setNegativeButton("No", (d, w) -> r.cancel()).setCancelable(false).show();
                return true;
            }
            @Override public void onGeolocationPermissionsShowPrompt(String o, GeolocationPermissions.Callback c) {
                c.invoke(o, true, false);
            }
        });

        webview1.setDownloadListener((url, ua, cd, mt, cl) -> {
            try {
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, cd, mt));
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm != null) { dm.enqueue(req); Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show(); }
            } catch (Exception e) { Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show(); }
        });
    }

    private boolean isAdUrl(String url) {
        for (String d : AD_DOMAINS) if (url.contains(d)) return true;
        return false;
    }
    private void injectAdRemovalScript() { if (webview1 != null) webview1.evaluateJavascript(AD_REMOVAL_SCRIPT, null); }
    private void startAdRemovalRecurring() {
        stopAdRemovalRecurring();
        adRemovalRunnable = new Runnable() {
            @Override public void run() {
                if (webview1 != null && mainLayout != null && mainLayout.getVisibility() == View.VISIBLE) {
                    injectAdRemovalScript();
                    handler.postDelayed(this, AD_CHECK_INTERVAL);
                }
            }
        };
        handler.postDelayed(adRemovalRunnable, AD_CHECK_INTERVAL);
    }
    private void stopAdRemovalRecurring() {
        if (adRemovalRunnable != null) { handler.removeCallbacks(adRemovalRunnable); adRemovalRunnable = null; }
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) { return false; }
    }

    // OFFLINE-FIRST: always continue into the WebView after the splash.
    private void checkNetwork() {
        if (noInternetLayout != null) noInternetLayout.setVisibility(View.GONE);
        handler.postDelayed(() ->
            animateFade(splashLayout, false, () -> {
                if (splashLayout != null) splashLayout.setVisibility(View.GONE);
                if (mainLayout != null) {
                    mainLayout.setVisibility(View.VISIBLE);
                    animateFade(mainLayout, true, () -> { injectAdRemovalScript(); startAdRemovalRecurring(); });
                }
                if (!isOnline())
                    Toast.makeText(this, "Offline — opening terminal with cached historical data", Toast.LENGTH_LONG).show();
            }), SPLASH_DELAY);
    }

    private void retryConnection() {
        animateFade(noInternetLayout, false, () -> {
            if (noInternetLayout != null) noInternetLayout.setVisibility(View.GONE);
            if (splashLayout != null) { splashLayout.setVisibility(View.VISIBLE); animateFade(splashLayout, true, null); }
            if (webview1 != null) webview1.loadUrl(WEBSITE_URL);
            checkNetwork();
        });
    }

    private void animateFade(View view, boolean in, Runnable done) {
        if (view == null) { if (done != null) done.run(); return; }
        view.setAlpha(in ? 0f : 1f);
        view.animate().alpha(in ? 1f : 0f).setDuration(400)
            .setInterpolator(new DecelerateInterpolator())
            .withEndAction(() -> { if (done != null) done.run(); }).start();
    }

    private String offlinePage() {
        return "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>*{margin:0;padding:0;box-sizing:border-box}body{background:#0C1929;color:#fff;"
            + "font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;"
            + "height:100vh;gap:10px;padding:32px;text-align:center}h2{font-size:20px;font-weight:700}"
            + "p{color:#8FA7C4;font-size:13px;line-height:1.7}button{margin-top:24px;padding:14px 40px;"
            + "background:#1963B5;color:#fff;border:none;border-radius:6px;font-size:14px;font-weight:700}"
            + "</style></head><body><span style='font-size:52px'>&#128225;</span><h2>No Cached Terminal</h2>"
            + "<p>First launch needs internet to download the terminal.<br>After that it opens offline with historical data.</p>"
            + "<button onclick='location.reload()'>RETRY</button></body></html>";
    }

    @Override
    public void onBackPressed() {
        if (noInternetLayout != null && noInternetLayout.getVisibility() == View.VISIBLE) { showExit(); return; }
        if (webview1 != null && webview1.canGoBack()) webview1.goBack();
        else showExit();
    }

    private void showExit() {
        new AlertDialog.Builder(this).setTitle("Exit?")
            .setMessage("Do you want to exit the app?")
            .setPositiveButton("Exit", (d, w) -> finishAffinity())
            .setNegativeButton("Stay", null).show();
    }

    @Override protected void onResume() {
        super.onResume();
        if (webview1 != null) {
            webview1.onResume();
            startAdRemovalRecurring();
            // Returning from Settings screens → refresh permission pills
            webview1.evaluateJavascript(
                "window.dispatchEvent(new Event('terminal-permissions-changed'))", null);
        }
    }
    @Override protected void onPause() {
        super.onPause();
        if (webview1 != null) webview1.onPause();
        stopAdRemovalRecurring();
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        stopAdRemovalRecurring();
        if (scanAnim != null) scanAnim.cancel();
        if (dividerAnim != null) dividerAnim.cancel();
        if (webview1 != null) { webview1.stopLoading(); webview1.destroy(); webview1 = null; }
    }
}
