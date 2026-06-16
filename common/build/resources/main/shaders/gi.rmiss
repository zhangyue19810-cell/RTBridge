#version 460
#extension GL_EXT_ray_tracing : require

layout(location = 0) rayPayloadInEXT vec4 giPayload;

layout(binding = 4, set = 0) uniform CameraUBO {
    mat4 invView; mat4 invProj;
    vec4 lightDir; vec4 cameraPos;
    float time; float nearPlane; float farPlane; float pad;
} cam;

void main() {
    vec3 dir = normalize(gl_WorldRayDirectionEXT);

    // 天空辐照度（GI miss = 从天空获得辐照）
    float sunDot  = max(dot(dir, -cam.lightDir.xyz), 0.0);
    float skyUp   = max(dir.y, 0.0);
    float horizon = pow(max(1.0 - abs(dir.y), 0.0), 3.0);

    // 天空颜色（蓝天 + 地平线橙红）
    vec3 skyZenith  = vec3(0.1, 0.2, 0.5);
    vec3 skyHorizon = vec3(0.5, 0.35, 0.2);
    vec3 sky = mix(skyZenith, skyHorizon, horizon);

    // 太阳直接贡献
    float sunSpot = smoothstep(0.998, 0.9995, sunDot);
    sky += vec3(1.4, 1.2, 0.9) * sunSpot;

    // 地面：深色（地面反射GI）
    vec3 ground = vec3(0.08, 0.06, 0.04);
    vec3 skyColor = dir.y >= 0.0 ? sky : ground;

    giPayload = vec4(skyColor, -1.0);
}
