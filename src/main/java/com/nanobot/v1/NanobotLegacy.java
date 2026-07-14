package com.nanobot.v1;

/**
 * Nanobot Legacy 启动入口
 * ============================
 * 
 * 这是原有的 JDK HttpServer 版本入口点，已标记为 deprecated。
 * 
 * **推荐使用 Spring Boot 版本**：
 * - 更稳定的 WebSocket 实现
 * - 更好的生态支持
 * - 更容易集成安全框架
 * 
 * @deprecated 使用 {@link NanobotApplication} 代替
 */
@Deprecated
public class NanobotLegacy {
    
    /**
     * Legacy 启动入口
     * 
     * @deprecated 使用 Spring Boot 方式启动
     */
    @Deprecated
    public static void main(String[] args) {
        System.out.println("""
            
            ⚠️  WARNING: You are using the deprecated JDK HttpServer version.
            
            This version is deprecated and will be removed in future releases.
            
            Please use the Spring Boot version instead:
              java -jar nanobot-java.jar
              
            Or use Maven:
              mvn spring-boot:run
            """);
        
        // 直接使用 Nanobot 主类
        Nanobot.main(args);
    }
}
