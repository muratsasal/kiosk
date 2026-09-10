package com.cinarli.kiosk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class KioskHttpServer {
    private static final int PORT = 8080;
    private final MainActivity activity;
    private final Handler mainHandler;
    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private final long startTime;

    public KioskHttpServer(MainActivity activity) {
        this.activity = activity;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.startTime = SystemClock.elapsedRealtime();
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(PORT);
                    while (isRunning) {
                        Socket socket = serverSocket.accept();
                        handleClient(socket);
                    }
                } catch (Exception e) {
                    // Sunucu kapandı veya hata
                }
            }
        }).start();
    }

    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
    }

    private void handleClient(final Socket socket) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    String line = reader.readLine();
                    if (line == null) {
                        socket.close();
                        return;
                    }

                    String[] parts = line.split(" ");
                    String path = parts.length > 1 ? parts[1] : "/";

                    String responseJson = "{}";
                    if (path.startsWith("/kapat")) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                activity.turnScreenOff();
                            }
                        });
                        responseJson = "{\"status\":\"ok\",\"action\":\"screen_off\"}";
                    } else if (path.startsWith("/ac")) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                activity.turnScreenOn();
                            }
                        });
                        responseJson = "{\"status\":\"ok\",\"action\":\"screen_on\"}";
                    } else if (path.startsWith("/yenile")) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                activity.reloadWebView();
                            }
                        });
                        responseJson = "{\"status\":\"ok\",\"action\":\"reload\"}";
                    } else if (path.startsWith("/durum")) {
                        long uptimeSec = (SystemClock.elapsedRealtime() - startTime) / 1000;
                        responseJson = "{\"status\":\"online\",\"uptime_seconds\":" + uptimeSec + "}";
                    } else {
                        responseJson = "{\"status\":\"error\",\"message\":\"Bilinmeyen komut\"}";
                    }

                    byte[] responseBytes = responseJson.getBytes(StandardCharsets.UTF_8);
                    String httpResponse = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json; charset=UTF-8\r\n" +
                            "Content-Length: " + responseBytes.length + "\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Connection: close\r\n\r\n";

                    OutputStream out = socket.getOutputStream();
                    out.write(httpResponse.getBytes(StandardCharsets.UTF_8));
                    out.write(responseBytes);
                    out.flush();
                } catch (Exception ignored) {
                } finally {
                    try {
                        if (socket != null && !socket.isClosed()) {
                            socket.close();
                        }
                    } catch (Exception ignored) {}
                }
            }
        }).start();
    }
}
