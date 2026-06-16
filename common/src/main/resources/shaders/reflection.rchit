#version 460
#extension GL_EXT_ray_tracing : require

layout(location = 0) rayPayloadInEXT vec4 hitResult;

// 实例自定义数据（材质 ID）
hitAttributeEXT vec2 attribs;

// 太阳光方向（与 shadow pass 一致）
layout(binding = 4, set = 0) uniform CameraUBO {
    mat4 invView;
    mat4 invProj;
    vec4 lightDir;
    vec4 cameraPos;
    float time;
    float nearPlane;
    float farPlane;
    float pad;
} cam;

void main() {
    // TODO: 从材质缓存读取 albedo
    // 目前用简单 Lambert 着色（命中距离越远越暗）
    float dist = gl_HitTEXT;
    float fade = exp(-dist * 0.01);

    // 简单 diffuse（后续替换为完整 PBR）
    vec3 hitNormal = vec3(0, 1, 0); // 占位，后续从顶点数据读取
    float NdotL    = max(dot(hitNormal, -cam.lightDir.xyz), 0.0);
    vec3  color    = vec3(0.5) * (NdotL * 0.8 + 0.2) * fade;

    hitResult = vec4(color, dist);
}
