package com.nanobot.core.state;

import com.nanobot.bus.MessageBus;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.core.TurnContext;
import com.nanobot.core.TurnState;
import com.nanobot.memory.Consolidator;
import com.nanobot.memory.Dream;
import com.nanobot.rules.RuleManager;
import com.nanobot.session.SessionManager;
import com.nanobot.skill.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * COMMAND — 命令分发。
 *
 * <h2>匹配优先级</h2>
 * <ol>
 *   <li>内置命令（系统级，不能被技能覆盖）：/stop, /clear, /compact, /remember, /skills, /rules</li>
 *   <li>技能斜杠调用：/xxx → SkillRegistry 查找，返回 SKILL.md 全文</li>
 *   <li>都不匹配 → BUILD（当作普通消息进入 LLM 处理）</li>
 * </ol>
 */
public class CommandState implements AgentState {

    private static final Logger logger = LoggerFactory.getLogger(CommandState.class);
    private final SkillManager skillManager;
    private final RuleManager ruleManager;
    private final SessionManager sessionManager;
    private final Consolidator consolidator;
    private final Dream dream;
    private final MessageBus messageBus;

    public CommandState(SkillManager skillManager, RuleManager ruleManager,
                        SessionManager sessionManager, Consolidator consolidator,
                        Dream dream, MessageBus messageBus) {
        this.skillManager = skillManager;
        this.ruleManager = ruleManager;
        this.sessionManager = sessionManager;
        this.consolidator = consolidator;
        this.dream = dream;
        this.messageBus = messageBus;
    }

    @Override
    public TurnState execute(TurnContext ctx) {
        String content = ctx.getMessage().getContent();
        if (content == null || !content.startsWith("/")) return TurnState.BUILD;

        String command = content.split("\\s")[0].toLowerCase();

        // ── ① 内置命令优先（系统级操作不能被用户技能覆盖）──
        TurnState builtinResult = handleBuiltinCommand(command, ctx);
        if (builtinResult != null) return builtinResult;

        // ── ② 不是内置命令 → 尝试技能匹配 ──
        if (skillManager != null) {
            SkillManager.SkillCall skillCall = skillManager.parseSlashCommand(content);
            if (skillCall != null) {
                String result = skillManager.executeSkill(
                        skillCall.skillName(), java.util.Map.of(), skillCall.args());
                ctx.setFinalContent(result);
                return TurnState.DONE;
            }
        }

        // ── ③ 都不匹配 → 当作普通 / 开头消息，进入 LLM ──
        return TurnState.BUILD;
    }

    /**
     * 处理内置命令。
     *
     * @return 匹配成功返回对应的 TurnState，不匹配返回 null（让调用方继续查技能）
     */
    private TurnState handleBuiltinCommand(String command, TurnContext ctx) {
        return switch (command) {
            case "/stop" -> {
                ctx.cancel();
                ctx.setFinalContent("已停止处理。");
                yield TurnState.DONE;
            }
            case "/clear" -> {
                sessionManager.clearSession(ctx.getSessionKey());
                ctx.setFinalContent("会话已清除。");
                // 发布 _session_cleared 事件到 outboundQueue，通知各通道清空展示
                publishSessionCleared(ctx);
                yield TurnState.DONE;
            }
            case "/compact" -> handleCompact(ctx);
            case "/remember" -> handleRemember(ctx);
            case "/skills" -> {
                String help = skillManager != null
                        ? skillManager.getRegistry().getHelp()
                        : "技能系统未启用。";
                ctx.setFinalContent(help);
                yield TurnState.DONE;
            }
            case "/rules" -> {
                String summary = ruleManager != null
                        ? ruleManager.getRulesSummary()
                        : "规则系统未启用。";
                ctx.setFinalContent(summary);
                yield TurnState.DONE;
            }
            case "/stats" -> handleStats(ctx);
            default -> null;  // 不是内置命令 → 返回 null，让调用方继续
        };
    }

    /** /stats — 显示当前会话和全局统计 */
    private TurnState handleStats(TurnContext ctx) {
        StringBuilder sb = new StringBuilder("📊 会话统计\n\n");
        // 当前会话
        var msgs = ctx.getMessages();
        int msgCount = (int) msgs.stream().filter(m -> !"system".equals(m.get("role"))).count();
        int tokens = (int) (msgs.stream()
                .mapToInt(m -> m.getOrDefault("content", "").toString().length()).sum() / 4.0);
        sb.append("消息数: ").append(msgCount).append(" 条 · Token 估算: ").append(tokens).append("\n");

        // 迭代信息
        sb.append("LLM 迭代次: ").append(ctx.getIteration()).append("\n\n");

        // 全局
        sb.append("📊 全局\n\n");
        sb.append("会话总数: ").append(sessionManager.getSessionCount()).append(" 个\n");

        // 队列
        sb.append("入站队列: ").append(messageBus.getInboundSize())
                .append("/").append(100 - messageBus.getInboundRemainingCapacity() + 100).append("\n");
        sb.append("出站队列: ").append(messageBus.getOutboundQueueSize()).append("/1000\n");
        sb.append("订阅者数: ").append(messageBus.getSubscriberCount()).append("\n");

        // 记忆
        if (dream != null) {
            sb.append("长期记忆: ").append(dream.getMemoryCount()).append(" 条\n");
        }

        // 工具耗时
        var timings = com.nanobot.hook.impl.MetricsHook.getToolTimings();
        if (!timings.isEmpty()) {
            sb.append("\n📊 工具耗时\n\n");
            timings.values().stream()
                    .sorted((a, b) -> Long.compare(b.totalMs(), a.totalMs()))
                    .limit(10)
                    .forEach(t -> sb.append("  ").append(t).append("\n"));
        }

        // 全局指标
        var instance = com.nanobot.hook.impl.MetricsHook.getInstance();
        if (instance != null) {
            var global = instance.getGlobalMetrics();
            sb.append("\n📊 全局指标\n\n");
            sb.append("总请求: ").append(global.get("totalRequests")).append("\n");
            sb.append("总 Token: ").append(global.get("totalTokens")).append("\n");
            sb.append("运行时间: ").append(String.format("%.1f 分",
                    ((Number) global.get("uptimeMs")).longValue() / 60000.0)).append("\n");
            if (global.containsKey("avgDurationMs")) {
                sb.append("平均耗时: ").append(global.get("avgDurationMs")).append("ms\n");
                sb.append("错误率: ").append(global.get("errorRate")).append("\n");
            }
        }

        ctx.setFinalContent(sb.toString());
        return TurnState.DONE;
    }

    /** /compact — 手动触发对话历史压缩 */
    private TurnState handleCompact(TurnContext ctx) {
        if (consolidator == null) {
            ctx.setFinalContent("压缩器未启用。");
            return TurnState.DONE;
        }
        List<Map<String, Object>> messages = ctx.getMessages();
        int before = messages.size();
        int tokens = consolidator.getCurrentUsage(messages);

        try {
            List<Map<String, Object>> compacted = consolidator.consolidate(messages).join();
            ctx.getMessages().clear();
            ctx.getMessages().addAll(compacted);
            int saved = before - compacted.size();
            int newTokens = consolidator.getCurrentUsage(compacted);
            ctx.setFinalContent(String.format(
                    "✅ 上下文已压缩：%d 条消息 → %d 条（减少 %d 条），token 估算 %d → %d",
                    before, compacted.size(), saved, tokens, newTokens));
            logger.info("Manual compaction: {} → {} messages", before, compacted.size());
        } catch (Exception e) {
            logger.error("Manual compaction failed", e);
            ctx.setFinalContent("❌ 压缩失败：" + e.getMessage());
        }
        return TurnState.DONE;
    }

    /** /remember — 手动触发长期记忆提取 */
    private TurnState handleRemember(TurnContext ctx) {
        if (dream == null) {
            ctx.setFinalContent("长期记忆系统未启用。");
            return TurnState.DONE;
        }
        String sessionId = ctx.getSessionKey();
        List<Map<String, Object>> messages = ctx.getMessages();

        try {
            var stored = dream.extractAndStore(sessionId, messages).join();
            if (stored.isEmpty()) {
                ctx.setFinalContent("📝 没有提取到新的长期记忆（可能已存在或增量不足）。");
            } else {
                StringBuilder sb = new StringBuilder("✅ 已提取 " + stored.size() + " 条长期记忆：\n");
                for (var entry : stored) {
                    sb.append("- ").append(entry.getContent()).append("\n");
                }
                ctx.setFinalContent(sb.toString().trim());
                logger.info("Manual memory extraction: {} entries from session {}",
                        stored.size(), sessionId);
            }
        } catch (Exception e) {
            logger.error("Manual memory extraction failed", e);
            ctx.setFinalContent("❌ 记忆提取失败：" + e.getMessage());
        }
        return TurnState.DONE;
    }

    /** 发布 _session_cleared 事件到 outboundQueue，通知各通道清空展示 */
    private void publishSessionCleared(TurnContext ctx) {
        try {
            String requestId = extractRequestId(ctx);
            OutboundMessage msg = OutboundMessage.builder()
                    .sessionId(ctx.getSessionKey())
                    .requestId(requestId)
                    .channel(ctx.getMessage().getChannel())
                    .metadata(Map.of("_session_cleared", true))
                    .build();
            messageBus.publishToOutboundQueue(msg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String extractRequestId(TurnContext ctx) {
        if (ctx.getMessage().getMetadata() == null) return null;
        Object o = ctx.getMessage().getMetadata().get("requestId");
        return o instanceof String s ? s : null;
    }
}
