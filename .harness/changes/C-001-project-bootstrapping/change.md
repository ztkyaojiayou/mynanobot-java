---
id: C-001
slug: project-bootstrapping
status: done
created: 2025-06-07
owner: Owner Agent
---

# C-001 项目脚手架搭建

## 用户故事

作为开发者，我想要一个基于 Java 17 + Maven + Spring Boot 的 AI Agent 项目骨架，支持三种运行模式（独立/Web/CLI），以便后续功能模块可以在此基础上快速开发。

## 验收标准

- AC-1: `mvn compile` 通过，Spring Boot 3.2 启动成功
- AC-2: V1 (Nanobot.java) 独立模式可启动，ChannelServer 接收 HTTP 请求
- AC-3: V2 (NanobotApplication.java) Spring Boot 模式可启动，内嵌 Tomcat 监听 8080
- AC-4: V3 (NanobotCliApplication.java) CLI 模式可启动，支持 `--workspace` 参数
- AC-5: pom.xml 包含 Jackson、SLF4J+Logback、JUnit5、Lombok、Jsoup、Spring Boot Web/WS
- AC-6: 配置系统 (Config.java + ConfigLoader) 支持 YAML + 环境变量覆盖

## 边界情况

- 当 JDK 版本 < 17 时，编译报错并给出明确提示
- 当配置文件不存在时，使用合理默认值（非崩溃）
- 当 V3 CLI 不传 `--workspace` 时，默认取当前目录

## 非功能需求

| 维度 | 指标 |
|------|------|
| 编译 | Java 17 target，无 preview 特性 |
| 启动 | Spring Boot 启动 < 5s |
| 日志 | SLF4J + Logback，UTF-8 编码输出 |
