package com.rtbridge.vulkan;

import com.rtbridge.RTBridgeMod;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK12.*;

/**
 * VulkanProbe — 启动时探测当前 GPU 的 RT 能力。
 * 在 VulkanContext.init() 之前调用，快速判断是否值得初始化完整 Vulkan。
 */
public class VulkanProbe {

    public record ProbeResult(
        boolean vulkanAvailable,
        boolean rtAvailable,
        String  deviceName,
        int     apiVersion,
        String  failReason
    ) {
        public static ProbeResult fail(String reason) {
            return new ProbeResult(false, false, "N/A", 0, reason);
        }
    }

    public static ProbeResult probe() {
        try (MemoryStack stack = MemoryStack.stackPush()) {

            // 1. 创建临时 Instance
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8Safe("RTBridge-Probe"))
                .apiVersion(VK_API_VERSION_1_2);

            VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo);

            org.lwjgl.PointerBuffer pInst = stack.mallocPointer(1);
            if (vkCreateInstance(ici, null, pInst) != VK_SUCCESS) {
                return ProbeResult.fail("vkCreateInstance 失败");
            }
            VkInstance instance = new VkInstance(pInst.get(0), ici);

            // 2. 枚举物理设备
            IntBuffer count = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, count, null);
            if (count.get(0) == 0) {
                vkDestroyInstance(instance, null);
                return ProbeResult.fail("没有找到 Vulkan 设备");
            }

            org.lwjgl.PointerBuffer pDevices = stack.mallocPointer(count.get(0));
            vkEnumeratePhysicalDevices(instance, count, pDevices);

            // 3. 遍历设备找最好的
            String bestName = "Unknown";
            int    bestApi  = 0;
            boolean hasRT   = false;

            for (int i = 0; i < count.get(0); i++) {
                VkPhysicalDevice dev = new VkPhysicalDevice(pDevices.get(i), instance);

                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
                vkGetPhysicalDeviceProperties(dev, props);

                String name = props.deviceNameString();
                int    api  = props.apiVersion();

                // 检查 RT 扩展
                boolean devHasRT = hasRTExtensions(dev, stack);

                RTBridgeMod.LOGGER.info("[VulkanProbe] 设备[{}]: {} API={}.{} RT={}",
                    i, name,
                    VK_VERSION_MAJOR(api), VK_VERSION_MINOR(api),
                    devHasRT);

                if (devHasRT || i == 0) {
                    bestName = name;
                    bestApi  = api;
                    hasRT    = devHasRT;
                }
            }

            vkDestroyInstance(instance, null);

            return new ProbeResult(true, hasRT, bestName, bestApi,
                hasRT ? null : "GPU 不支持 VK_KHR_ray_tracing_pipeline");

        } catch (Exception e) {
            return ProbeResult.fail("探测异常: " + e.getMessage());
        }
    }

    private static boolean hasRTExtensions(VkPhysicalDevice dev, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(dev, (String) null, count, null);
        VkExtensionProperties.Buffer exts = VkExtensionProperties.malloc(count.get(0), stack);
        vkEnumerateDeviceExtensionProperties(dev, (String) null, count, exts);

        List<String> names = new ArrayList<>();
        for (VkExtensionProperties e : exts) names.add(e.extensionNameString());

        return names.contains(VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME)
            && names.contains(VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME);
    }
}
