package com.nanobot.hook;

import com.nanobot.config.Config;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hook 加载器 — 从多个来源加载 Hook 规则，产出给 {@link HookManager}.
 *
 * <h2>加载来源（合并模式）</h2>
 * config.yaml + .nanobot/hooks/ 目录的 Hook 会合并在一起.
 * 两者都为空时才回退到内置 BuiltinHooks.
 * <ol>
 *   <li><b>config.yaml</b> — {@code hooks.list} 显式配置的 Hook</li>
 *   <li><b>.nanobot/hooks/ 目录</b> — 扫描 {@code *.yaml / *.yml / *.json} 文件，
 *       每个文件定义一个或多个 Hook（项目级 + 用户级）</li>
 *   <li><b>内置 BuiltinHooks</b> — 以上两者都为空时的兜底默认</li>
 * </ol>
 *
 * <h2>文件系统扫描路径</h2>
 * 和 Rule / Skill 保持一致：
 * <ul>
 *   <li>项目级：{@code {workspace}/.nanobot/hooks/*.yaml}</li>
 *   <li>用户级：{@code ~/.nanobot/hooks/*.yaml}</li>
 * </ul>
 *
 * <h2>文件格式</h2>
 * 每个 YAML 文件中为一个 Hook 或 Hook 列表：
 * <pre>
 * # .nanobot/hooks/security.yaml
 * - id: "block-rm"
 *   event: PRE_TOOL_USE
 *   condition: "tool==bash"
 *   action:
 *     type: COMMAND
 *     command: "echo '[安全] 已拦截'"
 *   reject: true
 * </pre>
 *
 * <h2>职责边界</h2>
 * <b>HookLoader</b> 只管"从哪加载 Hook 定义" → 产出 {@code List<Hook>}.
 * <b>HookManager</b> 只管"何时执行 Hook" —— 注册 / 匹配 / 执行 / 统计.
 *
 * @see HookManager
 * @see BuiltinHooks
 */
public final class HookLoader {

    private static final Logger logger = LoggerFactory.getLogger(HookLoader.class);
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private HookLoader() { /* 纯静态工具 */ }

    /**
     * 主入口：从 Config + 文件系统 + 内置默认 合并加载 Hook.
     *
     * <h3>加载优先级</h3>
     * <ol>
     *   <li>{@code config.enabled == false} → 空列表</li>
     *   <li>config.yaml 有显式配置 → 使用 config.yaml 的 hook（不走文件系统/Builtin）</li>
     *   <li>config.yaml 无配置 → 扫描 .nanobot/hooks/ 目录</li>
     *   <li>目录也无 .yaml 文件 → 加载 BuiltinHooks 默认</li>
     * </ol>
     *
     * @param config 完整 Config（需要 workspace 路径）
     * @return 合并后的 Hook 列表
     */
    public static List<Hook> load(Config config) {
        Config.HooksConfig hooksConfig = config.getHooks();
        if (!hooksConfig.isEnabled()) {
            logger.info("HookLoader: hooks disabled in config");
            return List.of();
        }

        List<Hook> all = new ArrayList<>();

        // ── ① config.yaml hooks.list（显式配置）──
        List<Hook> configList = hooksConfig.getList();
        if (configList != null && !configList.isEmpty()) {
            all.addAll(configList);
            logger.info("HookLoader: {} hook(s) from config.yaml", configList.size());
        }

        // ── ② .nanobot/hooks/ 目录（文件系统）──
        List<Hook> fileHooks = loadFromNanobotDir(config);
        if (!fileHooks.isEmpty()) {
            all.addAll(fileHooks);
            logger.info("HookLoader: {} hook(s) from .nanobot/hooks/", fileHooks.size());
        }

        // ── 合并结果 ──
        if (!all.isEmpty()) {
            logHooks(all);
            return all;
        }

        // ── ③ 都为空 → 内置兜底 ──
        List<Hook> builtin = BuiltinHooks.defaults();
        logger.info("HookLoader: no hooks found, loading {} builtin hook(s)", builtin.size());
        logHooks(builtin);
        return new ArrayList<>(builtin);
    }

    // ═══════════ 文件系统加载 ═══════════

    /**
     * 扫描 .nanobot/hooks/ 目录（项目级 + 用户级）加载 Hook 文件.
     *
     * 每个 .yaml / .yml / .json 文件应包含一个 Hook 或 Hook 列表.
     * 解析失败的文件会输出警告但不影响其他文件.
     */
    static List<Hook> loadFromNanobotDir(Config config) {
        List<Hook> result = new ArrayList<>();

        // 项目级：{workspace}/.nanobot/hooks/
        Path projectDir = Paths.get(config.getNanobotDir(), "hooks");
        result.addAll(loadFromDirectory(projectDir));

        // 用户级：~/.nanobot/hooks/
        Path userDir = Paths.get(System.getProperty("user.home"), ".nanobot", "hooks");
        if (!userDir.equals(projectDir)) {
            result.addAll(loadFromDirectory(userDir));
        }

        return result;
    }

    /**
     * 从单个目录扫描并加载所有 Hook 文件.
     */
    private static List<Hook> loadFromDirectory(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();

        List<Hook> result = new ArrayList<>();
        try {
            List<Path> files = Files.list(dir)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
                    })
                    .sorted()
                    .toList();

            for (Path file : files) {
                try {
                    List<Hook> parsed = parseHookFile(file);
                    result.addAll(parsed);
                    logger.debug("  Parsed {} hook(s) from {}", parsed.size(), file.getFileName());
                } catch (IOException e) {
                    logger.warn("  Failed to parse hook file {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to scan hooks directory {}: {}", dir, e.getMessage());
        }

        return result;
    }

    /**
     * 解析单个 Hook 文件.
     * 支持两种格式：
     * <ul>
     *   <li>列表格式（常见）：{@code [{id:..., event:...}, ...]}</li>
     *   <li>单对象格式：{@code {id:..., event:...}}</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    static List<Hook> parseHookFile(Path file) throws IOException {
        String content = Files.readString(file);
        if (content.isBlank()) return List.of();

        // 尝试解析为列表
        try {
            return YAML_MAPPER.readValue(content,
                    YAML_MAPPER.getTypeFactory().constructCollectionType(List.class, Hook.class));
        } catch (IOException listError) {
            // 不是列表 → 尝试解析为单个对象
            try {
                Hook single = YAML_MAPPER.readValue(content, Hook.class);
                return List.of(single);
            } catch (IOException singleError) {
                throw new IOException("Not a valid Hook or Hook list: " + singleError.getMessage());
            }
        }
    }

    private static void logHooks(List<Hook> hooks) {
        for (Hook hook : hooks) {
            logger.info("  Hook [{}]: event={}, condition=\"{}\", action={}, reject={}",
                    hook.id(), hook.event(), hook.condition(), hook.action().type(), hook.reject());
        }
    }
}
