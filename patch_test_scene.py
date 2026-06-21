f = 'common/src/main/java/com/rtbridge/scene/SceneExtractor.java'
c = open(f).read()

old = '''    private void handleChunkLoad(ChunkPos pos) {
        if (pos == null) return;
        // Submit heavy tessellation to mesh pool; update Back buffer on completion
        meshPool.submit(() -> {
            // TODO: extract vertex data from chunk via Sodium/vanilla mesh
            long fakeBufHandle = pos.toLong(); // placeholder
            int  fakeVertCount = 0;

            SceneDatabase back = tripleBuffer.getBack();
            back.writeLock();
            try {
                back.staticGeometry().put(pos, fakeBufHandle, fakeVertCount);
            } finally {
                back.writeUnlock();
            }
            RTBridgeMod.LOGGER.debug("[SceneExtractor] Chunk loaded: {}", pos);
        });
    }'''

new = '''    // ── 临时测试场景：用硬编码顶点验证 RT 链路 ──────────────────────────────────
    // 一个 8x8x8 的立方体，放在世界原点附近
    // 每个三角形: 3 x vec3 = 9 floats = 36 bytes; 12 个三角形组成一个立方体
    private static final float[] TEST_CUBE_VERTS;
    static {
        float s = 4f; // 半边长 4 格
        TEST_CUBE_VERTS = new float[]{
            // -X face
            -s,-s,-s,  -s,-s, s,  -s, s, s,
            -s,-s,-s,  -s, s, s,  -s, s,-s,
            // +X face
             s,-s,-s,   s, s, s,   s,-s, s,
             s,-s,-s,   s, s,-s,   s, s, s,
            // -Y face
            -s,-s,-s,   s,-s,-s,   s,-s, s,
            -s,-s,-s,   s,-s, s,  -s,-s, s,
            // +Y face
            -s, s,-s,   s, s, s,   s, s,-s,
            -s, s,-s,  -s, s, s,   s, s, s,
            // -Z face
            -s,-s,-s,  -s, s,-s,   s, s,-s,
            -s,-s,-s,   s, s,-s,   s,-s,-s,
            // +Z face
            -s,-s, s,   s,-s, s,   s, s, s,
            -s,-s, s,   s, s, s,  -s, s, s,
        };
    }

    private static volatile long testBLASHandle = VK_NULL_HANDLE;
    private static volatile boolean testBLASBuilt = false;

    private void handleChunkLoad(ChunkPos pos) {
        if (pos == null) return;
        meshPool.submit(() -> {
            // 如果还没有测试场景，建一个临时 BLAS（验证 RT 链路）
            if (!testBLASBuilt) {
                synchronized (SceneExtractor.class) {
                    if (!testBLASBuilt) {
                        buildTestBLAS();
                        testBLASBuilt = true;
                    }
                }
            }

            SceneDatabase back = tripleBuffer.getBack();
            back.writeLock();
            try {
                // 用测试 BLAS 句柄（非零）填进去，让 TLAS 有内容
                if (testBLASHandle != VK_NULL_HANDLE) {
                    back.staticGeometry().put(pos, testBLASHandle, TEST_CUBE_VERTS.length / 3);
                } else {
                    back.staticGeometry().put(pos, pos.toLong(), 0);
                }
            } finally {
                back.writeUnlock();
            }
            RTBridgeMod.LOGGER.debug("[SceneExtractor] Chunk loaded: {} blasHandle=0x{}",
                pos, Long.toHexString(testBLASHandle));
        });
    }

    /** 构建一个测试 BLAS（硬编码立方体），验证 RT 光线可以命中几何体 */
    private void buildTestBLAS() {
        try {
            var rt = RTBridgeMod.getRTRenderer();
            if (rt == null) return;
            var ctx = rt.getVulkanContext();
            if (ctx == null) return;

            RTBridgeMod.LOGGER.info("[SceneExtractor] 构建测试 BLAS（硬编码立方体）...");

            // 上传顶点数据到 GPU
            var vbuf = com.rtbridge.vulkan.VulkanBuffer.stagingVertex(
                ctx.physDevice, ctx.device, TEST_CUBE_VERTS);

            // 构建 BLAS
            var blasBuilder = rt.getBLASBuilder();
            if (blasBuilder != null) {
                testBLASHandle = blasBuilder.buildBLAS(vbuf, TEST_CUBE_VERTS.length / 3);
                RTBridgeMod.LOGGER.info("[SceneExtractor] 测试 BLAS 就绪: 0x{}",
                    Long.toHexString(testBLASHandle));
            }
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.error("[SceneExtractor] 测试 BLAS 构建失败: {}", e.getMessage());
        }
    }

    private static final long VK_NULL_HANDLE = 0L;'''

c = c.replace(old, new)
open(f, 'w').write(c)
print("Patched:", old not in c)
