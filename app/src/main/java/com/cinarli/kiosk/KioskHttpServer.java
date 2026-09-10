package com.cinarli.kiosk;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class KioskHttpServer {
    private static final String TAG = "KioskHttpServer";
    private final int port;
    private final CommandListener listener;
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean isRunning = false;

    public interface CommandListener {
        void onScreenOff();
        void onScreenOn();
        void onReload();
        String onGetStatus();
    }

    public KioskHttpServer(int port, CommandListener listener) {
        this.port = port;
        this.listener = listener;
    }

    public synchronized void start() {
        if (isRunning) {
            Log.w(TAG, "HTTP Sunucu zaten calisiyor.");
            return;
        }

        isRunning = true;
        threadPool = Executors.newCachedThreadPool();

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                Log.i(TAG, "Kiosk HTTP Sunucusu " + port + " portunda baslatildi.");

                while (isRunning && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        if (threadPool != null && !threadPool.isShutdown()) {
                            threadPool.execute(() -> handleClient(clientSocket));
                        }
                    } catch (SocketException se) {
                        if (!isRunning) {
                            break; // Normal kapatma sirasinda olusan istisna
                        }
                        Log.e(TAG, "Socket accept hatasi: " + se.getMessage());
                    } catch (IOException e) {
                        Log.e(TAG, "I/O hatasi: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Sunucu soketi baslatilamadi: " + e.getMessage(), e);
            } finally {
                stop();
            }
        }, "KioskHttpServer-Acceptor").start();
    }

    public synchronized void stop() {
        isRunning = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdown();
        }
        Log.i(TAG, "Kiosk HTTP Sunucusu durduruldu.");
    }

    public boolean isRunning() {
        return isRunning;
    }

    private void handleClient(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = socket.getOutputStream()) {

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.trim().isEmpty()) {
                return;
            }

            Log.d(TAG, "HTTP Istek: " + requestLine);
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendResponse(out, 400, "text/plain", "Bad Request");
                return;
            }

            String method = parts[0];
            String uri = parts[1];

            // Sadece path kismini ayikla (query params varsa cikar)
            String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;

            if (!"GET".equalsIgnoreCase(method)) {
                sendResponse(out, 405, "application/json; charset=utf-8", "{\"error\":\"Only GET method is supported\"}");
                return;
            }

            switch (path) {
                case "/kapat":
                    if (listener != null) {
                        listener.onScreenOff();
                    }
                    sendResponse(out, 200, "application/json; charset=utf-8", "{\"status\":\"ok\",\"action\":\"screen_off\",\"message\":\"Ekran parlakligi 0.0f yapildi ve karartildi\"}");
                    break;

                case "/ac":
                    if (listener != null) {
                        listener.onScreenOn();
                    }
                    sendResponse(out, 200, "application/json; charset=utf-8", "{\"status\":\"ok\",\"action\":\"screen_on\",\"message\":\"Ekran parlakligi 1.0f yapildi ve uyanma kilidi tetiklendi\"}");
                    break;

                case "/yenile":
                    if (listener != null) {
                        listener.onReload();
                    }
                    sendResponse(out, 200, "application/json; charset=utf-8", "{\"status\":\"ok\",\"action\":\"reload\",\"message\":\"WebView yeniden yukleniyor\"}");
                    break;

                case "/durum":
                case "/status":
                    String statusJson = listener != null ? listener.onGetStatus() : "{\"status\":\"running\"}";
                    sendResponse(out, 200, "application/json; charset=utf-8", statusJson);
                    break;

                case "/":
                    String welcome = "{\"app\":\"Cinarli Kiosk\",\"version\":\"1.0.0\",\"endpoints\":[\"/kapat\",\"/ac\",\"/yenile\",\"/durum\"]}";
                    sendResponse(out, 200, "application/json; charset=utf-8", welcome);
                    break;

                default:
                    sendResponse(out, 404, "application/json; charset=utf-8", "{\"error\":\"Not Found\",\"path\":\"" + path + "\"}");
                    break;
            }

        } catch (Exception e) {
            Log.e(TAG, "Client istegi islenirken hata: " + e.getMessage(), e);
        }
    }

    private void sendResponse(OutputStream out, int statusCode, String contentType, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String statusText = (statusCode == 200) ? "OK" : (statusCode == 404 ? "Not Found" : "Internal Error");

        String header = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n";

        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }
}
