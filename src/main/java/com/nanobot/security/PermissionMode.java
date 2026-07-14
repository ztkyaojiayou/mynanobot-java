package com.nanobot.security;

import com.nanobot.tools.Tool;

/**
 * 权限模式 — 定义 Agent 工具调用的整体安全姿态
 * ===============================================
 *
 * 参考 Claude Code 的 6 种权限模式，简化为 4 种。
 * 模式从严格到宽松排列：
 *
 * {@code PLAN < DEFAULT < ACCEPT_EDITS < BYPASS}
 *
 * 使用示例：
 * ```java
 * PermissionMode mode = PermissionMode.PLAN;
 * if (mode.allowsTool(tool)) { ... }
 * ```
 */
public enum PermissionMode {

    /**
     * 只读模式 — 拒绝所有写工具和执行工具。
     * 适合代码审查、探索未知代码库。
     */
    PLAN {
        @Override
        public boolean allowsTool(Tool tool) {
            return tool.isReadOnly();
        }
    },

    /**
     * 默认模式 — 读操作自动放行，写/执行操作需交互确认。
     * 本项目后续通过 InteractivePermissionHandler 实现确认。
     * 当前阶段：读放行，写拒绝（等同于 PLAN 行为，待交互组件就绪后升级）。
     */
    DEFAULT {
        @Override
        public boolean allowsTool(Tool tool) {
            // 读工具自动放行，写工具后续通过交互确认
            // 当前：无交互处理器时，读放行/写拒绝
            return tool.isReadOnly();
        }
    },

    /**
     * 接受编辑模式 — 读+文件编辑自动放行，Shell/网络需确认。
     * 适合信任的编码会话。
     */
    ACCEPT_EDITS {
        @Override
        public boolean allowsTool(Tool tool) {
            if (tool.isReadOnly()) return true;
            String name = tool.getName();
            // 文件编辑工具自动放行
            return "write_file".equals(name) || "edit_file".equals(name);
        }
    },

    /**
     * 绕过模式 — 全部放行（守卫仍然执行）。
     * 仅适用于完全信任的自动化工作流。
     */
    BYPASS {
        @Override
        public boolean allowsTool(Tool tool) {
            return true;
        }
    };

    /**
     * 判断当前模式下是否允许执行该工具
     *
     * @param tool 要检查的工具
     * @return true 表示允许执行
     */
    public abstract boolean allowsTool(Tool tool);

    /**
     * 从字符串解析模式名称（不区分大小写）
     *
     * @param name 模式名称
     * @return 对应的 PermissionMode
     * @throws IllegalArgumentException 如果名称无效
     */
    public static PermissionMode fromString(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT;
        }
        return switch (name.toLowerCase()) {
            case "plan" -> PLAN;
            case "default" -> DEFAULT;
            case "accept_edits", "acceptedits", "accept-edits" -> ACCEPT_EDITS;
            case "bypass" -> BYPASS;
            default -> throw new IllegalArgumentException(
                    "Unknown permission mode: " + name
                    + ". Valid values: plan, default, accept_edits, bypass");
        };
    }
}
