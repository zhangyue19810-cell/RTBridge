#version 460
#extension GL_EXT_ray_tracing : require

layout(location = 0) rayPayloadInEXT vec3 hitColor;

// Simple sky gradient on miss
void main() {
    vec3 dir = gl_WorldRayDirectionEXT;
    float t  = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
    hitColor = mix(vec3(0.5, 0.7, 1.0), vec3(0.1, 0.2, 0.5), t);
}
