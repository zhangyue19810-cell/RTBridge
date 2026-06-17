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
import static org.lwjgl.vulkan.KHRExternalMemory.VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.KHRSpirv14.*;
import static org.lwjgl.vulkan.KHRShaderFloatControls.*;
import static org.lwjgl.vulkan.VK12.*;

public class VulkanContext implements AutoCloseable {

    public VkInstance instance;
    public VkPhysicalDevice physDevice;
    public VkDevice device;
    public VkQueue computeQueue;
    public int computeQueueFamily = -1;
    public VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps;

    private boolean ownsInstance = false;

    private static final List<String> REQUIRED_EXTS = List.of(
        VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
        VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
        VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
        VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME,
        VK_KHR_SHADER_FLOAT_CONTROLS_EXTENSION_NAME,
        VK_KHR_SPIRV_1_4_EXTENSION_NAME,
        VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME,
        VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
        VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME
    );

    private String deviceName = "Unknown";

    public boolean init() {
        VulkanContextRaw raw = new VulkanContextRaw();
        if (raw.init(this)) return true;

        RTBridgeMod.LOGGER.info("[Vulkan] Raw 修补路径失败，尝试直接标准路径");

        boolean[] result = {false};
        Throwable[] err = {null};

        int prevStackSize = org.lwjgl.system.Configuration.STACK_SIZE.get(64);
        org.lwjgl.system.Configuration.STACK_SIZE.set(4096);

        Thread t = new Thread(null, () -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (!tryGetIrisInstance()) {
                    if (!createInstance(stack)) return;
                    ownsInstance = true;
                }
                if (!pickPhysDevice(stack)) return;
                if (!createDevice(stack)) return;
                queryRTProps(stack);

                RTBridgeMod.LOGGER.info("[Vulkan] 就绪 — {} API={}.{}",
                    getDeviceName(),
                    VK_VERSION_MAJOR(getApiVersion()),
                    VK_VERSION_MINOR(getApiVersion()));

                result[0] = true;
            } catch (Throwable e) {
                err[0] = e;
            }
        }, "RTBridge-VulkanInit", 64L * 1024 * 1024);

        try {
            t.start();
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            org.lwjgl.system.Configuration.STACK_SIZE.set(prevStackSize);
        }

        if (err[0] != null) {
            String msg = err[0].getMessage();
            RTBridgeMod.LOGGER.error("[Vulkan] 初始化失败: {}", msg);
        }

        return result[0];
    }

    private boolean tryGetIrisInstance() {
        try {
            Class<?> irisClass = Class.forName("net.irisshaders.iris.gl.IrisRenderSystem");
            Object vkHandle = irisClass.getMethod("getVkInstance").invoke(null);
            if (vkHandle instanceof Long handle && handle != 0L) {
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

    private boolean createInstance(MemoryStack stack) {
        try {
            org.lwjgl.vulkan.VK.create();
        } catch (Throwable ignored) {}

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
        if (res != VK_SUCCESS) return false;

        instance = new VkInstance(pInst.get(0), ci);
        return true;
    }

    private boolean pickPhysDevice(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumeratePhysicalDevices(instance, count, null);

        PointerBuffer devices = stack.mallocPointer(count.get(0));
        vkEnumeratePhysicalDevices(instance, count, devices);

        physDevice = new VkPhysicalDevice(devices.get(0), instance);
        return true;
    }

    private boolean createDevice(MemoryStack stack) {
        computeQueueFamily = findComputeFamily(stack);
        if (computeQueueFamily < 0) return false;

        VkDeviceQueueCreateInfo.Buffer qci =
            VkDeviceQueueCreateInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(computeQueueFamily)
                .pQueuePriorities(stack.floats(1.0f));

        VkPhysicalDeviceBufferDeviceAddressFeatures bda =
            VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES)
                .bufferDeviceAddress(true);

        VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
            .pQueueCreateInfos(qci)
            .pNext(bda.address());

        PointerBuffer pDev = stack.mallocPointer(1);
        int res = vkCreateDevice(physDevice, dci, null, pDev);
        if (res != VK_SUCCESS) return false;

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
            if ((props.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0)
                return i;
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
    }

    private boolean supportsRT(VkPhysicalDevice dev, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(dev, (String) null, count, null);

        VkExtensionProperties.Buffer exts =
            VkExtensionProperties.malloc(count.get(0), stack);

        vkEnumerateDeviceExtensionProperties(dev, (String) null, count, exts);

        List<String> available = new ArrayList<>();
        for (VkExtensionProperties e : exts)
            available.add(e.extensionNameString());

        return available.containsAll(REQUIRED_EXTS);
    }

    private String getDeviceName() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties p = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(physDevice, p);
            return p.deviceNameString();
        }
    }

    private int getApiVersion() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties p = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(physDevice, p);
            return p.apiVersion();
        }
    }

    @Override
    public void close() {
        if (device != null)
            vkDestroyDevice(device, null);
        if (ownsInstance && instance != null)
            vkDestroyInstance(instance, null);
    }
}
