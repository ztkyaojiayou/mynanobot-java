package com.nanobot.v1.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebSocketFrame 测试类
 */
class WebSocketFrameTest {
    
    private ByteArrayOutputStream outputStream;
    
    @BeforeEach
    void setUp() {
        outputStream = new ByteArrayOutputStream();
    }
    
    @Test
    void testCreateTextFrame() {
        WebSocketFrame frame = WebSocketFrame.text("Hello, World!");
        
        assertTrue(frame.isFin());
        assertEquals(WebSocketFrame.OPCODE_TEXT, frame.getOpcode());
        assertFalse(frame.isMasked());
        assertEquals("Hello, World!", frame.getText());
    }
    
    @Test
    void testCreateTextFragment() {
        WebSocketFrame frame = WebSocketFrame.textFragment("Hello", true);
        
        assertTrue(frame.isFin());
        assertEquals(WebSocketFrame.OPCODE_TEXT, frame.getOpcode());
        
        WebSocketFrame fragment = WebSocketFrame.textFragment("World", false);
        assertFalse(fragment.isFin());
    }
    
    @Test
    void testCreateCloseFrame() {
        WebSocketFrame frame = WebSocketFrame.close();
        
        assertTrue(frame.isFin());
        assertEquals(WebSocketFrame.OPCODE_CLOSE, frame.getOpcode());
        assertTrue(frame.isClose());
    }
    
    @Test
    void testCreatePingFrame() {
        WebSocketFrame frame = WebSocketFrame.ping();
        
        assertTrue(frame.isFin());
        assertEquals(WebSocketFrame.OPCODE_PING, frame.getOpcode());
        assertTrue(frame.isPing());
    }
    
    @Test
    void testCreatePongFrame() {
        WebSocketFrame frame = WebSocketFrame.pong();
        
        assertTrue(frame.isFin());
        assertEquals(WebSocketFrame.OPCODE_PONG, frame.getOpcode());
        assertTrue(frame.isPong());
    }
    
    @Test
    void testSendTextFrame() throws IOException {
        WebSocketFrame frame = WebSocketFrame.text("Test message");
        frame.send(outputStream);
        
        byte[] data = outputStream.toByteArray();
        
        // 检查第一个字节 (FIN + OPCODE)
        assertEquals(0x81, data[0]);  // FIN=1, OPCODE=1 (TEXT)
        
        // 检查第二个字节 (长度)
        assertEquals(12, data[1]);  // "Test message" 长度 = 12
        
        // 检查内容
        String content = new String(data, 2, 12, StandardCharsets.UTF_8);
        assertEquals("Test message", content);
    }
    
    @Test
    void testSendExtendedLengthFrame() throws IOException {
        // 创建超过 125 字节的消息
        String longMessage = "A".repeat(200);
        WebSocketFrame frame = WebSocketFrame.text(longMessage);
        frame.send(outputStream);
        
        byte[] data = outputStream.toByteArray();
        
        // 检查第一个字节
        assertEquals(0x81, data[0]);
        
        // 检查第二个字节 (126 表示扩展长度)
        assertEquals(126, data[1]);
        
        // 检查扩展长度 (2 字节)
        int length = (data[2] << 8) | data[3];
        assertEquals(200, length);
    }
    
    @Test
    void testParseTextFrame() throws IOException {
        // 构造一个简单的文本帧
        byte[] frameData = new byte[14];
        frameData[0] = (byte) 0x81;  // FIN=1, OPCODE=1 (TEXT)
        frameData[1] = (byte) 0x05;  // 长度=5
        System.arraycopy("Hello".getBytes(StandardCharsets.UTF_8), 0, frameData, 2, 5);
        
        ByteArrayInputStream inputStream = new ByteArrayInputStream(frameData);
        WebSocketFrame frame = WebSocketFrame.parse(inputStream);
        
        assertTrue(frame.isFin());
        assertEquals(WebSocketFrame.OPCODE_TEXT, frame.getOpcode());
        assertEquals("Hello", frame.getText());
        assertTrue(frame.isText());
    }
    
    @Test
    void testParseMaskedFrame() throws IOException {
        // 构造一个掩码的文本帧
        byte[] frameData = new byte[11];
        frameData[0] = (byte) 0x81;  // FIN=1, OPCODE=1
        frameData[1] = (byte) 0x85;  // MASKED=1, 长度=5
        
        // 掩码键
        frameData[2] = 0x01;
        frameData[3] = 0x02;
        frameData[4] = 0x03;
        frameData[5] = 0x04;
        
        // 掩码后的数据 (Hello -> 掩码后)
        byte[] original = "Hello".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < original.length; i++) {
            frameData[6 + i] = (byte) (original[i] ^ (0x01 + i));
        }
        
        ByteArrayInputStream inputStream = new ByteArrayInputStream(frameData);
        WebSocketFrame frame = WebSocketFrame.parse(inputStream);
        
        assertTrue(frame.isMasked());
        assertEquals("Hello", frame.getText());
    }
    
    @Test
    void testRoundTrip() throws IOException {
        String message = "Round trip test message!";
        
        // 发送
        WebSocketFrame original = WebSocketFrame.text(message);
        original.send(outputStream);
        
        // 解析
        ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
        WebSocketFrame parsed = WebSocketFrame.parse(inputStream);
        
        assertEquals(original.isFin(), parsed.isFin());
        assertEquals(original.getOpcode(), parsed.getOpcode());
        assertEquals(original.getText(), parsed.getText());
    }
    
    @Test
    void testIsTextMethod() {
        WebSocketFrame textFrame = WebSocketFrame.text("text");
        assertTrue(textFrame.isText());
        
        WebSocketFrame closeFrame = WebSocketFrame.close();
        assertFalse(closeFrame.isText());
        
        WebSocketFrame pingFrame = WebSocketFrame.ping();
        assertFalse(pingFrame.isText());
    }
}