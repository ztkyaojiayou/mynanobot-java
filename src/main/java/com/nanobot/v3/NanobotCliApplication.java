package com.nanobot.v3;

import com.nanobot.v3.cli.CliChannel;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Nanobot CLI 启动类（V3 — 命令行交互，类 Claude Code 体验）。
 *
 * 启动: java -cp nanobot.jar com.nanobot.v3.NanobotCliApplication [--workspace /path]
 * 不指定 --workspace 时自动取当前目录。
 */
@SpringBootApplication(scanBasePackages = "com.nanobot")
public class NanobotCliApplication {

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
            merged.add("--agents.defaults.workspace=" + System.getProperty("user.dir"));
        }
        merged.add("--logging.config=classpath:logback-cli.xml");
        merged.add("--spring.main.banner-mode=off");
        merged.add("--spring.profiles.active=cli");
        merged.add("--spring.main.web-application-type=none"); // CLI 无需 Web 服务器

        // --workspace / -w → Spring Boot 属性格式
        for (int i = 0; i < args.length; i++) {
            if (("--workspace".equals(args[i]) || "-w".equals(args[i])) && i + 1 < args.length) {
                merged.add("--agents.defaults.workspace=" + args[++i]);
            } else {
                merged.add(args[i]);
            }
        }

        SpringApplication.run(NanobotCliApplication.class, merged.toArray(new String[0]));
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
