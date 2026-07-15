package com.nanobot.tools.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具方法注解 — 标记一个方法为 Nanobot Tool，启动时自动扫描注册。
 *
 * 方法签名要求：返回 String，参数可选（基本类型/String）。
 *
 * 使用示例：
 * <pre>
 * public class MyTools {
 *     &#064;ToolDef(description = "获取当前时间")
 *     public String getCurrentTime(String timezone) {
 *         return ZonedDateTime.now(ZoneId.of(timezone)).toString();
 *     }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolDef {

    /** 工具名称（默认取方法名转 snake_case） */
    String name() default "";

    /** 工具描述 */
    String description() default "";

    /** 是否启用 */
    boolean enabled() default true;
}
