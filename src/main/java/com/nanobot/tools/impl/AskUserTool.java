package com.nanobot.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nanobot.tools.Tool;

import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

/**
 * AskUser 工具 — LLM 在关键决策点向用户提问。
 *
 * 典型场景：
 * - "端口用 8080 还是 9090？"
 * - "找到了 README.md 和 README.txt，用哪个？"
 * - "检测到拼写错误 'recieve'，帮你修复为 'receive' 吗？"
 *
 * CLI 模式: 通过 stdin 读取用户回答
 * HTTP/SSE 模式: 返回问题文本，由前端展示并收集回答（当前直接返回，待前端支持）
 */
public class AskUserTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** 交互处理器（CLI 注入 Scanner，HTTP 使用默认） */
    private volatile InteractiveHandler handler = null;

    /** CLI 模式的交互处理器 */
    @FunctionalInterface
    public interface InteractiveHandler {
        String ask(String question);
    }

    public AskUserTool() {}

    /** 注入 CLI 交互处理器 */
    public void setInteractiveHandler(InteractiveHandler handler) {
        this.handler = handler;
    }

    @Override
    public String getName() { return "ask_user"; }

    @Override
    public String getDescription() {
        return "Ask the user a question when you need clarification or confirmation. "
             + "Use this when you're unsure about something and the user's input would help. "
             + "Examples: port choice, file selection, confirmation before destructive actions.";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.putObject("question").put("type", "string")
                .put("description", "The question to ask the user");
        root.set("properties", props);
        root.set("required", mapper.createArrayNode().add("question"));
        return root;
    }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        String question = (String) params.get("question");
        if (question == null || question.isBlank())
            return CompletableFuture.completedFuture("Error: question is required");

        // CLI 模式：通过 handler 读取用户输入
        if (handler != null) {
            String answer = handler.ask(question);
            return CompletableFuture.completedFuture(
                    "[用户回答] " + (answer != null && !answer.isBlank() ? answer : "(未回答)"));
        }

        // HTTP/Web 模式：返回问题，等前端实现交互后补充
        return CompletableFuture.completedFuture(
                "[等待用户回答] " + question + "\n请回答以上问题。");
    }
}
