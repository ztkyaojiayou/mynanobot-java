package com.nanobot.config;

import jakarta.annotation.PostConstruct;
import jakarta.websocket.server.ServerEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket 配置类
 * 
 * 由于 NanobotWebSocketEndpoint 使用 @ServerEndpoint (Jakarta WebSocket API)，
 * 不需要在此处注册。但为了保持 Spring Boot 的兼容性，
 * 我们仍然启用 WebSocket 配置。
 * 
 * @ServerEndpoint 由容器自动注册，不需要显式配置
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    
    private static final Logger logger = LoggerFactory.getLogger(WebSocketConfig.class);
    
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // @ServerEndpoint 由容器自动注册
        // 这里不做额外配置
        logger.info("WebSocket handlers registered");
    }
}
