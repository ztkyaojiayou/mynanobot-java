package com.nanobot.providers;

import com.nanobot.config.Config;
import com.nanobot.providers.impl.DeepSeekProvider;
import com.nanobot.providers.impl.OpenAIProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM Provider 策略工厂 — 纯静态工具。
 *
 * 根据配置文件中的 model 字段匹配对应的 Provider 实现。
 * 新 Provider 只需注册一个 {@link ProviderStrategy} 即可，无需改其他代码。
 *
 * <pre>
 * LLMProvider provider = ProviderFactory.create(config);
 * </pre>
 */
public class ProviderFactory {

    private static final Logger logger = LoggerFactory.getLogger(ProviderFactory.class);

    /** 策略列表，按注册顺序匹配（先注册的优先） */
    private static final List<ProviderStrategy> strategies = new ArrayList<>();

    static {
        registerDefaults();
    }

    // ═══════════ 公开 API ═══════════

    /** 注册一个 Provider 策略（如需扩展，在应用启动早期调用） */
    public static void register(ProviderStrategy strategy) {
        strategies.add(strategy);
        logger.debug("Registered provider strategy: {}", strategy.getClass().getSimpleName());
    }

    /** 根据配置创建对应的 LLM Provider */
    public static LLMProvider create(Config config) {
        String model = config.getAgents().getDefaults().getModel();

        for (ProviderStrategy strategy : strategies) {
            if (strategy.supports(model)) {
                logger.info("Provider matched: model={} → {}", model, strategy.getClass().getSimpleName());
                return strategy.create(config, model);
            }
        }

        throw new IllegalStateException(
                "No provider strategy matched model '" + model
                + "'. Available strategies: " + strategies.size());
    }

    // ═══════════ 默认策略注册 ═══════════

    private static void registerDefaults() {
        // OpenAI 系（gpt-*, o1, o3, o4）
        register(new ProviderStrategy() {
            @Override public boolean supports(String model) {
                return model.startsWith("gpt-") || model.startsWith("o1")
                        || model.startsWith("o3") || model.startsWith("o4");
            }

            @Override public LLMProvider create(Config config, String model) {
                Config.ProviderConfig cfg = config.getProviders().getOpenai();
                String apiKey = resolveApiKey(cfg, "OPENAI_API_KEY");
                return new OpenAIProvider(apiKey, model);
            }
        });

        // DeepSeek 系
        register(new ProviderStrategy() {
            @Override public boolean supports(String model) {
                return model.startsWith("deepseek");
            }

            @Override public LLMProvider create(Config config, String model) {
                Config.ProviderConfig cfg = config.getProviders().getDeepseek();
                String apiKey = resolveApiKey(cfg, "DEEPSEEK_API_KEY");
                if (apiKey == null || apiKey.isBlank()) {
                    throw new IllegalStateException("DeepSeek API key not configured");
                }
                return new DeepSeekProvider(apiKey, model, cfg.getApiBase());
            }
        });

        // 兜底：默认当作 OpenAI 兼容 API
        register(new ProviderStrategy() {
            @Override public boolean supports(String model) { return true; } // 永远匹配

            @Override public LLMProvider create(Config config, String model) {
                Config.ProviderConfig cfg = config.getProviders().getOpenai();
                String apiKey = resolveApiKey(cfg, "OPENAI_API_KEY");
                if (apiKey == null || apiKey.isBlank()) {
                    throw new IllegalStateException(
                            "No API key configured for model '" + model + "'");
                }
                return new OpenAIProvider(apiKey, model);
            }
        });
    }

    // ═══════════ 工具方法 ═══════════

    /** 解析 API Key：优先使用配置文件，没有则读环境变量 */
    private static String resolveApiKey(Config.ProviderConfig cfg, String envVar) {
        if (cfg.isConfigured()) return cfg.getApiKey();
        String fromEnv = System.getenv(envVar);
        if (fromEnv != null && !fromEnv.isBlank()) {
            cfg.setApiKey(fromEnv);
            return fromEnv;
        }
        return cfg.getApiKey(); // 可能为空
    }

    // ═══════════ 策略接口 ═══════════

    /** Provider 创建策略：声明能处理哪些模型 + 如何创建 */
    public interface ProviderStrategy {
        /** 判断是否匹配该模型 */
        boolean supports(String model);
        /** 创建 Provider 实例 */
        LLMProvider create(Config config, String model);
    }
}
