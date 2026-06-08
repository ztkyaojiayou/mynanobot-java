package com.nanobot;

import com.nanobot.config.Config;
import com.nanobot.rules.RuleManager;
import com.nanobot.skill.SkillManager;
import com.nanobot.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.net.UnknownHostException;

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
public class NanobotApplication {
    
    private static final Logger logger = LoggerFactory.getLogger(NanobotApplication.class);
    
    public static void main(String[] args) {
        logger.info("Starting Nanobot Spring Boot Application...");
        SpringApplication.run(NanobotApplication.class, args);
    }
    
    @Bean
    public ApplicationRunner printBannerOnStartup(
            Environment env, 
            ToolRegistry toolRegistry,
            SkillManager skillManager,
            RuleManager ruleManager,
            Config config) {
        return args -> {
            String serverPort = env.getProperty("server.port", "8080");
            String localUrl = "http://localhost:" + serverPort;
            
            int toolsCount = toolRegistry.size();
            int skillsCount = skillManager.getRegistry().size();
            int rulesCount = ruleManager.getRegistry().size();
            String model = config.getAgents().getDefaults().getModel();
            
            String banner = String.format("""
                ╔══════════════════════════════════════════════════════════════════════════════╗
                ║                                                                              ║
                ║    ███╗   ███╗ ██████╗  ███╗   ██╗   ██████╗  █████╗  ███╗   ██╗          ║
                ║    ████╗ ████║ ██╔══██╗ ████╗  ██║   ██╔══██╗██╔══██╗████╗  ██║          ║
                ║    ██╔████╔██║ ██████╔╝ ██╔██╗ ██║   ██████╔╝███████║██╔██╗ ██║          ║
                ║    ██║╚██╔╝██║ ██╔═══╝  ██║╚██╗██║   ██╔═══╝ ██╔══██║██║╚██╗██║          ║
                ║    ██║ ╚═╝ ██║ ██║       ██║ ╚████║   ██║     ██║  ██║██║ ╚████║          ║
                ║    ╚═╝     ╚═╝ ╚═╝       ╚═╝  ╚═══╝   ╚═╝     ╚═╝  ╚═╝╚═╝  ╚═══╝          ║
                ║                                                                              ║
                ║                          my-nanobot v1.0.0                                   ║
                ║              A lightweight AI Agent Framework for Java                        ║
                ║                                                                              ║
                ║    Features:  • Agent Loop    • Memory Management    • Tool System           ║
                ║               • Multi-Channel  • MCP Support         • Web Search            ║
                ║                                                                              ║
                ║                         🌟 启动成功！🌟                                      ║
                ║                                                                              ║
                ║    服务 ID:    nanobot-java                                                   ║
                ║    Local:      %s                                                            ║
                ║    Model:      %s                                                            ║
                ║    Components: • Tools: %d    • Skills: %d    • Rules: %d                       ║
                ║                                                                              ║
                ╚══════════════════════════════════════════════════════════════════════════════╝
                """, localUrl, model, toolsCount, skillsCount, rulesCount);
            
            System.out.println();
            System.out.println(banner);
            System.out.println();
        };
    }
}
