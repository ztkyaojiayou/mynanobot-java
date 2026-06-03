package com.nanobot.cron;

import com.nanobot.bus.MessageBus;
import com.nanobot.bus.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cron 定时任务调度器
 * ======================
 * 
 * 本类实现了基于 cron 表达式的定时任务调度系统。
 * 支持标准的 5 字段 cron 表达式格式：
 * 
 * ┌───────────── 分钟 (0 - 59)
 * │ ┌───────────── 小时 (0 - 23)
 * │ │ ┌───────────── 日期 (1 - 31)
 * │ │ │ ┌───────────── 月份 (1 - 12)
 * │ │ │ │ ┌───────────── 星期 (0 - 6) (周日=0 或 7)
 * │ │ │ │ │
 * * * * * *
 * 
 * **支持的特殊字符**：
 * - * : 匹配任何值
 * - ? : 不指定值（用于日期和星期）
 * - , : 列出多个值
 * - - : 指定范围
 * - / : 步长
 * 
 * **使用示例**：
 * ```java
 * CronScheduler scheduler = new CronScheduler(messageBus);
 * scheduler.schedule("0 * * * *", () -> {
 *     messageBus.publish(OutboundMessage.builder()
 *         .channel("system")
 *         .content("每分钟执行一次")
 *         .build());
 * });
 * ```
 */
public class CronScheduler {
    
    private static final Logger logger = LoggerFactory.getLogger(CronScheduler.class);
    
    private final MessageBus messageBus;
    private final ScheduledExecutorService executorService;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final Pattern cronPattern = Pattern.compile(
        "^\\s*(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s*$"
    );
    
    public CronScheduler(MessageBus messageBus) {
        this.messageBus = messageBus;
        this.executorService = Executors.newScheduledThreadPool(4);
        logger.info("CronScheduler initialized");
    }
    
    /**
     * 调度定时任务
     * 
     * @param cronExpression cron 表达式
     * @param task 要执行的任务
     * @return 任务 ID，用于取消任务
     */
    public String schedule(String cronExpression, Runnable task) {
        String taskId = UUID.randomUUID().toString();
        
        try {
            CronExpression expr = parseCronExpression(cronExpression);
            
            Runnable wrapperTask = () -> {
                try {
                    task.run();
                } catch (Exception e) {
                    logger.error("Cron task execution failed", e);
                    try {
                        messageBus.publishOutbound(OutboundMessage.builder()
                            .channel("system")
                            .content("Cron task error: " + e.getMessage())
                            .build());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            
            long initialDelay = calculateInitialDelay(expr);
            long period = 24 * 60 * 60 * 1000; // 每天检查
            
            ScheduledFuture<?> future = executorService.scheduleAtFixedRate(
                () -> {
                    if (shouldExecute(expr)) {
                        wrapperTask.run();
                    }
                },
                initialDelay,
                period,
                TimeUnit.MILLISECONDS
            );
            
            scheduledTasks.put(taskId, future);
            logger.info("Scheduled cron task: {} -> {}", taskId, cronExpression);
            
            return taskId;
            
        } catch (Exception e) {
            logger.error("Failed to schedule cron task: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid cron expression: " + cronExpression, e);
        }
    }
    
    /**
     * 取消定时任务
     * 
     * @param taskId 任务 ID
     * @return 是否成功取消
     */
    public boolean cancel(String taskId) {
        ScheduledFuture<?> future = scheduledTasks.remove(taskId);
        if (future != null) {
            future.cancel(false);
            logger.info("Cancelled cron task: {}", taskId);
            return true;
        }
        return false;
    }
    
    /**
     * 停止调度器
     */
    public void shutdown() {
        scheduledTasks.values().forEach(f -> f.cancel(false));
        scheduledTasks.clear();
        executorService.shutdown();
        logger.info("CronScheduler shutdown");
    }
    
    /**
     * 获取所有已调度的任务
     */
    public Set<String> getScheduledTaskIds() {
        return new HashSet<>(scheduledTasks.keySet());
    }
    
    /**
     * 解析 cron 表达式
     */
    private CronExpression parseCronExpression(String expression) {
        Matcher matcher = cronPattern.matcher(expression);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid cron expression format: " + expression);
        }
        
        return new CronExpression(
            parseField(matcher.group(1), 0, 59),   // 分钟
            parseField(matcher.group(2), 0, 23),   // 小时
            parseField(matcher.group(3), 1, 31),   // 日期
            parseField(matcher.group(4), 1, 12),   // 月份
            parseField(matcher.group(5), 0, 7)     // 星期
        );
    }
    
    /**
     * 解析单个字段
     */
    private Set<Integer> parseField(String field, int min, int max) {
        Set<Integer> values = new TreeSet<>();
        
        if ("*".equals(field) || "?".equals(field)) {
            for (int i = min; i <= max; i++) {
                values.add(i);
            }
            return values;
        }
        
        String[] parts = field.split(",");
        for (String part : parts) {
            if (part.contains("/")) {
                // 步长表达式
                String[] stepParts = part.split("/");
                Set<Integer> baseValues = parseField(stepParts[0], min, max);
                int step = Integer.parseInt(stepParts[1]);
                int count = 0;
                for (int val : baseValues) {
                    if (count % step == 0) {
                        values.add(val);
                    }
                    count++;
                }
            } else if (part.contains("-")) {
                // 范围表达式
                String[] rangeParts = part.split("-");
                int start = Integer.parseInt(rangeParts[0]);
                int end = Integer.parseInt(rangeParts[1]);
                for (int i = start; i <= end; i++) {
                    values.add(i);
                }
            } else {
                // 单个值
                values.add(Integer.parseInt(part));
            }
        }
        
        // 验证值范围
        for (int val : values) {
            if (val < min || val > max) {
                throw new IllegalArgumentException(
                    "Value " + val + " out of range [" + min + "-" + max + "] in field: " + field);
            }
        }
        
        return values;
    }
    
    /**
     * 计算初始延迟
     */
    private long calculateInitialDelay(CronExpression expr) {
        long now = System.currentTimeMillis();
        long nextTime = findNextExecutionTime(expr, now);
        return Math.max(0, nextTime - now);
    }
    
    /**
     * 查找下一个执行时间
     */
    private long findNextExecutionTime(CronExpression expr, long fromTime) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(fromTime);
        
        while (true) {
            int minute = calendar.get(Calendar.MINUTE);
            int hour = calendar.get(Calendar.HOUR_OF_DAY);
            int dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH);
            int month = calendar.get(Calendar.MONTH) + 1; // Calendar 月份从 0 开始
            int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1; // 转换为 0-6
            
            if (expr.minutes.contains(minute) &&
                expr.hours.contains(hour) &&
                expr.days.contains(dayOfMonth) &&
                expr.months.contains(month) &&
                expr.weekDays.contains(dayOfWeek)) {
                
                // 确保不是当前时间之前的时间
                long candidate = calendar.getTimeInMillis();
                if (candidate >= fromTime) {
                    return candidate;
                }
            }
            
            // 增加一分钟
            calendar.add(Calendar.MINUTE, 1);
            
            // 防止无限循环
            if (calendar.getTimeInMillis() > fromTime + 365L * 24 * 60 * 60 * 1000) {
                throw new IllegalStateException("Cannot find valid execution time within one year");
            }
        }
    }
    
    /**
     * 判断当前是否应该执行
     */
    private boolean shouldExecute(CronExpression expr) {
        LocalDateTime now = LocalDateTime.now();
        
        int minute = now.getMinute();
        int hour = now.getHour();
        int dayOfMonth = now.getDayOfMonth();
        int month = now.getMonthValue();
        int dayOfWeek = now.getDayOfWeek().getValue() % 7; // 转换为 0-6
        
        return expr.minutes.contains(minute) &&
               expr.hours.contains(hour) &&
               expr.days.contains(dayOfMonth) &&
               expr.months.contains(month) &&
               expr.weekDays.contains(dayOfWeek);
    }
    
    /**
     * cron 表达式内部表示
     */
    private static class CronExpression {
        final Set<Integer> minutes;
        final Set<Integer> hours;
        final Set<Integer> days;
        final Set<Integer> months;
        final Set<Integer> weekDays;
        
        CronExpression(Set<Integer> minutes, Set<Integer> hours, 
                      Set<Integer> days, Set<Integer> months, Set<Integer> weekDays) {
            this.minutes = minutes;
            this.hours = hours;
            this.days = days;
            this.months = months;
            this.weekDays = weekDays;
        }
    }
}
