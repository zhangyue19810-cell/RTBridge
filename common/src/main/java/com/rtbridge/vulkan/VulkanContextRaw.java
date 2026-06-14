package com.rtbridge.vulkan;

import com.rtbridge.RTBridgeMod;

import java.lang.reflect.Field;

/**
 * VulkanContextRaw — 用 Unsafe 修补 MemoryStack DEFAULT_STACK_SIZE，
 * 然后在新线程（新 MemoryStack）里初始化 Vulkan。
 *
 * 关键：
 *   1. DEFAULT_STACK_SIZE 是静态常量，只在线程第一次访问 MemoryStack 时读取。
 *   2. 渲染线程的 MemoryStack 在 MC 启动时就创建好了（64KB），对它无效。
 *   3. 修补后创建的「新线程」第一次访问 MemoryStack 时，
 *      会用修补后的值（4MB），从而能容纳 RTX 5070 的 400+ 扩展。
 */
public class VulkanContextRaw {

    private static final int BIG_STACK = 4 * 1024 * 1024; // 4MB

    /** 返回 true 表示 Vulkan 初始化成功（包含 RT 支持） */
    public boolean init(VulkanContext ctx) {
        // Step 1: 找到 DEFAULT_STACK_SIZE 字段
        Field field = findField();
        if (field == null) {
            RTBridgeMod.LOGGER.warn("[VulkanRaw] 找不到 MemoryStack 大小字段");
            return false;
        }

        // Step 2: 用 Unsafe 修补静态常量
        int original;
        try {
            original = patch(field, BIG_STACK);
            RTBridgeMod.LOGGER.info("[VulkanRaw] MemoryStack 已修补: {}KB → {}MB",
                original / 1024, BIG_STACK / 1024 / 1024);
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.warn("[VulkanRaw] Unsafe 修补失败: {}", e.getMessage());
            return false;
        }

        // Step 3: 在新线程里初始化（新线程 = 全新 MemoryStack，会用修补后的 4MB）
        boolean[] result = {false};
        Thread t = new Thread(() -> {
            // 这个线程第一次调用 MemoryStack.stackPush() 时，
            // 会创建 4MB 的 MemoryStack（读取修补后的 DEFAULT_STACK_SIZE）
            result[0] = ctx.initInternal();
        }, "RTBridge-VulkanInit-Patched");
        t.setDaemon(true);

        try {
            t.start();
            t.join(30_000); // 最多等 30 秒
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Step 4: 修补完成，恢复原值（不影响已有线程，只影响未来的新线程）
        try {
            patch(field, original);
            RTBridgeMod.LOGGER.debug("[VulkanRaw] MemoryStack 已恢复: {}KB", original / 1024);
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.warn("[VulkanRaw] 恢复失败（无害）: {}", e.getMessage());
        }

        return result[0];
    }

    // ── 查找字段 ─────────────────────────────────────────────────────────────

    private Field findField() {
        // LWJGL 3.x 不同版本字段位置不同
        String[][] targets = {
            {"org.lwjgl.system.MemoryStack",    "DEFAULT_STACK_SIZE"},
            {"org.lwjgl.system.ThreadLocalUtil", "DEFAULT_STACK_SIZE"},
            {"org.lwjgl.system.MemoryStack",    "STACK_SIZE"},
        };
        for (String[] t : targets) {
            try {
                Field f = Class.forName(t[0]).getDeclaredField(t[1]);
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    return f;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ── Unsafe 修改静态 final int ─────────────────────────────────────────────

    @SuppressWarnings({"deprecation", "unchecked"})
    private int patch(Field field, int newVal) throws Throwable {
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) uf.get(null);

        Object base   = unsafe.staticFieldBase(field);
        long   offset = unsafe.staticFieldOffset(field);
        int    old    = unsafe.getInt(base, offset);
        unsafe.putInt(base, offset, newVal);
        return old;
    }
}
