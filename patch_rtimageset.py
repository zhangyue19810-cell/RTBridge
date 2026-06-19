f = 'common/src/main/java/com/rtbridge/vulkan/RTImageSet.java'
c = open(f).read()

old = '''    public long giImage = VK_NULL_HANDLE;
    public long giMemory = VK_NULL_HANDLE;
    public long giView = VK_NULL_HANDLE;'''

new = '''    public long giImage = VK_NULL_HANDLE;
    public long giMemory = VK_NULL_HANDLE;
    public long giView = VK_NULL_HANDLE;

    public long gDepthImage = VK_NULL_HANDLE, gDepthMemory = VK_NULL_HANDLE, gDepthView = VK_NULL_HANDLE;
    public long gNormalImage = VK_NULL_HANDLE, gNormalMemory = VK_NULL_HANDLE, gNormalView = VK_NULL_HANDLE;

    public boolean gDepthEverWritten = false;
    public boolean gNormalEverWritten = false;

    public long gDepthUploadBuffer = VK_NULL_HANDLE, gDepthUploadMemory = VK_NULL_HANDLE;
    public long gDepthUploadPtr = 0L, gDepthUploadSize = 0L;

    public long gNormalUploadBuffer = VK_NULL_HANDLE, gNormalUploadMemory = VK_NULL_HANDLE;
    public long gNormalUploadPtr = 0L, gNormalUploadSize = 0L;'''

c = c.replace(old, new)

old2 = '''            RTBridgeMod.LOGGER.info("[RTImageSet] Allocated {}x{}", width, height);
        }
        allocateShadowStaging();
    }'''

new2 = '''            long[] gd = createImageWithUsage(VK_FORMAT_R32_SFLOAT, width, height,
                    VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, stack);
            gDepthImage = gd[0]; gDepthMemory = gd[1];
            gDepthView = createView(gDepthImage, VK_FORMAT_R32_SFLOAT, VK_IMAGE_ASPECT_COLOR_BIT, stack);

            long[] gn = createImageWithUsage(VK_FORMAT_R16G16B16A16_SFLOAT, width, height,
                    VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, stack);
            gNormalImage = gn[0]; gNormalMemory = gn[1];
            gNormalView = createView(gNormalImage, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_ASPECT_COLOR_BIT, stack);

            RTBridgeMod.LOGGER.info("[RTImageSet] Allocated {}x{}", width, height);
        }
        allocateShadowStaging();
        allocateGBufferUploadStaging();
    }

    private void allocateGBufferUploadStaging() {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            gDepthUploadSize  = (long) width * height * 4;
            gNormalUploadSize = (long) width * height * 8;

            long[] depthBuf = createHostStagingBuffer(gDepthUploadSize, stack);
            gDepthUploadBuffer = depthBuf[0]; gDepthUploadMemory = depthBuf[1];
            gDepthUploadPtr = mapPersistent(gDepthUploadMemory, gDepthUploadSize, stack);

            long[] normalBuf = createHostStagingBuffer(gNormalUploadSize, stack);
            gNormalUploadBuffer = normalBuf[0]; gNormalUploadMemory = normalBuf[1];
            gNormalUploadPtr = mapPersistent(gNormalUploadMemory, gNormalUploadSize, stack);

            RTBridgeMod.LOGGER.info("[RTImageSet] GBuffer 上传 staging 就绪 depth={}KB normal={}KB",
                gDepthUploadSize / 1024, gNormalUploadSize / 1024);
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.error("[RTImageSet] GBuffer staging 分配失败: {}", e.getMessage());
        }
    }

    private long[] createHostStagingBuffer(long size, org.lwjgl.system.MemoryStack stack) {
        java.nio.LongBuffer pBuf = stack.mallocLong(1);
        VulkanBuffer.check(vkCreateBuffer(ctx.device,
            VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE),
            null, pBuf), "vkCreateBuffer gBufferStaging");
        long buf = pBuf.get(0);

        VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
        vkGetBufferMemoryRequirements(ctx.device, buf, req);

        int memType = findMemType(req.memoryTypeBits(),
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, stack);

        java.nio.LongBuffer pMem = stack.mallocLong(1);
        VulkanBuffer.check(vkAllocateMemory(ctx.device,
            VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(req.size())
                .memoryTypeIndex(memType),
            null, pMem), "vkAllocateMemory gBufferStaging");
        long mem = pMem.get(0);

        vkBindBufferMemory(ctx.device, buf, mem, 0);
        return new long[]{buf, mem};
    }

    private long mapPersistent(long memory, long size, org.lwjgl.system.MemoryStack stack) {
        org.lwjgl.PointerBuffer pData = stack.mallocPointer(1);
        vkMapMemory(ctx.device, memory, 0, size, 0, pData);
        return pData.get(0);
    }'''

c = c.replace(old2, new2)

old3 = '''    private long[] createImage(int format, int w, int h, MemoryStack stack) {
        LongBuffer pImg = stack.mallocLong(1);

        VulkanBuffer.check(vkCreateImage(ctx.device,
                VkImageCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                        .imageType(VK_IMAGE_TYPE_2D)
                        .format(format)
                        .extent(e -> e.width(w).height(h).depth(1))
                        .mipLevels(1)
                        .arrayLayers(1)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(VK_IMAGE_USAGE_STORAGE_BIT
                                | VK_IMAGE_USAGE_SAMPLED_BIT
                                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED),
                null, pImg), "vkCreateImage");'''

new3 = '''    private long[] createImage(int format, int w, int h, MemoryStack stack) {
        return createImageWithUsage(format, w, h,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
            stack);
    }

    private long[] createImageWithUsage(int format, int w, int h, int usage, MemoryStack stack) {
        LongBuffer pImg = stack.mallocLong(1);

        VulkanBuffer.check(vkCreateImage(ctx.device,
                VkImageCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                        .imageType(VK_IMAGE_TYPE_2D)
                        .format(format)
                        .extent(e -> e.width(w).height(h).depth(1))
                        .mipLevels(1)
                        .arrayLayers(1)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(usage)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED),
                null, pImg), "vkCreateImage");'''

c = c.replace(old3, new3)

old4 = '''        if (shadowMemory != VK_NULL_HANDLE) vkFreeMemory(ctx.device, shadowMemory, null);
        if (reflectionMemory != VK_NULL_HANDLE) vkFreeMemory(ctx.device, reflectionMemory, null);
        if (giMemory != VK_NULL_HANDLE) vkFreeMemory(ctx.device, giMemory, null);
    }'''

new4 = '''        if (shadowMemory != VK_NULL_HANDLE) vkFreeMemory(ctx.device, shadowMemory, null);
        if (reflectionMemory != VK_NULL_HANDLE) vkFreeMemory(ctx.device, reflectionMemory, null);
        if (giMemory != VK_NULL_HANDLE) vkFreeMemory(ctx.device, giMemory, null);

        if (gDepthView != VK_NULL_HANDLE) vkDestroyImageView(ctx.device, gDepthView, null);
        if (gNormalView != VK_NULL_HANDLE) vkDestroyImageView(ctx.device, gNormalView, null);
        if (gDepthImage != VK_NULL_HANDLE) vkDestroyImage(ctx.device, gDepthImage, null);
        if (gNormalImage != VK_NULL_HANDLE) vkDestroyImage(ctx.device, gNormalImage, null);
        if (gDepthMemory != VK_NULL_HANDLE) vkFreeMemory(ctx.device, gDepthMemory, null);
        if (gNormalMemory != VK_NULL_HANDLE) vkFreeMemory(ctx.device, gNormalMemory, null);

        if (gDepthUploadBuffer != VK_NULL_HANDLE) vkDestroyBuffer(ctx.device, gDepthUploadBuffer, null);
        if (gNormalUploadBuffer != VK_NULL_HANDLE) vkDestroyBuffer(ctx.device, gNormalUploadBuffer, null);
        if (gDepthUploadMemory != VK_NULL_HANDLE) vkFreeMemory(ctx.device, gDepthUploadMemory, null);
        if (gNormalUploadMemory != VK_NULL_HANDLE) vkFreeMemory(ctx.device, gNormalUploadMemory, null);
    }'''

c = c.replace(old4, new4)

open(f, 'w').write(c)
print("Patched:", old not in c and old2 not in c and old3 not in c and old4 not in c)
