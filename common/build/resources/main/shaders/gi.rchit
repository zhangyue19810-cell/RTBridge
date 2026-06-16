#version 460
#extension GL_EXT_ray_tracing : require

layout(location = 0) rayPayloadInEXT vec4 giPayload;

layout(binding = 4, set = 0) uniform CameraUBO {
    mat4 invView; mat4 invProj;
    vec4 lightDir; vec4 cameraPos;
    float time; float nearPlane; float farPlane; float pad;
} cam;

// 发光体颜色缓冲（SSBO，后续实现）
// 目前用简单直接光着色

void main() {
    float dist = gl_HitTEXT;

    // 命中表面的简单 Lambert 着色
    // TODO: 读取材质 albedo、发光属性
    // 占位：假设漫反射灰色
    vec3  surfaceNormal = vec3(0, 1, 0); // 后续从顶点属性读取
    float NdotL = max(dot(surfaceNormal, -cam.lightDir.xyz), 0.0);

    // 模拟彩色 MC 方块颜色（后续替换为真实材质）
    // 基于命中位置生成伪随机颜色（占位）
    vec3 hitPos = gl_WorldRayOriginEXT + gl_WorldRayDirectionEXT * dist;
    vec3 blockColor = vec3(
        fract(hitPos.x * 0.3 + 0.5),
        fract(hitPos.y * 0.3 + 0.3),
        fract(hitPos.z * 0.3 + 0.7)
    ) * 0.5 + 0.25;

    // 直接光 + 少量环境光
    vec3 directLight  = vec3(1.3, 1.2, 1.0) * NdotL;
    vec3 ambientLight = vec3(0.05, 0.07, 0.1);
    vec3 irradiance   = blockColor * (directLight + ambientLight);

    // 距离衰减（远处 GI 贡献小）
    irradiance *= exp(-dist * 0.03);

    giPayload = vec4(irradiance, dist);
}
