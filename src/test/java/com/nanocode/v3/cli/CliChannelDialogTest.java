package com.nanocode.v3.cli;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归测试：修复权限确认框输入超时（终端输入竞争）的核心路由逻辑。
 * <p>
 * 场景：主线程阻塞在 readCliLine（mainThreadReading=true）期间，工具线程弹确认框。
 * 修复后工具线程不再直接读终端，而是发布 pendingAnswer 等待；主线程读到输入行后
 * 经 drainPendingAnswer 消费为回答。本测试验证该 future 路由的发布/消费闭环。
 */
class CliChannelDialogTest {

    /** 用动态代理造一个最小 ConfigurableApplicationContext（构造里只取 appContext::close 方法引用，不调用） */
    private static CliChannel newChannel() {
        ConfigurableApplicationContext ctx = (ConfigurableApplicationContext) Proxy.newProxyInstance(
                CliChannelDialogTest.class.getClassLoader(),
                new Class<?>[]{ConfigurableApplicationContext.class},
                (proxy, method, args) -> null);
        return new CliChannel(ctx);
    }

    /** drainPendingAnswer 消费挂起请求后置空，二次调用返回 false（普通行走正常流程） */
    @Test
    void drainConsumesPendingAnswerAndIsIdempotent() throws Exception {
        CliChannel c = newChannel();
        CompletableFuture<String> f = new CompletableFuture<>();
        field("pendingAnswer").set(c, f);

        Method drain = method("drainPendingAnswer", String.class);
        assertTrue((Boolean) drain.invoke(c, "2"), "存在挂起回答时应消费本行");
        assertEquals("2", f.getNow(null), "回答应 complete 给等待的工具线程");

        assertFalse((Boolean) drain.invoke(c, "3"), "已消费后再次调用应返回 false");
    }

    /**
     * 工具线程 readInteractiveLine 无条件发布 pendingAnswer 等待；主线程 drain 后工具线程拿到回答。
     * 无论主线程在 readCliLine 还是 waitForStreamCompletion，消费路径一致。
     */
    @Test
    void interactiveLinePublishesPendingAnswerAndDrainWakesIt() throws Exception {
        CliChannel c = newChannel();

        Method read = method("readInteractiveLine", int.class);
        Method drain = method("drainPendingAnswer", String.class);

        AtomicReference<String> result = new AtomicReference<>();
        Thread tool = new Thread(() -> {
            try {
                result.set((String) read.invoke(c, 10));
            } catch (Exception e) {
                result.set("ERR:" + e);
            }
        });
        tool.start();

        // 等待工具线程发布 pendingAnswer（轮询而非固定 sleep，避免 flaky）
        CompletableFuture<?> published = null;
        for (int i = 0; i < 100 && published == null; i++) {
            published = (CompletableFuture<?>) field("pendingAnswer").get(c);
            if (published == null) Thread.sleep(20);
        }
        assertNotNull(published, "工具线程应发布 pendingAnswer");

        assertTrue((Boolean) drain.invoke(c, "2"));
        tool.join(3000);
        assertFalse(tool.isAlive(), "工具线程应被回答唤醒而非超时");
        assertEquals("2", result.get());

        // 消费后 pendingAnswer 应被清空（finally 清理），再次 drain 返回 false
        assertNull(field("pendingAnswer").get(c), "drain 后 pendingAnswer 应清空");
        assertFalse((Boolean) drain.invoke(c, "3"));
    }

    private static Field field(String name) throws Exception {
        Field f = CliChannel.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Method method(String name, Class<?>... paramTypes) throws Exception {
        Method m = CliChannel.class.getDeclaredMethod(name, paramTypes);
        m.setAccessible(true);
        return m;
    }
}
