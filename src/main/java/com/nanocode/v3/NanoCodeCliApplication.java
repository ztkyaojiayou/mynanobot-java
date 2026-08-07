package com.nanocode.v3;

import com.nanocode.v3.cli.CliChannel;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * NanoCode CLI 启动类（V3 — 命令行交互，类 Claude Code 体验）。
 *
 * 启动: java -cp nanocode.jar com.nanocode.v3.NanoCodeCliApplication [--workspace /path]
 * 不指定 --workspace 时自动取当前目录。
 */
@SpringBootApplication(scanBasePackages = "com.nanocode")
public class NanoCodeCliApplication {

    private static String resumeSessionId = null;

    public static void main(String[] args) {
        // --resume <sessionId>: 恢复指定会话
        for (int i = 0; i < args.length; i++) {
            if ("--resume".equals(args[i]) && i + 1 < args.length) {
                resumeSessionId = args[i + 1];
            }
        }

        // 没有 --workspace / -w 时，自动取当前目录
        boolean hasWorkspace = false;
        for (String a : args) {
            if ("--workspace".equals(a) || "-w".equals(a)
                    || a.startsWith("--agents.defaults.workspace=")) {
                hasWorkspace = true; break;
            }
        }

        java.util.List<String> merged = new java.util.ArrayList<>();
        if (!hasWorkspace) {
            // 用系统属性传递原始工作目录（ConfigLoader 不认 Spring Boot CLI 参数）
            // 双写新旧键，兼容旧版读取逻辑
            com.nanocode.config.NanoCodeEnv.setPropertyBoth(
                    "nanocode.workspace", "nanobot.workspace", System.getProperty("user.dir"));
        }

        merged.add("--logging.config=classpath:logback-cli.xml");
        merged.add("--spring.main.banner-mode=off");
        merged.add("--spring.profiles.active=cli");
        merged.add("--spring.main.web-application-type=none"); // CLI 无需 Web 服务器

        // --workspace / -w → 系统属性（优先级最高，ConfigLoader 读取）
        for (int i = 0; i < args.length; i++) {
            if (("--workspace".equals(args[i]) || "-w".equals(args[i])) && i + 1 < args.length) {
                com.nanocode.config.NanoCodeEnv.setPropertyBoth(
                        "nanocode.workspace", "nanobot.workspace", args[++i]);
            } else {
                merged.add(args[i]);
            }
        }

        SpringApplication.run(NanoCodeCliApplication.class, merged.toArray(new String[0]));
    }

    @Bean
    @org.springframework.context.annotation.Profile("cli")
    public ApplicationRunner startCli(ConfigurableApplicationContext ctx) {
        return args -> new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            new CliChannel(ctx, resumeSessionId).start();
        }, "CLI-Main").start();
    }
}
