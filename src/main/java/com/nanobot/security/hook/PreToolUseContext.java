package com.nanobot.security.hook;

import com.nanobot.tools.Tool;
import lombok.Getter;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * PreToolUse 钩子上下文 — 携带工具调用信息
 * ===========================================
 *
 * 不可变的上下文快照，传递给 PreToolUse 钩子。
 * 包含工具实例、参数、会话标识等，供钩子决策。
 */
@Getter
public class PreToolUseContext {

    private final String toolName;
    private final String toolDescription;
    private final boolean isReadOnly;
    private final String toolCategory;
    private final Map<String, Object> params;
    private final String sessionId;
    private final Instant timestamp;

    private PreToolUseContext(Builder builder) {
        this.toolName = builder.toolName;
        this.toolDescription = builder.toolDescription;
        this.isReadOnly = builder.isReadOnly;
        this.toolCategory = builder.toolCategory;
        this.params = Collections.unmodifiableMap(builder.params);
        this.sessionId = builder.sessionId;
        this.timestamp = Instant.now();
    }

    // ==================== Builder ====================

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String toolName;
        private String toolDescription;
        private boolean isReadOnly;
        private String toolCategory = "other";
        private Map<String, Object> params = Map.of();
        private String sessionId;

        public Builder toolName(String v)       { this.toolName = v;        return this; }
        public Builder toolDescription(String v){ this.toolDescription = v;  return this; }
        public Builder isReadOnly(boolean v)    { this.isReadOnly = v;      return this; }
        public Builder toolCategory(String v)   { this.toolCategory = v;    return this; }
        public Builder params(Map<String, Object> v) { this.params = v;     return this; }
        public Builder sessionId(String v)      { this.sessionId = v;       return this; }

        /** 从 Tool 实例自动填充上下文字段 */
        public Builder fromTool(Tool tool, Map<String, Object> params) {
            this.toolName = tool.getName();
            this.toolDescription = tool.getDescription();
            this.isReadOnly = tool.isReadOnly();
            this.params = params;
            return this;
        }

        public PreToolUseContext build() {
            return new PreToolUseContext(this);
        }
    }
}
