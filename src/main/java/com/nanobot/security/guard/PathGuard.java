package com.nanobot.security.guard;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件路径守卫 — 工作区隔离
 * ===========================
 *
 * 参考 Nanobot (Python) 的 {@code _resolve_path()} 设计。
 * 所有文件工具（read_file, write_file, edit_file, list_dir, glob, grep）
 * 必须通过此守卫验证路径，确保不越出工作区。
 *
 * 核心逻辑：
 * 1. 解析用户输入的路径（相对路径基于 workspace，绝对路径直接使用）
 * 2. normalize() 消除 ".."
 * 3. toRealPath() 解析符号链接
 * 4. 检查解析后的路径是否在允许范围内（workspace + extraAllowedDirs）
 * 5. 不在范围内则抛出 SecurityException
 *
 * 使用示例：
 * ```java
 * PathGuard guard = new PathGuard(Paths.get("/workspace"));
 * guard.addAllowedDir(Paths.get("/tmp"));
 * Path safe = guard.resolvePath("src/main/App.java");
 * ```
 */
@Getter
public class PathGuard {

    private static final Logger logger = LoggerFactory.getLogger(PathGuard.class);

    /** 工作区根路径 */
    private final Path workspace;

    /** 额外允许的目录列表 */
    private final List<Path> extraAllowedDirs;

    /** 是否启用工作区限制（false 时仅做路径归一化，不拦截越界） */
    private boolean restrictToWorkspace;

    /** 严格模式（默认 true），关闭后越界仅记录日志不抛异常 */
    private boolean strictMode = true;

    // ==================== 构造函数 ====================

    /**
     * @param workspace 工作区根路径
     */
    public PathGuard(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.extraAllowedDirs = new ArrayList<>();
        this.restrictToWorkspace = true;
        logger.info("PathGuard initialized: workspace={}", this.workspace);
    }

    /**
     * @param workspace 工作区根路径字符串
     */
    public PathGuard(String workspace) {
        this(Paths.get(workspace));
    }

    // ==================== 配置方法 ====================

    public void setRestrictToWorkspace(boolean restrict) {
        this.restrictToWorkspace = restrict;
    }

    public void setStrictMode(boolean strictMode) {
        this.strictMode = strictMode;
    }

    /**
     * 添加额外允许的目录
     */
    public void addAllowedDir(Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        if (!extraAllowedDirs.contains(normalized)) {
            extraAllowedDirs.add(normalized);
            logger.debug("Added extra allowed dir: {}", normalized);
        }
    }

    /**
     * 添加额外允许的目录（字符串形式）
     */
    public void addAllowedDir(String dir) {
        addAllowedDir(Paths.get(dir));
    }

    // ==================== 核心方法 ====================

    /**
     * 解析并验证路径
     *
     * @param userPath 用户提供的路径（相对或绝对）
     * @return 解析后的安全绝对路径
     * @throws SecurityException 如果路径不在允许范围内
     */
    public Path resolvePath(String userPath) {
        if (userPath == null || userPath.isBlank()) {
            throw new SecurityException("PathGuard", "Path cannot be null or empty");
        }

        Path resolved;
        Path inputPath = Paths.get(userPath);

        // 相对路径基于 workspace 解析，绝对路径直接使用
        if (inputPath.isAbsolute()) {
            resolved = inputPath.normalize();
        } else {
            resolved = workspace.resolve(inputPath).normalize();
        }

        // 如果未启用限制，仅做归一化
        if (!restrictToWorkspace) {
            return resolved;
        }

        // 解析符号链接到真实路径
        Path realPath;
        try {
            if (Files.exists(resolved)) {
                realPath = resolved.toRealPath();
            } else {
                // 文件不存在时检查父目录
                Path parent = resolved.getParent();
                if (parent != null && Files.exists(parent)) {
                    realPath = parent.toRealPath().resolve(resolved.getFileName());
                } else {
                    // 无法解析到真实路径，使用规范化路径
                    realPath = resolved;
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to resolve real path for {}: {}", resolved, e.getMessage());
            realPath = resolved;
        }

        // 检查是否在允许范围内
        if (isPathAllowed(realPath)) {
            logger.debug("Path allowed: {} (resolved from {})", realPath, userPath);
            return resolved;
        }

        String reason = String.format("Path outside allowed directories: %s (resolved to %s)",
                userPath, realPath);
        logger.warn("Path blocked: {}", reason);

        if (strictMode) {
            throw new SecurityException("PathGuard", reason);
        } else {
            // 非严格模式：记录日志但放行
            logger.warn("Non-strict mode: allowing path despite violation");
            return resolved;
        }
    }

    /**
     * 检查路径是否在允许的目录范围内
     */
    public boolean isPathAllowed(Path realPath) {
        // 检查工作区
        if (realPath.startsWith(workspace)) {
            return true;
        }
        // 检查额外允许目录
        for (Path allowed : extraAllowedDirs) {
            if (realPath.startsWith(allowed)) {
                return true;
            }
        }
        return false;
    }
}
