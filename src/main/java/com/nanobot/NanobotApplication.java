package com.nanobot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Nanobot Spring Boot 启动类
 * ============================
 * 
 * 这是 Spring Boot 版本的入口点，整合了：
 * - Spring MVC (REST API)
 * - Spring WebSocket (标准 WebSocket)
 * - 原有 Nanobot 核心组件
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.nanobot")
public class NanobotApplication {
    
    private static final Logger logger = LoggerFactory.getLogger(NanobotApplication.class);
    
    public static void main(String[] args) {
        logger.info("Starting Nanobot Spring Boot Application...");
        SpringApplication.run(NanobotApplication.class, args);
    }
}
