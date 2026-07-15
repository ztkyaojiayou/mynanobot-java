package com.nanobot.v3;

import com.nanobot.v3.cli.CliChannel;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Nanobot CLI 启动类（V3 — 命令行交互）。
 *
 * 启动: java -cp nanobot.jar com.nanobot.v3.NanobotCliApplication
 * CLI 模式下抑制所有 INFO/DEBUG 日志，控制台只显示对话内容。
 */
@SpringBootApplication(scanBasePackages = "com.nanobot")
public class NanobotCliApplication {

    public static void main(String[] args) {
        // 用命令行参数优先级覆盖 YAML 中的 DEBUG 设置
        String[] cliArgs = new String[]{
            "--logging.level.root=WARN",
            "--logging.level.com.nanobot=WARN",
            "--spring.main.banner-mode=off",
            "--spring.profiles.active=cli"};

        // 合并用户参数和 CLI 默认参数（用户参数优先）
        String[] merged = new String[cliArgs.length + args.length];
        System.arraycopy(cliArgs, 0, merged, 0, cliArgs.length);
        System.arraycopy(args, 0, merged, cliArgs.length, args.length);

        SpringApplication.run(NanobotCliApplication.class, merged);
    }

    @Bean
    @org.springframework.context.annotation.Profile("cli")
    public ApplicationRunner startCli(ConfigurableApplicationContext ctx) {
        return args -> new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            new CliChannel(ctx).start();
        }, "CLI-Main").start();
    }
}
