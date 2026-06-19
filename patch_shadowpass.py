f = 'common/src/main/java/com/rtbridge/render/ShadowPass.java'
c = open(f).read()

old = '''            descriptorSet = descPool.allocateAndWrite(
                pipeline.descriptorSetLayout,
                tlas.getTLASHandle(),
                images.shadowView,
                placeholderView, // depth  — 待 GL-Vulkan interop 实现后替换
                placeholderView, // normal — 待 GL-Vulkan interop 实现后替换
                sampler,
                cameraUBO.bufferHandle()
            );'''

new = '''            // 使用真实 GBuffer（深度/法线从 MC CPU 回读上传），不可用时回退占位图
            long depthV  = images.gDepthView  != VK_NULL_HANDLE ? images.gDepthView  : placeholderView;
            long normalV = images.gNormalView != VK_NULL_HANDLE ? images.gNormalView : placeholderView;

            descriptorSet = descPool.allocateAndWrite(
                pipeline.descriptorSetLayout,
                tlas.getTLASHandle(),
                images.shadowView,
                depthV,
                normalV,
                sampler,
                cameraUBO.bufferHandle()
            );'''

c = c.replace(old, new)

old2 = '''    public void dispatch(Matrix4f invView, Matrix4f invProj, Vector3f lightDir) {
        if (!ready || tlas.getTLASHandle() == VK_NULL_HANDLE) return;

        // 更新 CameraUBO
        cameraUBO.update(invView, invProj, lightDir);

        // 录制命令
        VkCommandBuffer cmd = cmdBuf.begin();

        // Barrier: shadow image UNDEFINED/SHADER_READ → GENERAL（可写入）
        imageBarrier(cmd,
            images.shadowImage,
            VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_GENERAL,
            0,
            VK_ACCESS_SHADER_WRITE_BIT,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);'''

new2 = '''    public void dispatch(Matrix4f invView, Matrix4f invProj, Vector3f lightDir) {
        if (!ready || tlas.getTLASHandle() == VK_NULL_HANDLE) return;

        // 更新 CameraUBO
        cameraUBO.update(invView, invProj, lightDir);

        // 录制命令
        VkCommandBuffer cmd = cmdBuf.begin();

        // ── 上传真实 GBuffer：staging buffer → gDepthImage/gNormalImage ──────────
        if (images.gDepthUploadPtr != 0 && images.gDepthImage != VK_NULL_HANDLE) {
            int depthOldLayout = images.gDepthEverWritten
                ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED;

            imageBarrier(cmd, images.gDepthImage,
                depthOldLayout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                images.gDepthEverWritten ? VK_ACCESS_SHADER_READ_BIT : 0,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                images.gDepthEverWritten ? VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                    .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
                region.imageSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.imageExtent().width(width).height(height).depth(1);
                vkCmdCopyBufferToImage(cmd, images.gDepthUploadBuffer, images.gDepthImage,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            }

            imageBarrier(cmd, images.gDepthImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);

            images.gDepthEverWritten = true;
        }

        if (images.gNormalUploadPtr != 0 && images.gNormalImage != VK_NULL_HANDLE) {
            int normalOldLayout = images.gNormalEverWritten
                ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED;

            imageBarrier(cmd, images.gNormalImage,
                normalOldLayout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                images.gNormalEverWritten ? VK_ACCESS_SHADER_READ_BIT : 0,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                images.gNormalEverWritten ? VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                    .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
                region.imageSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.imageExtent().width(width).height(height).depth(1);
                vkCmdCopyBufferToImage(cmd, images.gNormalUploadBuffer, images.gNormalImage,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            }

            imageBarrier(cmd, images.gNormalImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);

            images.gNormalEverWritten = true;
        }

        // Barrier: shadow image UNDEFINED/SHADER_READ → GENERAL（可写入）
        imageBarrier(cmd,
            images.shadowImage,
            VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_GENERAL,
            0,
            VK_ACCESS_SHADER_WRITE_BIT,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);'''

c = c.replace(old2, new2)

open(f, 'w').write(c)
print("Patched:", old not in c, old2 not in c)
