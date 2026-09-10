package com.cinarli.kiosk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements KioskHttpServer.CommandListener {

    private static final String TAG = "CinarliKiosk";
    private static final String DEFAULT_KIOSK_URL = "https://kapinet.com.tr/gecis/kiosk.php?token=CinarliGecisKiosk2026";
    private static final int HTTP_PORT = 8080;

    private WebView webView;
    private ProgressBar progressBar;
    private View blackoutOverlay;
    private KioskHttpServer httpServer;
    private PowerManager.WakeLock wakeLock;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isScreenDimmed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Tam ekran ve Kiosk pencere bayraklari
        setupWindowFlags();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        blackoutOverlay = findViewById(R.id.blackoutOverlay);

        initWakeLock();
        initWebView();
        initHttpServer();

        // Varsayilan Kiosk sayfasini yukle
        if (savedInstanceState == null) {
            webView.loadUrl(DEFAULT_KIOSK_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void setupWindowFlags() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        if (window != null) {
            window.addFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
        }
        applyImmersiveMode();
    }

    private void applyImmersiveMode() {
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decorView.setSystemUiVisibility(uiOptions);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "RequiresFeature"})
    private void initWebView() {
        // Donanim hizlandirmasi
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setBackgroundColor(0xFF000000);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);

        // Eski nesil Android cihazlar icin AppCache uyumlulugu
        try {
            settings.getClass().getMethod("setAppCacheEnabled", boolean.class).invoke(settings, true);
            settings.getClass().getMethod("setAppCachePath", String.class).invoke(settings, getCacheDir().getAbsolutePath());
        } catch (Exception ignored) {}

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    view.loadUrl(request.getUrl().toString());
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                applyImmersiveMode();
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Kiosk ortaminda SSL sertifika uyarilarini tolere et
                handler.proceed();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceError error) {
                super.onReceivedError(view, error);
                Log.w(TAG, "WebView kaynak yukleme hatasi: " + error);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d(TAG, "[WebView Console] " + consoleMessage.message() + " -- Line " + consoleMessage.lineNumber());
                return true;
            }
        });
    }

    private void initWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "kiosk:main_wakelock"
            );
        }
    }

    private void initHttpServer() {
        httpServer = new KioskHttpServer(HTTP_PORT, this);
        httpServer.start();
    }

    // --- KioskHttpServer.CommandListener Implementasyonu ---

    @Override
    public void onScreenOff() {
        mainHandler.post(() -> {
            try {
                isScreenDimmed = true;
                // Ekran parlakligini minimuma cek
                WindowManager.LayoutParams params = getWindow().getAttributes();
                params.screenBrightness = 0.001f;
                getWindow().setAttributes(params);

                // Ekrana tam siyah overlay yerlestir
                blackoutOverlay.setVisibility(View.VISIBLE);
                applyImmersiveMode();
                Log.i(TAG, "Ekran karartildi (0.0f).");
            } catch (Exception e) {
                Log.e(TAG, "onScreenOff hatasi: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public void onScreenOn() {
        mainHandler.post(() -> {
            try {
                isScreenDimmed = false;
                // Siyah overlay'i kaldir
                blackoutOverlay.setVisibility(View.GONE);

                // Ekran parlakligini normale/maksimuma cek
                WindowManager.LayoutParams params = getWindow().getAttributes();
                params.screenBrightness = 1.0f;
                getWindow().setAttributes(params);

                // WakeLock tetikle
                if (wakeLock != null) {
                    if (wakeLock.isHeld()) {
                        wakeLock.release();
                    }
                    wakeLock.acquire(5000); // 5 saniye uyanma kilidi
                }

                applyImmersiveMode();
                Log.i(TAG, "Ekran acildi (1.0f).");
            } catch (Exception e) {
                Log.e(TAG, "onScreenOn hatasi: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public void onReload() {
        mainHandler.post(() -> {
            try {
                if (webView != null) {
                    webView.reload();
                    Log.i(TAG, "WebView yeniden yuklendi.");
                }
            } catch (Exception e) {
                Log.e(TAG, "onReload hatasi: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public String onGetStatus() {
        long uptimeSeconds = SystemClock.elapsedRealtime() / 1000;
        long hours = uptimeSeconds / 3600;
        long minutes = (uptimeSeconds % 3600) / 60;
        long seconds = uptimeSeconds % 60;
        String uptimeFormatted = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);

        String ipAddress = getDeviceIpAddress();
        Runtime runtime = Runtime.getRuntime();
        long usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMemMb = runtime.maxMemory() / (1024 * 1024);

        return String.format(
                Locale.US,
                "{" +
                "\"status\":\"online\"," +
                "\"device_ip\":\"%s\"," +
                "\"http_port\":%d," +
                "\"uptime\":\"%s\"," +
                "\"uptime_seconds\":%d," +
                "\"screen_dimmed\":%b," +
                "\"memory_used_mb\":%d," +
                "\"memory_max_mb\":%d," +
                "\"current_url\":\"%s\"," +
                "\"android_version\":\"%s\"," +
                "\"api_level\":%d" +
                "}",
                ipAddress,
                HTTP_PORT,
                uptimeFormatted,
                uptimeSeconds,
                isScreenDimmed,
                usedMemMb,
                maxMemMb,
                (webView != null && webView.getUrl() != null) ? webView.getUrl() : DEFAULT_KIOSK_URL,
                Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT
        );
    }

    private String getDeviceIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        if (sAddr != null && sAddr.indexOf(':') < 0) {
                            return sAddr;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    // --- Kiosk Guvenlik ve Buton Yonetimi ---

    @Override
    public void onBackPressed() {
        // Kiosk modunda geri tusu web gecmisi varsa geri gider, yoksa uygulamayi kapatmaz
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        if (httpServer != null && !httpServer.isRunning()) {
            httpServer.start();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (httpServer != null) {
            httpServer.stop();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (webView != null) {
            webView.destroy();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }
}
