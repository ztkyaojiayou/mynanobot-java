package com.nanobot.v2;

import com.nanobot.bus.MessageBus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MessageBus Spring 配置
 * 
 * 将 MessageBus 注册为 Spring Bean，供 Controller 和 WebSocket 使用
 */
@Configuration
public class MessageBusConfig {
    
    @Bean
    public MessageBus messageBus() {
        return new MessageBus();
    }
}
