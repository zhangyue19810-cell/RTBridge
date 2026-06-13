#version 460
#extension GL_EXT_ray_tracing : require
layout(location = 0) rayPayloadInEXT vec3 irradiance;
void main() {
    // TODO: read material albedo + shade with direct light
    irradiance = vec3(0.3);
}
