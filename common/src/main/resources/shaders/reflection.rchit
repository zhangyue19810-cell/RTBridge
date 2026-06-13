#version 460
#extension GL_EXT_ray_tracing : require

layout(location = 0) rayPayloadInEXT vec3 hitColor;

// Placeholder: shade hit surface with a simple diffuse colour
// Real implementation: read material cache, sample albedo texture
void main() {
    // TODO: read material from instance custom index
    //       apply simple Lambertian shading
    hitColor = vec3(0.4, 0.4, 0.4); // mid-grey placeholder
}
