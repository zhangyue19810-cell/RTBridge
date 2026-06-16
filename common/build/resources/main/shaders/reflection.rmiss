#version 460
#extension GL_EXT_ray_tracing : require

layout(location = 0) rayPayloadInEXT vec4 hitResult;

layout(binding = 4, set = 0) uniform CameraUBO {
    mat4 invView; mat4 invProj;
    vec4 lightDir; vec4 cameraPos;
    float time; float nearPlane; float farPlane; float pad;
} cam;

void main() {
    vec3 dir = normalize(gl_WorldRayDirectionEXT);

    // 大气散射近似（Preetham 简化版）
    float sunDot = max(dot(dir, -cam.lightDir.xyz), 0.0);
    float horizon = pow(max(1.0 - abs(dir.y), 0.0), 4.0);

    vec3 zenith  = vec3(0.08, 0.15, 0.35);
    vec3 sky     = mix(zenith, vec3(0.55, 0.72, 0.95), horizon);

    // 太阳盘
    float sunDisc = smoothstep(0.9995, 0.9998, sunDot);
    sky += vec3(1.5, 1.3, 1.0) * sunDisc;

    // 夜晚星空（简化）
    if (dir.y > 0.0 && cam.lightDir.y > 0.0) {
        float starNoise = fract(sin(dot(dir.xz, vec2(127.1, 311.7))) * 43758.5);
        sky += vec3(starNoise > 0.998 ? (starNoise - 0.998) * 500.0 : 0.0);
    }

    hitResult = vec4(max(sky, 0.0), -1.0);
}
