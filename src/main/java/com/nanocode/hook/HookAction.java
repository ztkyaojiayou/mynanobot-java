package com.nanocode.hook;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Hook 动作 — 定义匹配后"做什么".
 *
 * <h2>字段说明</h2>
 * 三个字段对应三种 {@link ActionType}，同一时间只有一个有效：
 * <ul>
 *   <li>{@code type} 为 {@link ActionType#COMMAND} → 执行 {@code command}（shell 命令）</li>
 *   <li>{@code type} 为 {@link ActionType#PROMPT} → 注入 {@code message} 到 LLM 上下文</li>
 *   <li>{@code type} 为 {@link ActionType#SCRIPT} → 执行 {@code scriptPath} 指定的脚本文件</li>
 * </ul>
 *
 * <h2>YAML 配置示例</h2>
 * <pre>
 * # COMMAND 类型：执行 shell
 * action:
 *   type: COMMAND
 *   command: "echo 'blocked: ${NANOBOT_TOOL}'"
 *
 * # PROMPT 类型：注入上下文
 * action:
 *   type: PROMPT
 *   message: "[工作区上下文] 当前分支: main"
 *
 * # SCRIPT 类型：执行脚本文件
 * action:
 *   type: SCRIPT
 *   script_path: "${NANOBOT_DIR}/hooks/audit.sh"
 * </pre>
 */
public record HookAction(
        ActionType type,

        @JsonProperty("command")
        String command,

        @JsonProperty("message")
        String message,

        @JsonProperty("script_path")
        String scriptPath
) {}
