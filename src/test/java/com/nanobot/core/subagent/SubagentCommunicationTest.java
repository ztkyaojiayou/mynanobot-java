package com.nanobot.core.subagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SubagentCommunication 测试类
 */
class SubagentCommunicationTest {
    
    private SubagentCommunication comm;
    
    @BeforeEach
    void setUp() {
        comm = new SubagentCommunication();
    }
    
    @Test
    void testSendAndReceiveMessage() {
        // 发送消息
        SubagentCommunication.SubagentMessage message = SubagentCommunication.SubagentMessage.of("test", "hello");
        comm.send("sender", "receiver", message);
        
        // 接收消息
        Optional<SubagentCommunication.SubagentMessage> received = comm.receive("receiver");
        
        assertTrue(received.isPresent());
        assertEquals("test", received.get().getType());
        assertEquals("hello", received.get().getPayload());
        assertEquals("sender", received.get().getFromId());
        assertEquals("receiver", received.get().getToId());
    }
    
    @Test
    void testBroadcastMessage() {
        // 注册父子关系
        comm.registerParentChild("parent", "child1");
        comm.registerParentChild("parent", "child2");
        
        // 广播消息
        SubagentCommunication.SubagentMessage message = SubagentCommunication.SubagentMessage.of("broadcast", "hello all");
        comm.broadcast("parent", message);
        
        // 检查所有子节点都收到消息
        assertTrue(comm.hasMessages("child1"));
        assertTrue(comm.hasMessages("child2"));
        
        Optional<SubagentCommunication.SubagentMessage> msg1 = comm.receive("child1");
        Optional<SubagentCommunication.SubagentMessage> msg2 = comm.receive("child2");
        
        assertTrue(msg1.isPresent());
        assertTrue(msg2.isPresent());
        assertTrue(msg1.get().isBroadcast());
        assertTrue(msg2.get().isBroadcast());
    }
    
    @Test
    void testSendToParent() {
        // 注册父子关系
        comm.registerParentChild("parent", "child");
        
        // 子节点发送消息给父节点
        SubagentCommunication.SubagentMessage message = SubagentCommunication.SubagentMessage.result("result");
        comm.sendToParent("child", message);
        
        // 父节点接收消息
        Optional<SubagentCommunication.SubagentMessage> received = comm.receive("parent");
        
        assertTrue(received.isPresent());
        assertEquals("result", received.get().getType());
        assertEquals("child", received.get().getFromId());
        assertEquals("parent", received.get().getToId());
    }
    
    @Test
    void testSharedState() {
        // 设置共享状态
        comm.setSharedState("config.apiKey", "test-key");
        comm.setSharedState("config.timeout", 30000);
        
        // 获取共享状态
        assertEquals("test-key", comm.getSharedState("config.apiKey"));
        assertEquals(30000, comm.getSharedState("config.timeout"));
        
        // 检查状态存在
        assertTrue(comm.hasSharedState("config.apiKey"));
        assertFalse(comm.hasSharedState("nonexistent"));
        
        // 删除共享状态
        comm.removeSharedState("config.apiKey");
        assertFalse(comm.hasSharedState("config.apiKey"));
    }
    
    @Test
    void testParentChildRelationship() {
        // 注册父子关系
        comm.registerParentChild("parent", "child1");
        comm.registerParentChild("parent", "child2");
        
        // 检查父子关系
        assertEquals("parent", comm.getParentId("child1").orElse(null));
        assertEquals("parent", comm.getParentId("child2").orElse(null));
        assertFalse(comm.getParentId("nonexistent").isPresent());
        
        // 获取所有子节点
        List<String> children = comm.getChildIds("parent");
        assertEquals(2, children.size());
        assertTrue(children.contains("child1"));
        assertTrue(children.contains("child2"));
        
        // 解除父子关系
        comm.unregisterChild("child1");
        assertFalse(comm.getParentId("child1").isPresent());
    }
    
    @Test
    void testMessageReply() {
        // 发送消息并设置回复回调
        SubagentCommunication.SubagentMessage message = SubagentCommunication.SubagentMessage.of("request", "hello");
        
        final String[] replyContent = {null};
        comm.send("client", "server", message, reply -> {
            replyContent[0] = (String) reply.getPayload();
        });
        
        // 服务器接收并回复
        Optional<SubagentCommunication.SubagentMessage> received = comm.receive("server");
        assertTrue(received.isPresent());
        
        // 发送回复
        received.get().reply(comm, "reply content");
        
        // 客户端接收回复
        Optional<SubagentCommunication.SubagentMessage> reply = comm.receive("client");
        assertTrue(reply.isPresent());
        assertEquals("reply", reply.get().getType());
        assertEquals("reply content", reply.get().getPayload());
    }
    
    @Test
    void testStats() {
        // 执行一些操作
        comm.registerParentChild("parent", "child");
        comm.setSharedState("test", "value");
        comm.send("a", "b", SubagentCommunication.SubagentMessage.of("test", "hello"));
        
        SubagentCommunication.CommunicationStats stats = comm.getStats();
        
        assertEquals(1, stats.getParentChildCount());
        assertEquals(1, stats.getSharedStateCount());
        assertTrue(stats.getMessageCount() >= 1);
    }
}