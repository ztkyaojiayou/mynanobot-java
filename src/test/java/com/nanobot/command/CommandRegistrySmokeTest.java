package com.nanobot.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.nanobot.bus.MessageBus;
import com.nanobot.command.impl.CostCommand;
import com.nanobot.command.impl.HelpCommand;
import com.nanobot.command.impl.HistoryCommand;
import com.nanobot.command.impl.InitCommand;
import com.nanobot.command.impl.ModeCommand;
import com.nanobot.command.impl.ResumeCommand;
import com.nanobot.config.Config;
import com.nanobot.core.AgentLoop;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;
import com.nanobot.security.PermissionManager;
import com.nanobot.security.PermissionMode;
import com.nanobot.session.SessionManager;
import com.nanobot.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 命令系统统一重构冒烟测试。
 * 验证：buildBase 注册完整性、execute 三态语义（empty/present/exit）、
 * /clear 使用完整 sessionKey（修复 CLI 裸 sessionId 清错 key 的 bug）。
 */
class CommandRegistrySmokeTest {

    private SessionManager sm;
    private AgentLoop agentLoop;
    private CommandRegistry registry;
    private CommandContext cliCtx;
    /** /help 测试的输出缓冲区（buildCliRegistry 填充） */
    private ByteArrayOutputStream cmdOut;

    @BeforeEach
    void setup() throws Exception {
        Path temp = Files.createTempDirectory("nanobot-cmd-test");
        sm = new SessionManager(temp.toString());
        Config config = new Config(null, null, null, null, null);
        MessageBus bus = new MessageBus();
        ToolRegistry toolRegistry = new ToolRegistry();
        agentLoop = new AgentLoop(bus, stubProvider(), toolRegistry, sm, config);
        registry = CommandRegistry.buildBase();
        cliCtx = new CommandContext(toolRegistry, null, agentLoop,
                "sessionX", "cli:sessionX", "cli", System.out, null);
    }

    private LLMProvider stubProvider() {
        return new LLMProvider() {
            @Override public String getName() { return "stub"; }
            @Override public String getDefaultModel() { return "stub-model"; }
            @Override public int getMaxTokens() { return 4096; }
            @Override public boolean supportsTools() { return false; }
            @Override public boolean supportsStreaming() { return false; }
            @Override public CompletableFuture<LLMResponse> chat(
                    List<LLMProvider.Message> messages, List<JsonNode> tools) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException("stub"));
            }
            @Override public CompletableFuture<LLMResponse> chatStream(
                    List<LLMProvider.Message> messages, List<JsonNode> tools, Consumer<String> onDelta) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException("stub"));
            }
        };
    }

    @Test
    void buildBaseRegistersAllBuiltinCommands() {
        assertTrue(registry.isRegistered("clear"));
        assertTrue(registry.isRegistered("exit"));
        assertTrue(registry.isRegistered("compact"));
        assertTrue(registry.isRegistered("remember"));
        assertTrue(registry.isRegistered("skills"));
        assertTrue(registry.isRegistered("rules"));
        assertTrue(registry.isRegistered("stats"));
        assertTrue(registry.isRegistered("stop"));
        assertTrue(registry.isRegistered("q"), "exit 别名 q 应注册");
        assertTrue(registry.isRegistered("quit"), "exit 别名 quit 应注册");
    }

    @Test
    void unknownCommandReturnsEmpty() {
        assertTrue(registry.execute(cliCtx, "/nonexistent").isEmpty(),
                "未注册命令应返回 empty，供调用方 fallback 到技能");
    }

    @Test
    void registeredCommandReturnsPresentAndExitSignalsTrue() {
        var skills = registry.execute(cliCtx, "/skills");
        assertTrue(skills.isPresent(), "/skills 应被识别");
        assertFalse(skills.get(), "/skills 不应触发退出");

        var exit = registry.execute(cliCtx, "/exit");
        assertTrue(exit.isPresent(), "/exit 应被识别");
        assertTrue(exit.get(), "/exit 应返回退出信号 true");
    }

    @Test
    void clearUsesFullSessionKey() {
        // 预置历史到完整 key "cli:sessionX"（与 SessionStore 目录一致）
        sm.saveHistory("cli:sessionX", List.of(Map.of("role", "user", "content", "hi")));
        assertTrue(sm.loadHistory("cli:sessionX").isPresent(), "前置：历史应存在");

        var result = registry.execute(cliCtx, "/clear");
        assertTrue(result.isPresent());
        assertFalse(result.get());

        // 修复验证：清除的是 "cli:sessionX"，而非裸 sessionId
        assertTrue(sm.loadHistory("cli:sessionX").isEmpty(),
                "完整 sessionKey 的历史应被清空（旧 bug 用裸 sessionId 会清错 key）");
    }

    @Test
    void stopWithNoActiveTurnDoesNotCrash() {
        var result = registry.execute(cliCtx, "/stop");
        assertTrue(result.isPresent());
        assertFalse(result.get(), "无活动轮次时 /stop 应正常返回而非崩溃");
    }

    @Test
    void costEstimatesTokensAndCost() {
        // 静态估算：中文/英文混合约 2 字符/token
        assertEquals(2, CostCommand.estimateTokens("abcd"), "4 字符应估算 2 token");
        assertEquals(0, CostCommand.estimateTokens(""), "空内容估算 0 token");

        sm.saveHistory("cli:sessionX", List.of(
                Map.of("role", "user", "content", "你好世界，帮我写代码"),
                Map.of("role", "assistant", "content", "好的，以下是实现")
        ));

        var result = registry.execute(cliCtx, "/cost");
        assertTrue(result.isPresent(), "/cost 应被识别");
        assertFalse(result.get());

        // 成本计算应非负且有限
        var cost = CostCommand.estimateCost(
                List.of(Map.of("role", "user", "content", "你好"),
                        Map.of("role", "assistant", "content", "好的")),
                new double[]{2.0, 8.0});
        assertTrue(cost >= 0, "成本应为非负");
    }

    @Test
    void permissionsShowsStatusAndSwitchesMode() {
        PermissionManager pm = PermissionManager.builder().build();
        CommandContext permCtx = new CommandContext(
                new ToolRegistry(), pm, agentLoop, "sessionX", "cli:sessionX", "cli", System.out, null);

        // 查看状态
        var status = registry.execute(permCtx, "/permissions");
        assertTrue(status.isPresent(), "/permissions 应被识别");
        assertFalse(status.get());

        // 切换模式
        var switchResult = registry.execute(permCtx, "/permissions bypass");
        assertTrue(switchResult.isPresent());
        assertEquals(PermissionMode.BYPASS, pm.getMode(), "/permissions bypass 应切到 BYPASS");

        // 无效模式不崩溃
        var bad = registry.execute(permCtx, "/permissions nonsense");
        assertTrue(bad.isPresent());
        assertEquals(PermissionMode.BYPASS, pm.getMode(), "无效模式不应改变当前模式");
    }

    /** 构造 CLI 完整注册表（buildBase + 通道专属命令），输出捕获到 cmdOut */
    private CommandContext buildCliRegistry() {
        registry.register(new ModeCommand());
        registry.register(new HelpCommand(registry));
        registry.register(new InitCommand());
        registry.register(new ResumeCommand(s -> {}));
        registry.register(new HistoryCommand(new java.util.LinkedList<>()));
        cmdOut = new ByteArrayOutputStream();
        return new CommandContext(new ToolRegistry(), null, agentLoop,
                "sessionX", "cli:sessionX", "cli",
                new PrintStream(cmdOut, true, StandardCharsets.UTF_8), null);
    }

    @Test
    void helpShowsSingleCommandUsage() {
        CommandContext helpCtx = buildCliRegistry();

        var r = registry.execute(helpCtx, "/help mode");
        assertTrue(r.isPresent(), "/help mode 应被识别");
        assertFalse(r.get());

        String out = cmdOut.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("/mode"), "应显示命令名 /mode，实际:\n" + out);
        assertTrue(out.contains("用法"), "应显示 usage，实际:\n" + out);
        assertTrue(out.contains("plan"), "应显示别名 plan，实际:\n" + out);
    }

    @Test
    void helpResolvesAliasToCommand() {
        CommandContext helpCtx = buildCliRegistry();

        var r = registry.execute(helpCtx, "/help plan");
        assertTrue(r.isPresent(), "/help plan（别名）应被识别");

        String out = cmdOut.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("/mode"), "别名 plan 应解析到 /mode，实际:\n" + out);
    }

    @Test
    void helpUnknownCommandHints() {
        CommandContext helpCtx = buildCliRegistry();

        var r = registry.execute(helpCtx, "/help nonexistent");
        assertTrue(r.isPresent(), "/help <未知> 不应 fallback（已注册命令）");
        assertFalse(r.get());

        String out = cmdOut.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("未知命令"), "应提示未知命令，实际:\n" + out);
    }

    @Test
    void allRegisteredCommandsHaveUsage() {
        buildCliRegistry();

        assertFalse(registry.listUnique().isEmpty(), "应有注册命令");
        for (Command cmd : registry.listUnique()) {
            String usage = cmd.usage();
            assertNotNull(usage, "/" + cmd.name() + " 应有 usage()");
            assertFalse(usage.isBlank(), "/" + cmd.name() + " usage() 不应为空");
        }
    }
}
