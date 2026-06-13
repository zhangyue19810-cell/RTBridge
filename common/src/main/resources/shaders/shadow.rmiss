#version 460
#extension GL_EXT_ray_tracing : require

layout(location = 0) rayPayloadInEXT float shadowed;

void main() {
    // Ray reached max distance without hitting anything → fully lit
    shadowed = 0.0;
}
