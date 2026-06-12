package com.rtbridge.vulkan;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK12.*;

/**
 * CameraUBO — uploads invView, invProj, lightDir to a device-visible UBO.
 * Updated every frame before RT dispatch.
 *
 * Layout (std140, 144 bytes):
 *   mat4 invView   (64 bytes)
 *   mat4 invProj   (64 bytes)
 *   vec4 lightDir  (16 bytes)
 */
public class CameraUBO implements AutoCloseable {

    public static final int SIZE = 64 + 64 + 16; // 144 bytes

    private final VulkanContext ctx;
    private final VulkanBuffer  ubo;

    public CameraUBO(VulkanContext ctx) {
        this.ctx = ctx;
        ubo = new VulkanBuffer(ctx.device);
        ubo.alloc(ctx.physDevice, SIZE,
            VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    }

    public void update(Matrix4f invView, Matrix4f invProj, Vector3f lightDir) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer pp = stack.mallocPointer(1);
            vkMapMemory(ctx.device, ubo.memory, 0, SIZE, 0, pp);
            ByteBuffer buf = pp.getByteBuffer(0, SIZE);

            // invView (column-major float[16])
            float[] tmp = new float[16];
            invView.get(tmp);
            for (float f : tmp) buf.putFloat(f);

            // invProj
            invProj.get(tmp);
            for (float f : tmp) buf.putFloat(f);

            // lightDir (vec4, w = intensity)
            buf.putFloat(lightDir.x);
            buf.putFloat(lightDir.y);
            buf.putFloat(lightDir.z);
            buf.putFloat(1.0f);

            vkUnmapMemory(ctx.device, ubo.memory);
        }
    }

    public long bufferHandle() { return ubo.buffer; }

    @Override
    public void close() { ubo.close(); }
}
