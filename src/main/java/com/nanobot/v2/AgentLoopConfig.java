package com.nanobot.v2;

import com.nanobot.bus.MessageBus;
import com.nanobot.config.Config;
import com.nanobot.core.AgentLoop;
import com.nanobot.identity.IdentityManager;
import com.nanobot.memory.Consolidator;
import com.nanobot.memory.Dream;
import com.nanobot.providers.LLMProvider;
import com.nanobot.rules.RuleManager;
import com.nanobot.session.SessionManager;
import com.nanobot.skill.SkillManager;
import com.nanobot.tools.ToolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentLoop Spring 配置 — 整个系统的"心跳".
 *
 * <h2>为什么 AgentLoop 是整个项目的核心？</h2>
 * 它就是那个死循环——从 MessageBus 取消息、走 8 状态 State 模式引擎、
 * 调 LLM、执行工具、保存记忆、发布响应。所有用户请求最终都汇聚到这里处理.
 *
 * <h2>8 状态流转</h2>
 * <pre>
 *   RESTORE → COMPACT → COMMAND → BUILD → RUN → SAVE → RESPOND → DONE
 * </pre>
 *
 * <h2>依赖注入（按状态归属）</h2>
 * <ul>
 *   <li>RESTORE: SessionManager — 恢复对话历史</li>
 *   <li>COMPACT: Consolidator — 超过 token 预算时压缩旧消息</li>
 *   <li>COMMAND: SkillManager + RuleManager + SessionManager + Consolidator + Dream</li>
 *   <li>BUILD: IdentityManager + RuleManager + Dream + SkillRegistry — 拼装 System Prompt</li>
 *   <li>RUN: AgentRunner(LLMProvider + ToolRegistry) — 调 LLM + 工具调用循环</li>
 *   <li>SAVE: SessionManager + Dream — 持久化对话 + 异步提取长期记忆</li>
 *   <li>RESPOND: MessageBus — 发布最终响应</li>
 * </ul>
 *
 * <h2>设计决策</h2>
 * <ul>
 *   <li><b>单线程消费</b>：AgentLoop 内部是单 daemon 线程死循环消费，
 *       保证消息按到达顺序处理，天然线程安全.</li>
 *   <li><b>异步 Worker</b>：processMessage() 提交到 4 线程池异步执行，
 *       避免某个慢 LLM 调用阻塞后续消息.</li>
 *   <li><b>不在此处 start()</b>：Bean 创建只组装依赖，start() 由
 *       {@link com.nanobot.NanobotRunner#run} 在所有组件就绪后触发.</li>
 * </ul>
 *
 * <h2>与其他组件的关系</h2>
 * <ul>
 *   <li>{@link MessageBus} — 消息来源和去向</li>
 *   <li>{@link com.nanobot.v2.NanobotConfig} — 所有依赖 Bean 的定义</li>
 *   <li>{@link com.nanobot.NanobotRunner} — 启动入口</li>
 * </ul>
 *
 * @see com.nanobot.core.AgentLoop
 * @see MessageBusConfig
 */
@Configuration
public class AgentLoopConfig {

    /**
     * 全局唯一的 AgentLoop 实例.
     *
     * <h3>线程模型</h3>
     * <ul>
     *   <li><b>主循环线程</b>（AgentLoop）：单 daemon 线程，死循环 poll MessageBus.inboundQueue</li>
     *   <li><b>Worker 线程池</b>（AgentLoop-worker × 4）：异步处理每条消息的状态机流程</li>
     * </ul>
     *
     * Spring 容器中全局唯一（默认 singleton scope）.
     * {@code destroyMethod = "stop"} 确保 Spring 关闭时释放线程池 + 停止 MessageBus.
     */
    @Bean(destroyMethod = "stop")
    public AgentLoop agentLoop(
            MessageBus messageBus,
            LLMProvider llmProvider,
            ToolRegistry toolRegistry,
            SessionManager sessionManager,
            SkillManager skillManager,
            RuleManager ruleManager,
            IdentityManager identityManager,
            Consolidator consolidator,
            Dream dream,
            Config config) {
        AgentLoop agentLoop = new AgentLoop(
                messageBus,
                llmProvider,
                toolRegistry,
                sessionManager,
                config,
                ruleManager,
                skillManager,
                identityManager
        );
        agentLoop.setConsolidator(consolidator);
        agentLoop.setDream(dream);
        return agentLoop;
    }
}
