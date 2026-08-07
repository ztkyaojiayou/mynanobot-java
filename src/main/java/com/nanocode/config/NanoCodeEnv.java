package com.nanocode.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 环境变量/系统属性/运行时目录的<b>品牌兼容层</b>。
 *
 * <h2>为什么需要这个类</h2>
 * 品牌从 nanobot 升级为 nanocode 后，环境变量、系统属性、配置目录都换了新名。
 * 但<b>现有部署环境</b>（用户的 ~/.zshrc、服务器 env、已有钩子脚本）可能仍用旧名。
 * 本类提供统一入口：<b>新名优先，旧名 fallback</b>，保证升级不破坏现有环境。
 *
 * <h2>兼容映射</h2>
 * <pre>
 *   nanocode.workspace        ← 旧 nanobot.workspace
 *   NANOCODE_API_KEY          ← 旧 NANOBOT_API_KEY
 *   NANOCODE_MODEL            ← 旧 NANOBOT_MODEL
 *   nanocode.dir              ← 旧 nanobot.dir
 *   .nanocode 目录            ← 旧 .nanobot 目录
 *   NANOCODE.md               ← 旧 NANOBOT.md
 * </pre>
 */
public final class NanoCodeEnv {

    private NanoCodeEnv() {}

    // ═══════════ 系统属性兼容读取 ═══════════

    /** 读系统属性：新名优先，旧名 fallback。 */
    public static String getProperty(String primary, String legacy) {
        String v = System.getProperty(primary);
        if (v == null || v.isBlank()) {
            v = System.getProperty(legacy);
        }
        return v;
    }

    /** 同时写新名和旧名——保证新读者和旧读者都能读到。 */
    public static void setPropertyBoth(String primary, String legacy, String value) {
        System.setProperty(primary, value);
        System.setProperty(legacy, value);
    }

    // ═══════════ 环境变量兼容读取 ═══════════

    /** 读环境变量：新名优先，旧名 fallback。 */
    public static String getEnv(String primary, String legacy) {
        String v = System.getenv(primary);
        if (v == null || v.isBlank()) {
            v = System.getenv(legacy);
        }
        return v;
    }

    // ═══════════ 运行时目录解析 ═══════════

    /**
     * 解析运行时目录路径：优先用 `.nanocode`，若该目录不存在但旧 `.nanobot` 存在，
     * 则回退到旧目录——保证旧会话/记忆/技能数据不丢失。
     *
     * @param baseDir 基础目录（如 workspace 或 user.home）
     * @return 实际存在的目录路径；两者都不存在时返回新名路径
     */
    public static Path resolveRuntimeDir(String baseDir, String... sub) {
        Path nanoCode = Paths.get(baseDir, sub.length > 0 ? sub[0] : ".nanocode");
        if (Files.exists(nanoCode)) return nanoCode;
        Path legacy = Paths.get(baseDir, ".nanobot");
        if (Files.exists(legacy)) return legacy;
        return nanoCode;
    }

    /**
     * 解析项目记忆文件名：优先用 `NANOCODE.md`，若不存在但旧 `NANOBOT.md` 存在则回退。
     *
     * @param dir 目录
     * @return 实际存在的记忆文件名；都不存在时返回新名
     */
    public static String resolveMemoryFileName(Path dir) {
        if (Files.exists(dir.resolve("NANOBOT.md"))) return "NANOBOT.md";
        return "NANOCODE.md";
    }
}
