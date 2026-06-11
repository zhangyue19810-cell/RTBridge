# RTBridge — Vulkan RT Bridge for Minecraft

> Minecraft 保留 OpenGL，另起一个 Vulkan RT 分支，靠事件驱动的 Scene Database、
> 异步 BVH、同帧合成和发光几何采样来兼容航空学与复杂动态光源。

---

## 架构总览

```
Minecraft World
      │
      ▼
DirtyEventSystem          ← 事件驱动，禁止每帧全量扫描
      │
      ▼
SceneExtractor            ← 后台线程池，主线程只做最小同步
      │
      ▼
SceneDatabase             ← Static / Dynamic / Transform / Material / Emissive
      │
   TripleBuffer            ← Front(GL读) | Middle(RT读) | Back(Extractor写)
      │
 ┌────┴─────────────────────────────────┐
 │                                      │
 ▼                                      ▼
OpenGL (untouched)              RT Renderer (Vulkan)
 Iris / Sodium / Voxy              AsyncBLASBuilder
                                   TLASInstanceBuffer
                                   LightCluster (Layer 1)
                                   ReservoirSampler (Layers 2-4)
                                   Shadow / Reflection / GI passes
 └────────────────────┬─────────────────┘
                      ▼
               CompositePass
     BaseColor × ShadowMask + GI + Reflection×Fresnel + Emission
                      ▼
                 Final Frame
```

---

## 包结构

| 包 | 职责 |
|----|------|
| `event/` | `DirtyEventSystem`、`DirtyEvent`、`DirtyEventType` |
| `scene/` | `SceneDatabase`、`SceneExtractor` |
| `scene/cache/` | `StaticGeometryCache`、`DynamicGeometryCache`、`TransformCache`、`MaterialCache`、`EmissiveCache` |
| `buffer/` | `TripleBuffer` |
| `bvh/` | `AsyncBLASBuilder`、`TLASInstanceBuffer` |
| `light/` | `LightCluster`、`ReservoirSampler` |
| `render/` | `RTRenderer`、`CompositePass`、`MotionVectorBuffer` |
| `compat/` | `ValkyrienSkiesCompat`、`IrisCompat`、`SodiumCompat` |
| `mixin/` | `MixinGameRenderer`、`MixinWorldRenderer`、`MixinLevelChunk` |

---

## 开发优先级（spec §12）

- [x] **P1** SceneDatabase + 所有 Cache 类
- [x] **P1** DirtyEventSystem + DirtyEvent
- [x] **P2** 航空学 ShipTransform 接入 (ValkyrienSkiesCompat)
- [x] **P2** EmissiveCache
- [x] **P3** AsyncBLASBuilder（stub, TODO: Vulkan）
- [x] **P3** TripleBuffer 同帧 Composite Pass
- [ ] **P4** RT Shadow pass（Vulkan 实现）
- [ ] **P5** RT Reflection pass
- [ ] **P6** GI + ReSTIR DI/GI 采样优化
- [ ] **P7** Motion Vector / Temporal Reprojection

---

## 关键设计规则

### ✅ 推荐
- 世界逻辑层提取（SceneExtractor 事件驱动）
- SceneDatabase 统一管理
- OpenGL 与 RT 双分支并行（OpenGL 不被接管）
- 异步构建加速结构（AsyncBLASBuilder）
- 同帧合成（CompositePass 在 `WorldRenderEvents.LAST`）
- 运动矢量作为补偿手段，不是主路径
- 发光几何体 + 光源聚类应对复杂室内照明

### ❌ 禁止
- 每帧全量扫描世界
- 同步 BLAS 重建（会卡主线程）
- 2 帧延迟作为主工作流
- 用 RT 完全替换 OpenGL 主画面

---

## 航空学飞艇规则

```
ShipCreate   → 立即注册占位 AABB Transform → 异步 BLAS 构建
ShipMove     → 仅更新 TLAS Instance Transform  ← 永远不重建 BLAS
ShipRotate   → 仅更新 TLAS Instance Transform  ← 永远不重建 BLAS
ShipModified → 队列 BLAS 重建（新旧 BLAS 共存直到完成）
ShipDestroy  → 移除 BLAS + TLAS instance
```

---

## Composite 公式

```glsl
FinalColor = (BaseColor * DirectLighting * ShadowMask)
           + GI
           + Reflection * Fresnel
           + Emission
```

- `ShadowMask` 是乘性衰减项（不是加法）
- `Reflection` 受 Schlick Fresnel 影响
- `GI` 是间接光贡献（加法）
- `Emission` 是自发光项（加法）

---

## TODO (Vulkan 实现清单)

- [ ] `RTRenderer.initVulkan()` — vkCreateInstance, 扩展检测
- [ ] `AsyncBLASBuilder.buildGeometryBLAS()` — vkCmdBuildAccelerationStructuresKHR (BLAS)
- [ ] `TLASInstanceBuffer` TLAS 实际构建 — vkCmdBuildAccelerationStructuresKHR (TLAS)
- [ ] `RTRenderer.dispatchShadowPass()` — vkCmdTraceRaysKHR shadow pipeline
- [ ] `RTRenderer.dispatchReflectionPass()` — vkCmdTraceRaysKHR reflection pipeline  
- [ ] `RTRenderer.dispatchGIPass()` — ReSTIR DI/GI pipeline
- [ ] `CompositePass.init()` — 编译 GLSL composite shader
- [ ] `MotionVectorBuffer` — compute shader reprojection
- [ ] `ValkyrienSkiesCompat.registerShipEvents()` — 接入真实 VS2 事件 API
- [ ] `SodiumCompat` — MixinSodiumChunkRegion VBO 捕获
- [ ] `MixinLevelChunk` — 区块加载时发光块扫描

---

## 依赖

| Mod | 版本 | 类型 |
|-----|------|------|
| Fabric Loader | ≥ 0.15.0 | 必须 |
| Fabric API | 0.92.0+ | 必须 |
| Valkyrien Skies 2 | 2.3.0-beta.5 | 可选（软依赖）|
| Sodium | mc1.20.1-0.5.8 | 可选（软依赖）|
| Iris | 任意 | 可选（软依赖）|
| Voxy | 任意 | 可选（软依赖）|
