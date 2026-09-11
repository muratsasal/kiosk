package com.cinarli.kiosk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements KioskHttpServer.CommandListener {

    private static final int HTTP_PORT = 8080;
    private static final String DEFAULT_URL = "https://kapinet.com.tr/gecis/kiosk.php?token=CinarliGecis2026";
    private static final String API_URL = "https://kapinet.com.tr/gecis/kiosk_saat_kontrol.php?token=CinarliGecis2026";

    private WebView webView;
    private View blackOverlay;
    private KioskHttpServer httpServer;
    private PowerManager.WakeLock wakeLock;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isScreenDimmed = false;
    private boolean isPageLoaded = false;
    private long lastSecretTapTime = 0;
    private int secretTapCount = 0;

    private SharedPreferences prefs;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("kiosk_settings", Context.MODE_PRIVATE);

        webView = findViewById(R.id.webView);

        // Dinamik Siyah Perde
        blackOverlay = new View(this);
        blackOverlay.setBackgroundColor(Color.BLACK);
        blackOverlay.setVisibility(View.GONE);
        ViewGroup rootView = (ViewGroup) findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.addView(blackOverlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
        }

        hideSystemUI();
        setupWebView();
        setupWakeLock();

        // Yerel HTTP Sunucu
        httpServer = new KioskHttpServer(HTTP_PORT, this);
        httpServer.start();

        // 5 Dokunus ile Gizli Yenileme Tetikleyicisi
        setupSecretTap();

        // Arka Plan Bagimsiz Saat Polling Dongusu (Her 30 sn)
        startBackgroundPoller();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        if (webView == null) return;

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                isPageLoaded = false;
                // Baglanti hatasinda 5 saniye sonra tekrar dene
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!isDestroyedCompatible()) {
                            webView.loadUrl(DEFAULT_URL);
                        }
                    }
                }, 5000);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                isPageLoaded = true;
            }
        });

        webView.loadUrl(DEFAULT_URL);
    }

    private void setupWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "CinarliKiosk:WakeLock"
            );
        }
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    // Ekranın sol ust 150x150 alanina 2 sn icinde 5 kez tiklanirsa sayfayi zorla yeniler
    private void setupSecretTap() {
        if (webView != null) {
            webView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        if (event.getX() < 150 && event.getY() < 150) {
                            long now = System.currentTimeMillis();
                            if (now - lastSecretTapTime < 2000) {
                                secretTapCount++;
                                if (secretTapCount >= 5) {
                                    secretTapCount = 0;
                                    reloadWebView();
                                }
                            } else {
                                secretTapCount = 1;
                            }
                            lastSecretTapTime = now;
                        }
                    }
                    return false;
                }
            });
        }
    }

    // ARKA PLAN SAAT & SUNUCU YOKLAMA MOTORU
    private void startBackgroundPoller() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (!isDestroyedCompatible()) {
                    checkScheduleFromServer();
                    try {
                        Thread.sleep(30000); // 30 saniyede bir yokla
                    } catch (InterruptedException ignored) {}
                }
            }
        }).start();
    }

    private void checkScheduleFromServer() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(API_URL + "&t=" + System.currentTimeMillis());
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject json = new JSONObject(sb.toString());
                final String status = json.optString("ekran_durumu", "acik");
                String acilis = json.optString("acilis", "07:30");
                String kapanis = json.optString("kapanis", "19:00");

                // Son gecerli saatleri internet kesilmesine karsi hafizaya kaydet
                prefs.edit().putString("acilis", acilis).putString("kapanis", kapanis).apply();

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if ("kapali".equalsIgnoreCase(status)) {
                            turnScreenOff();
                        } else {
                            turnScreenOn();
                        }
                    }
                });
                return;
            }
        } catch (Exception e) {
            // Internet yoksa hafizadaki saatlere gore offline yonet
            evaluateOfflineSchedule();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void evaluateOfflineSchedule() {
        try {
            String acilis = prefs.getString("acilis", "07:30");
            String kapanis = prefs.getString("kapanis", "19:00");
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
            String nowStr = sdf.format(new Date());

            final boolean shouldBeOpen = (nowStr.compareTo(acilis) >= 0 && nowStr.compareTo(kapanis) < 0);

            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (shouldBeOpen) {
                        turnScreenOn();
                    } else {
                        turnScreenOff();
                    }
                }
            });
        } catch (Exception ignored) {}
    }

    @Override
    public void onScreenOffCommand() {
        turnScreenOff();
    }

    @Override
    public void onScreenOnCommand() {
        turnScreenOn();
    }

    @Override
    public void onReloadCommand() {
        reloadWebView();
    }

    @Override
    public String onStatusRequest() {
        return "{\"status\":\"online\",\"screen_dimmed\":" + isScreenDimmed + "}";
    }

    public synchronized void turnScreenOff() {
        if (isScreenDimmed) return;
        isScreenDimmed = true;

        if (blackOverlay != null) {
            blackOverlay.setVisibility(View.VISIBLE);
        }
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = 0.0f;
        getWindow().setAttributes(params);
    }

    public synchronized void turnScreenOn() {
        if (!isScreenDimmed) return;
        isScreenDimmed = false;

        if (blackOverlay != null) {
            blackOverlay.setVisibility(View.GONE);
        }
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(params);

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(3000);
        }

        // Ekran uyandiginda sayfayi tazeleyip bellegi temizle
        reloadWebView();
    }

    public void reloadWebView() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.reload();
                }
            }
        });
    }

    private boolean isDestroyedCompatible() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return isDestroyed() || isFinishing();
        }
        return isFinishing();
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
    }
}
