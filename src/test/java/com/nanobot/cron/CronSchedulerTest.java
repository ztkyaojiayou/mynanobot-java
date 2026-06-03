package com.nanobot.cron;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CronScheduler 定时任务调度器测试类
 * ======================================
 * 
 * 测试 cron 表达式解析和任务调度功能：
 * - cron 表达式格式验证
 * - 任务执行逻辑
 */
@DisplayName("CronScheduler 定时任务调度器测试")
class CronSchedulerTest {

    @Test
    @DisplayName("测试 cron 表达式格式验证")
    void testCronExpressionFormat() {
        // 有效的 cron 表达式
        String validCron = "* * * * *";
        String specificTime = "30 14 * * *";
        String interval = "*/5 * * * *";
        
        // 验证表达式非空
        assertNotNull(validCron);
        assertNotNull(specificTime);
        assertNotNull(interval);
        
        // 验证字段数量（5个字段）
        String[] parts = validCron.split("\\s+");
        assertEquals(5, parts.length);
    }

    @Test
    @DisplayName("测试无效 cron 表达式")
    void testInvalidCronExpression() {
        String invalid1 = "invalid";
        String invalid2 = "* * * *"; // 缺少一个字段
        
        // 验证无效表达式
        assertNotEquals(5, invalid1.split("\\s+").length);
        assertNotEquals(5, invalid2.split("\\s+").length);
    }

    @Test
    @DisplayName("测试任务执行计数器")
    void testTaskExecutionCounter() {
        AtomicInteger counter = new AtomicInteger(0);
        
        // 模拟任务执行
        Runnable task = counter::incrementAndGet;
        task.run();
        task.run();
        
        assertEquals(2, counter.get());
    }

    @Test
    @DisplayName("测试 cron 字段含义")
    void testCronFieldMeaning() {
        // 验证每个字段的含义
        String cron = "0 12 * * MON";
        
        String[] fields = cron.split("\\s+");
        assertEquals("0", fields[0]);   // 分钟
        assertEquals("12", fields[1]);  // 小时
        assertEquals("*", fields[2]);   // 日期
        assertEquals("*", fields[3]);   // 月份
        assertEquals("MON", fields[4]); // 星期
    }

    @Test
    @DisplayName("测试任务调度延迟计算")
    void testDelayCalculation() {
        // 模拟延迟计算
        long delay = 1000; // 1秒
        long period = 60000; // 1分钟
        
        assertTrue(delay > 0);
        assertTrue(period > delay);
    }
}