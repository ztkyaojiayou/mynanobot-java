package com.nanobot.v1.channel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * WebSocket 帧处理
 * =================
 * 
 * 实现 WebSocket 协议的帧解析和发送。
 * 
 * **帧格式**：
 * 
 * ```
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-------+-+-------------+-------------------------------+
 * |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 * |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 * |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 * | |1|2|3|       |K|             |                               |
 * +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 * |     Extended payload length continued, if payload len == 127  |
 * + - - - - - - - - - - - - - - - +-------------------------------+
 * |                               |Masking-key, if MASK set to 1  |
 * +-------------------------------+-------------------------------+
 * | Masking-key (continued)       |          Payload Data         |
 * +-------------------------------- - - - - - - - - - - - - - - - +
 * :                     Payload Data continued ...                :
 * + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
 * |                     Payload Data continued ...                |
 * +---------------------------------------------------------------+
 * ```
 * 
 * **操作码**：
 * - 0x0: 继续帧
 * - 0x1: 文本帧
 * - 0x2: 二进制帧
 * - 0x8: 关闭帧
 * - 0x9: Ping 帧
 * - 0xA: Pong 帧
 */
public class WebSocketFrame {
    
    /** 是否为最后一帧 */
    private final boolean fin;
    
    /** 操作码 */
    private final int opcode;
    
    /** 是否掩码 */
    private final boolean masked;
    
    /** 掩码键 */
    private final int maskingKey;
    
    /** 负载数据 */
    private final byte[] payload;
    
    // ==================== 操作码常量 ====================
    
    public static final int OPCODE_CONTINUATION = 0x0;
    public static final int OPCODE_TEXT = 0x1;
    public static final int OPCODE_BINARY = 0x2;
    public static final int OPCODE_CLOSE = 0x8;
    public static final int OPCODE_PING = 0x9;
    public static final int OPCODE_PONG = 0xA;
    
    // ==================== 构造函数 ====================
    
    public WebSocketFrame(boolean fin, int opcode, boolean masked, int maskingKey, byte[] payload) {
        this.fin = fin;
        this.opcode = opcode;
        this.masked = masked;
        this.maskingKey = maskingKey;
        this.payload = payload;
    }
    
    // ==================== 工厂方法 ====================
    
    /**
     * 创建文本帧
     */
    public static WebSocketFrame text(String text) {
        return new WebSocketFrame(
            true, 
            OPCODE_TEXT, 
            false, 
            0, 
            text.getBytes(StandardCharsets.UTF_8)
        );
    }
    
    /**
     * 创建文本帧（流式片段）
     */
    public static WebSocketFrame textFragment(String text, boolean isLast) {
        return new WebSocketFrame(
            isLast, 
            OPCODE_TEXT, 
            false, 
            0, 
            text.getBytes(StandardCharsets.UTF_8)
        );
    }
    
    /**
     * 创建关闭帧
     */
    public static WebSocketFrame close() {
        return new WebSocketFrame(true, OPCODE_CLOSE, false, 0, new byte[0]);
    }
    
    /**
     * 创建关闭帧（带状态码）
     */
    public static WebSocketFrame close(int statusCode, String reason) {
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) ((statusCode >> 8) & 0xFF);
        payload[1] = (byte) (statusCode & 0xFF);
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
        return new WebSocketFrame(true, OPCODE_CLOSE, false, 0, payload);
    }
    
    /**
     * 创建 Ping 帧
     */
    public static WebSocketFrame ping() {
        return new WebSocketFrame(true, OPCODE_PING, false, 0, new byte[0]);
    }
    
    /**
     * 创建 Pong 帧
     */
    public static WebSocketFrame pong() {
        return new WebSocketFrame(true, OPCODE_PONG, false, 0, new byte[0]);
    }
    
    // ==================== 解析方法 ====================
    
    /**
     * 从输入流解析帧
     */
    public static WebSocketFrame parse(InputStream in) throws IOException {
        // 读取第一个字节
        int firstByte = in.read();
        if (firstByte == -1) {
            throw new IOException("Connection closed");
        }
        
        boolean fin = (firstByte & 0x80) != 0;
        int opcode = firstByte & 0x0F;
        
        // 读取第二个字节
        int secondByte = in.read();
        if (secondByte == -1) {
            throw new IOException("Connection closed");
        }
        
        boolean masked = (secondByte & 0x80) != 0;
        int payloadLen = secondByte & 0x7F;
        
        // 读取扩展长度
        if (payloadLen == 126) {
            int b1 = in.read();
            int b2 = in.read();
            payloadLen = (b1 << 8) | b2;
        } else if (payloadLen == 127) {
            in.read(); in.read(); in.read(); in.read();
            int b1 = in.read();
            int b2 = in.read();
            int b3 = in.read();
            int b4 = in.read();
            payloadLen = (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
        }
        
        // 读取掩码键
        int maskingKey = 0;
        if (masked) {
            int b1 = in.read();
            int b2 = in.read();
            int b3 = in.read();
            int b4 = in.read();
            if (b1 == -1 || b2 == -1 || b3 == -1 || b4 == -1) {
                throw new IOException("Connection closed");
            }
            maskingKey = (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
        }
        
        // 读取负载
        byte[] payload = new byte[payloadLen];
        int read = 0;
        while (read < payloadLen) {
            int n = in.read(payload, read, payloadLen - read);
            if (n == -1) {
                throw new IOException("Connection closed");
            }
            read += n;
        }
        
        // 解掩码
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= (maskingKey >> (8 * (3 - (i % 4)))) & 0xFF;
            }
        }
        
        return new WebSocketFrame(fin, opcode, masked, maskingKey, payload);
    }
    
    // ==================== 发送方法 ====================
    
    /**
     * 发送帧到输出流
     */
    public void send(OutputStream out) throws IOException {
        // 第一个字节
        int firstByte = (fin ? 0x80 : 0) | opcode;
        out.write(firstByte);
        
        // 第二个字节 + 长度
        int payloadLen = payload.length;
        if (payloadLen <= 125) {
            out.write((masked ? 0x80 : 0) | payloadLen);
        } else if (payloadLen <= 65535) {
            out.write((masked ? 0x80 : 0) | 126);
            out.write((payloadLen >> 8) & 0xFF);
            out.write(payloadLen & 0xFF);
        } else {
            out.write((masked ? 0x80 : 0) | 127);
            // 8 字节长度（前 4 字节为 0）
            out.write(0); out.write(0); out.write(0); out.write(0);
            out.write((payloadLen >> 24) & 0xFF);
            out.write((payloadLen >> 16) & 0xFF);
            out.write((payloadLen >> 8) & 0xFF);
            out.write(payloadLen & 0xFF);
        }
        
        // 掩码键（服务器发送不需要掩码）
        if (masked) {
            out.write((maskingKey >> 24) & 0xFF);
            out.write((maskingKey >> 16) & 0xFF);
            out.write((maskingKey >> 8) & 0xFF);
            out.write(maskingKey & 0xFF);
        }
        
        // 负载
        out.write(payload);
        out.flush();
    }
    
    // ==================== Getter ====================
    
    public boolean isFin() { return fin; }
    public int getOpcode() { return opcode; }
    public boolean isMasked() { return masked; }
    public int getMaskingKey() { return maskingKey; }
    public byte[] getPayload() { return payload; }
    
    /**
     * 获取文本内容
     */
    public String getText() {
        return new String(payload, StandardCharsets.UTF_8);
    }
    
    /**
     * 是否为文本帧
     */
    public boolean isText() {
        return opcode == OPCODE_TEXT || opcode == OPCODE_CONTINUATION;
    }
    
    /**
     * 是否为关闭帧
     */
    public boolean isClose() {
        return opcode == OPCODE_CLOSE;
    }
    
    /**
     * 是否为 Ping 帧
     */
    public boolean isPing() {
        return opcode == OPCODE_PING;
    }
    
    /**
     * 是否为 Pong 帧
     */
    public boolean isPong() {
        return opcode == OPCODE_PONG;
    }
    
    @Override
    public String toString() {
        return "WebSocketFrame{" +
            "fin=" + fin +
            ", opcode=" + opcode +
            ", payloadLen=" + payload.length +
            '}';
    }
}