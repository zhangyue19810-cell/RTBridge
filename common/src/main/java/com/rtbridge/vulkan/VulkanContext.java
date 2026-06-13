package com.rtbridge.vulkan;

import com.rtbridge.RTBridgeMod;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.EXTDescriptorIndexing.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.*;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.KHRSpirv14.*;
import static org.lwjgl.vulkan.KHRShaderFloatControls.*;
import static org.lwjgl.vulkan.VK12.*;

public class VulkanContext implements AutoCloseable {

    public VkInstance       instance;
    public VkPhysicalDevice physDevice;
    public VkDevice         device;
    public VkQueue          computeQueue;
    public int              computeQueueFamily = -1;
    public VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps;

    // 是否由我们自己创建的 instance（需要我们销毁）
    private boolean ownsInstance = false;

    private static final List<String> REQUIRED_EXTS = List.of(
        VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
        VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
        VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
        VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME,
        VK_KHR_SHADER_FLOAT_CONTROLS_EXTENSION_NAME,
        VK_KHR_SPIRV_1_4_EXTENSION_NAME,
        VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME
    );

    public boolean init() {
        // VkInstance.getInstanceCapabilities 会加载所有扩展，RTX 5070 扩展数量巨大
        // 必须在大栈线程里运行，避免 StackOverflow
        boolean[] result = {false};
        Throwable[] err  = {null};

        Thread t = new Thread(null, () -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (!tryGetIrisInstance()) {
                    if (!createInstance(stack)) return;
                    ownsInstance = true;
                }
                if (!pickPhysDevice(stack)) return;
                if (!createDevice(stack))   return;
                queryRTProps(stack);
                RTBridgeMod.LOGGER.info("[Vulkan] 就绪 — {} API={}.{}",
                    getDeviceName(),
                    VK_VERSION_MAJOR(getApiVersion()),
                    VK_VERSION_MINOR(getApiVersion()));
                result[0] = true;
            } catch (Throwable e) {
                err[0] = e;
            }
        }, "RTBridge-VulkanInit", 64L * 1024 * 1024); // 64MB 栈

        try {
            t.start();
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        if (err[0] != null) {
            RTBridgeMod.LOGGER.error("[Vulkan] 初始化失败: {}", err[0].getMessage());
        }
        return result[0];
    }

    // ── 尝试从 Iris 获取 VkInstance ──────────────────────────────────────────

    private boolean tryGetIrisInstance() {
        try {
            // Iris 1.7+ 暴露了 VulkanContext
            Class<?> irisClass = Class.forName(
                "net.irisshaders.iris.gl.IrisRenderSystem");
            Object vkHandle = irisClass.getMethod("getVkInstance").invoke(null);
            if (vkHandle instanceof Long handle && handle != 0L) {
                // 用 Iris 的 instance handle 构建 VkInstance
                // 注意：Iris 的 VkInstanceCreateInfo 已经消亡，用 null capabilities
                instance = new VkInstance(handle, null);
                RTBridgeMod.LOGGER.info("[Vulkan] 使用 Iris VkInstance: 0x{}",
                    Long.toHexString(handle));
                return true;
            }
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.debug("[Vulkan] Iris VkInstance 不可用: {}", e.getMessage());
        }
        return false;
    }

    // ── 自建 Instance ────────────────────────────────────────────────────────

    private boolean createInstance(MemoryStack stack) {
        // 初始化 LWJGL Vulkan 函数指针（加载 vulkan-1.dll/so）
        try {
            org.lwjgl.vulkan.VK.create();
            RTBridgeMod.LOGGER.info("[Vulkan] VK.create() 成功");
        } catch (IllegalStateException e) {
            // "already created" 说明已经初始化过，继续即可
            RTBridgeMod.LOGGER.debug("[Vulkan] VK 已初始化: {}", e.getMessage());
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.error("[Vulkan] VK.create() 失败: {}", e.getMessage());
            return false;
        }

        VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
            .pApplicationName(stack.UTF8Safe("RTBridge"))
            .applicationVersion(VK_MAKE_VERSION(0, 1, 0))
            .apiVersion(VK_API_VERSION_1_2);

        VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
            .pApplicationInfo(appInfo);

        PointerBuffer pInst = stack.mallocPointer(1);
        int res = vkCreateInstance(ci, null, pInst);
        if (res != VK_SUCCESS) {
            RTBridgeMod.LOGGER.error("[Vulkan] vkCreateInstance 失败: {}", res);
            return false;
        }

        // 用 MCCapabilities 兼容方式创建 VkInstance
        try {
            instance = new VkInstance(pInst.get(0), ci);
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.error("[Vulkan] VkInstance 包装失败: {}", e.getMessage());
            return false;
        }
        return true;
    }

    // ── Physical device ───────────────────────────────────────────────────────

    private boolean pickPhysDevice(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumeratePhysicalDevices(instance, count, null);
        if (count.get(0) == 0) {
            RTBridgeMod.LOGGER.error("[Vulkan] 没有找到物理设备");
            return false;
        }

        PointerBuffer pDevices = stack.mallocPointer(count.get(0));
        vkEnumeratePhysicalDevices(instance, count, pDevices);

        for (int i = 0; i < count.get(0); i++) {
            VkPhysicalDevice candidate =
                new VkPhysicalDevice(pDevices.get(i), instance);
            if (supportsRT(candidate, stack)) {
                physDevice = candidate;
                RTBridgeMod.LOGGER.info("[Vulkan] 选择 RT 设备[{}]: {}",
                    i, getNameOf(candidate, stack));
                return true;
            }
        }

        // 没有 RT 设备，选第一个
        physDevice = new VkPhysicalDevice(pDevices.get(0), instance);
        RTBridgeMod.LOGGER.warn("[Vulkan] 无 RT 支持设备，选第一个: {}",
            getNameOf(physDevice, stack));
        return false; // 返回 false = 不启用 RT
    }

    private boolean supportsRT(VkPhysicalDevice dev, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(dev, (String) null, count, null);
        VkExtensionProperties.Buffer exts =
            VkExtensionProperties.malloc(count.get(0), stack);
        vkEnumerateDeviceExtensionProperties(dev, (String) null, count, exts);

        List<String> available = new ArrayList<>();
        for (VkExtensionProperties e : exts) available.add(e.extensionNameString());
        return available.containsAll(REQUIRED_EXTS);
    }

    private String getNameOf(VkPhysicalDevice dev, MemoryStack stack) {
        VkPhysicalDeviceProperties p = VkPhysicalDeviceProperties.malloc(stack);
        vkGetPhysicalDeviceProperties(dev, p);
        return p.deviceNameString();
    }

    private String getDeviceName() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return getNameOf(physDevice, stack);
        }
    }

    private int getApiVersion() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties p = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(physDevice, p);
            return p.apiVersion();
        }
    }

    // ── Logical device ────────────────────────────────────────────────────────

    private boolean createDevice(MemoryStack stack) {
        computeQueueFamily = findComputeFamily(stack);
        if (computeQueueFamily < 0) return false;

        VkDeviceQueueCreateInfo.Buffer qci =
            VkDeviceQueueCreateInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(computeQueueFamily)
                .pQueuePriorities(stack.floats(1.0f));

        VkPhysicalDeviceBufferDeviceAddressFeatures bdaFeats =
            VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES)
                .bufferDeviceAddress(true);

        VkPhysicalDeviceAccelerationStructureFeaturesKHR asFeats =
            VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR)
                .accelerationStructure(true)
                .pNext(bdaFeats.address());

        VkPhysicalDeviceRayTracingPipelineFeaturesKHR rtFeats =
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR)
                .rayTracingPipeline(true)
                .pNext(asFeats.address());

        PointerBuffer extNames = stack.mallocPointer(REQUIRED_EXTS.size());
        REQUIRED_EXTS.forEach(e -> extNames.put(stack.UTF8(e)));
        extNames.flip();

        VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
            .pQueueCreateInfos(qci)
            .ppEnabledExtensionNames(extNames)
            .pNext(rtFeats.address());

        PointerBuffer pDev = stack.mallocPointer(1);
        int res = vkCreateDevice(physDevice, dci, null, pDev);
        if (res != VK_SUCCESS) {
            RTBridgeMod.LOGGER.error("[Vulkan] vkCreateDevice 失败: {}", res);
            return false;
        }
        device = new VkDevice(pDev.get(0), physDevice, dci);

        PointerBuffer pQ = stack.mallocPointer(1);
        vkGetDeviceQueue(device, computeQueueFamily, 0, pQ);
        computeQueue = new VkQueue(pQ.get(0), device);
        return true;
    }

    private int findComputeFamily(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(physDevice, count, null);
        VkQueueFamilyProperties.Buffer props =
            VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(physDevice, count, props);
        for (int i = 0; i < props.capacity(); i++) {
            if ((props.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) return i;
        }
        return -1;
    }

    private void queryRTProps(MemoryStack stack) {
        rtProps = VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc()
            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR);
        VkPhysicalDeviceProperties2 p2 = VkPhysicalDeviceProperties2.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
            .pNext(rtProps.address());
        vkGetPhysicalDeviceProperties2(physDevice, p2);
        RTBridgeMod.LOGGER.info("[Vulkan] maxRecursion={} handleSize={}",
            rtProps.maxRayRecursionDepth(), rtProps.shaderGroupHandleSize());
    }

    @Override
    public void close() {
        if (rtProps  != null) rtProps.free();
        if (device   != null) vkDestroyDevice(device, null);
        if (ownsInstance && instance != null) vkDestroyInstance(instance, null);
    }
}
