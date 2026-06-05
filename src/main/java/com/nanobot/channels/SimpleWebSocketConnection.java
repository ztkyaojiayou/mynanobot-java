package com.nanobot.channels;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 简化的 WebSocket 连接处理
 * 使用简单的文本行协议，避免复杂的帧解析
 */
public class SimpleWebSocketConnection {
    
    private final String connectionId;
    private final OutputStream outputStream;
    private final BufferedReader reader;
    private volatile boolean closed = false;
    private final MessageHandler messageHandler;
    
    public interface MessageHandler {
        void onMessage(String message);
        void onClose();
    }
    
    public SimpleWebSocketConnection(OutputStream outputStream, java.io.InputStream inputStream, 
                                     String connectionId, MessageHandler handler) {
        this.outputStream = outputStream;
        this.reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        this.connectionId = connectionId;
        this.messageHandler = handler;
        
        // 启动读取线程
        startReading();
    }
    
    private void startReading() {
        new Thread(() -> {
            try {
                String line;
                while (!closed && (line = reader.readLine()) != null) {
                    messageHandler.onMessage(line);
                }
            } catch (IOException e) {
                if (!closed) {
                    System.out.println("WebSocket read error for " + connectionId + ": " + e.getMessage());
                }
            } finally {
                close();
                messageHandler.onClose();
            }
        }).start();
    }
    
    public void send(String message) {
        if (closed) return;
        try {
            outputStream.write((message + "\n").getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch (IOException e) {
            close();
        }
    }
    
    public void close() {
        if (closed) return;
        closed = true;
        try {
            reader.close();
        } catch (IOException ignored) {}
        try {
            outputStream.close();
        } catch (IOException ignored) {}
    }
    
    public String getConnectionId() {
        return connectionId;
    }
    
    public boolean isClosed() {
        return closed;
    }
}