package com.nanobot.tools.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具参数注解 — 标注方法参数的类型和描述。
 *
 * <pre>
 * &#064;ToolDef(description = "搜索文件")
 * public String search(@ToolParam(description = "搜索关键词", required = true) String query,
 *                       @ToolParam(description = "最大结果数") int limit) { ... }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {

    /** 参数描述 */
    String description() default "";

    /** 是否必填 */
    boolean required() default false;
}
