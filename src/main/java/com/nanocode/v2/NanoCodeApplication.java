package com.nanocode.v2;

import com.nanocode.config.Config;
import com.nanocode.rules.RuleManager;
import com.nanocode.session.SessionManager;
import com.nanocode.skill.SkillManager;
import com.nanocode.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * NanoCode Spring Boot 启动类（V2 — HTTP/SSE + WebSocket）。
 */
@SpringBootApplication(scanBasePackages = "com.nanocode")
public class NanoCodeApplication {

    private static final Logger logger = LoggerFactory.getLogger(NanoCodeApplication.class);

    public static void main(String[] args) {
        logger.info("Starting NanoCode Spring Boot Application...");
        SpringApplication.run(NanoCodeApplication.class, args);
    }
    
    /**
     * V2 启动 banner — 仅在 V2 模式下显示（CLI 模式跳过）。
     * 因为 CLI 的 NanoCodeCliApplication 会扫描到 V2 的 @Configuration，
     * 需用 Profile 隔离。
     */
    @Bean
    @org.springframework.context.annotation.Profile("!cli")
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
                ║                             nanocode v1.0.0                                   ║
                ║              A lightweight AI Agent Framework for Java                        ║
                ║                                                                              ║
                ║    Features:  • Agent Loop    • Memory Management    • Tool System           ║
                ║               • Multi-Channel  • MCP Support         • Web Search            ║
                ║                                                                              ║
                ║                         🌟 启动成功！🌟                                      ║
                ║                                                                              ║
                ║    服务 ID:    nanocode                                                   ║
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
