package com.rtbridge.vulkan;

import com.rtbridge.RTBridgeMod;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/**
 * RTPipeline — creates a VkPipeline for one RT pass (shadow / reflection / GI).
 *
 * Shader stages:
 *   stage 0 : RAYGEN   (.rgen.spv)
 *   stage 1 : MISS     (.rmiss.spv)
 *   stage 2 : ANY_HIT  (.rahit.spv)   optional
 *
 * Shader groups:
 *   group 0 : GENERAL        → raygen
 *   group 1 : GENERAL        → miss
 *   group 2 : TRIANGLES/AABB → any-hit (no closest-hit for shadow)
 */
public class RTPipeline implements AutoCloseable {

    public final VulkanContext ctx;

    public long pipeline       = VK_NULL_HANDLE;
    public long pipelineLayout = VK_NULL_HANDLE;
    public long descriptorSetLayout = VK_NULL_HANDLE;

    // SBT group count
    public static final int GROUP_RAYGEN  = 0;
    public static final int GROUP_MISS    = 1;
    public static final int GROUP_ANYHIT  = 2;
    public static final int GROUP_COUNT   = 3;

    public RTPipeline(VulkanContext ctx) {
        this.ctx = ctx;
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    public boolean build(String passName) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!createDescriptorSetLayout(stack)) return false;
            if (!createPipelineLayout(stack))      return false;
            if (!createPipeline(passName, stack))  return false;
            RTBridgeMod.LOGGER.info("[RTPipeline] {} pipeline created", passName);
            return true;
        } catch (Exception e) {
            RTBridgeMod.LOGGER.error("[RTPipeline] Build failed for {}", passName, e);
            return false;
        }
    }

    // ── Descriptor set layout ─────────────────────────────────────────────────

    private boolean createDescriptorSetLayout(MemoryStack stack) {
        // Bindings:
        //   0 = TLAS                (ACCELERATION_STRUCTURE)
        //   1 = shadow output image (STORAGE_IMAGE)
        //   2 = depth sampler       (COMBINED_IMAGE_SAMPLER)
        //   3 = normal sampler      (COMBINED_IMAGE_SAMPLER)
        //   4 = camera UBO          (UNIFORM_BUFFER)
        VkDescriptorSetLayoutBinding.Buffer bindings =
            VkDescriptorSetLayoutBinding.calloc(5, stack);

        bindings.get(0)
            .binding(0)
            .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
            .descriptorCount(1)
            .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);

        bindings.get(1)
            .binding(1)
            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            .descriptorCount(1)
            .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);

        bindings.get(2)
            .binding(2)
            .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .descriptorCount(1)
            .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);

        bindings.get(3)
            .binding(3)
            .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .descriptorCount(1)
            .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);

        bindings.get(4)
            .binding(4)
            .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
            .descriptorCount(1)
            .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);

        LongBuffer pLayout = stack.mallocLong(1);
        int res = vkCreateDescriptorSetLayout(ctx.device,
            VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(bindings),
            null, pLayout);
        if (res != VK_SUCCESS) return false;
        descriptorSetLayout = pLayout.get(0);
        return true;
    }

    // ── Pipeline layout ───────────────────────────────────────────────────────

    private boolean createPipelineLayout(MemoryStack stack) {
        LongBuffer pLayouts = stack.longs(descriptorSetLayout);
        LongBuffer pPipeLayout = stack.mallocLong(1);
        int res = vkCreatePipelineLayout(ctx.device,
            VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(pLayouts),
            null, pPipeLayout);
        if (res != VK_SUCCESS) return false;
        pipelineLayout = pPipeLayout.get(0);
        return true;
    }

    // ── RT pipeline ───────────────────────────────────────────────────────────

    private boolean createPipeline(String passName, MemoryStack stack) throws IOException {
        // Load SPIR-V modules
        long rgenModule  = loadShader(passName + ".rgen.spv", stack);
        long rmissModule = loadShader(passName + ".rmiss.spv", stack);
        long rahitModule = loadShader(passName + ".rahit.spv", stack);

        // Shader stages
        VkPipelineShaderStageCreateInfo.Buffer stages =
            VkPipelineShaderStageCreateInfo.calloc(3, stack);

        stages.get(0)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
            .stage(VK_SHADER_STAGE_RAYGEN_BIT_KHR)
            .module(rgenModule)
            .pName(stack.UTF8("main"));

        stages.get(1)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
            .stage(VK_SHADER_STAGE_MISS_BIT_KHR)
            .module(rmissModule)
            .pName(stack.UTF8("main"));

        stages.get(2)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
            .stage(VK_SHADER_STAGE_ANY_HIT_BIT_KHR)
            .module(rahitModule)
            .pName(stack.UTF8("main"));

        // Shader groups
        VkRayTracingShaderGroupCreateInfoKHR.Buffer groups =
            VkRayTracingShaderGroupCreateInfoKHR.calloc(GROUP_COUNT, stack);

        // Group 0: raygen (GENERAL)
        groups.get(GROUP_RAYGEN)
            .sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
            .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
            .generalShader(0)
            .closestHitShader(VK_SHADER_UNUSED_KHR)
            .anyHitShader(VK_SHADER_UNUSED_KHR)
            .intersectionShader(VK_SHADER_UNUSED_KHR);

        // Group 1: miss (GENERAL)
        groups.get(GROUP_MISS)
            .sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
            .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
            .generalShader(1)
            .closestHitShader(VK_SHADER_UNUSED_KHR)
            .anyHitShader(VK_SHADER_UNUSED_KHR)
            .intersectionShader(VK_SHADER_UNUSED_KHR);

        // Group 2: any-hit (TRIANGLES hit group, no closest-hit)
        groups.get(GROUP_ANYHIT)
            .sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
            .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
            .generalShader(VK_SHADER_UNUSED_KHR)
            .closestHitShader(VK_SHADER_UNUSED_KHR)
            .anyHitShader(2)
            .intersectionShader(VK_SHADER_UNUSED_KHR);

        // Create RT pipeline
        VkRayTracingPipelineCreateInfoKHR.Buffer pipeCI =
            VkRayTracingPipelineCreateInfoKHR.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_RAY_TRACING_PIPELINE_CREATE_INFO_KHR)
                .pStages(stages)
                .pGroups(groups)
                .maxPipelineRayRecursionDepth(1) // shadow = no recursion needed
                .layout(pipelineLayout);

        LongBuffer pPipe = stack.mallocLong(1);
        int res = vkCreateRayTracingPipelinesKHR(ctx.device,
            VK_NULL_HANDLE, VK_NULL_HANDLE, pipeCI, null, pPipe);

        // Cleanup shader modules
        vkDestroyShaderModule(ctx.device, rgenModule,  null);
        vkDestroyShaderModule(ctx.device, rmissModule, null);
        vkDestroyShaderModule(ctx.device, rahitModule, null);

        if (res != VK_SUCCESS) return false;
        pipeline = pPipe.get(0);
        return true;
    }

    // ── SPIR-V loader ─────────────────────────────────────────────────────────

    private long loadShader(String spvName, MemoryStack stack) throws IOException {
        InputStream is = RTPipeline.class.getClassLoader()
            .getResourceAsStream("shaders/" + spvName);
        if (is == null) throw new IOException("Shader not found: shaders/" + spvName);

        byte[] bytes = is.readAllBytes();
        ByteBuffer spv = stack.malloc(bytes.length);
        spv.put(bytes).flip();

        LongBuffer pModule = stack.mallocLong(1);
        VulkanBuffer.check(vkCreateShaderModule(ctx.device,
            VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(spv),
            null, pModule), "vkCreateShaderModule " + spvName);
        return pModule.get(0);
    }

    @Override
    public void close() {
        if (pipeline            != VK_NULL_HANDLE) vkDestroyPipeline(ctx.device, pipeline, null);
        if (pipelineLayout      != VK_NULL_HANDLE) vkDestroyPipelineLayout(ctx.device, pipelineLayout, null);
        if (descriptorSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(ctx.device, descriptorSetLayout, null);
    }
}
