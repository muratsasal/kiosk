package com.cinarli.kiosk;

import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class KioskHttpServer {

    public interface CommandListener {
        void onScreenOffCommand();
        void onScreenOnCommand();
        void onReloadCommand();
        String onStatusRequest();
    }

    private final int port;
    private final CommandListener listener;
    private final Handler mainHandler;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    public KioskHttpServer(int port, CommandListener listener) {
        this.port = port;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(port);
                    while (running) {
                        final Socket clientSocket = serverSocket.accept();
                        handleClient(clientSocket);
                    }
                } catch (Exception ignored) {
                } finally {
                    running = false;
                }
            }
        }).start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
    }

    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
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
                        if (listener != null) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    listener.onScreenOffCommand();
                                }
                            });
                        }
                        responseJson = "{\"status\":\"ok\",\"action\":\"screen_off\"}";
                    } else if (path.startsWith("/ac")) {
                        if (listener != null) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    listener.onScreenOnCommand();
                                }
                            });
                        }
                        responseJson = "{\"status\":\"ok\",\"action\":\"screen_on\"}";
                    } else if (path.startsWith("/yenile")) {
                        if (listener != null) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    listener.onReloadCommand();
                                }
                            });
                        }
                        responseJson = "{\"status\":\"ok\",\"action\":\"reload\"}";
                    } else if (path.startsWith("/durum")) {
                        if (listener != null) {
                            responseJson = listener.onStatusRequest();
                        } else {
                            responseJson = "{\"status\":\"online\"}";
                        }
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
