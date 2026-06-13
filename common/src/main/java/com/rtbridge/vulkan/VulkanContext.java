package com.rtbridge.vulkan;

import com.rtbridge.RTBridgeMod;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
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
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!createInstance(stack)) return false;
            if (!pickPhysDevice(stack)) return false;
            if (!createDevice(stack))   return false;
            queryRTProps(stack);
            RTBridgeMod.LOGGER.info("[Vulkan] Ready — {}", getDeviceName());
            return true;
        } catch (Exception e) {
            RTBridgeMod.LOGGER.error("[Vulkan] Init failed", e);
            return false;
        }
    }

    private boolean createInstance(MemoryStack stack) {
        // 确保 LWJGL Vulkan 函数指针已加载
        try {
            org.lwjgl.vulkan.VK.create();
        } catch (Exception e) {
            RTBridgeMod.LOGGER.warn("[Vulkan] VK.create() 失败: {}", e.getMessage());
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
            RTBridgeMod.LOGGER.error("[Vulkan] vkCreateInstance failed: {}", res);
            return false;
        }
        instance = new VkInstance(pInst.get(0), ci);
        return true;
    }

    private boolean pickPhysDevice(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumeratePhysicalDevices(instance, count, null);
        if (count.get(0) == 0) return false;

        PointerBuffer pDevices = stack.mallocPointer(count.get(0));
        vkEnumeratePhysicalDevices(instance, count, pDevices);

        for (int i = 0; i < count.get(0); i++) {
            VkPhysicalDevice candidate = new VkPhysicalDevice(pDevices.get(i), instance);
            if (supportsRT(candidate, stack)) {
                physDevice = candidate;
                return true;
            }
        }
        RTBridgeMod.LOGGER.warn("[Vulkan] No RT-capable GPU found");
        return false;
    }

    private boolean supportsRT(VkPhysicalDevice dev, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(dev, (String) null, count, null);
        VkExtensionProperties.Buffer exts = VkExtensionProperties.malloc(count.get(0), stack);
        vkEnumerateDeviceExtensionProperties(dev, (String) null, count, exts);

        List<String> available = new ArrayList<>();
        for (VkExtensionProperties e : exts) available.add(e.extensionNameString());
        return available.containsAll(REQUIRED_EXTS);
    }

    private String getDeviceName() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties p = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(physDevice, p);
            return p.deviceNameString();
        }
    }

    private boolean createDevice(MemoryStack stack) {
        computeQueueFamily = findComputeFamily(stack);
        if (computeQueueFamily < 0) return false;

        VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, stack)
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
            RTBridgeMod.LOGGER.error("[Vulkan] vkCreateDevice failed: {}", res);
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
        VkQueueFamilyProperties.Buffer props = VkQueueFamilyProperties.malloc(count.get(0), stack);
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
        RTBridgeMod.LOGGER.info("[Vulkan] maxRecursionDepth={} handleSize={}",
            rtProps.maxRayRecursionDepth(), rtProps.shaderGroupHandleSize());
    }

    @Override
    public void close() {
        if (rtProps != null) rtProps.free();
        if (device   != null) vkDestroyDevice(device, null);
        if (instance != null) vkDestroyInstance(instance, null);
    }
}
