#version 450
// A triangle from the vertex index: no vertex buffers. `tint` 0 gives flat
// green (exact bytes to read back), 1 gives one color per corner.
layout(push_constant) uniform Push { float angle; float aspect; float tint; } push;
layout(location = 0) out vec3 color;

void main() {
    const vec2 corners[3] = vec2[3](vec2(0.0, -0.8), vec2(0.7, 0.6), vec2(-0.7, 0.6));
    const vec3 colors[3] = vec3[3](vec3(1.0, 0.0, 0.0), vec3(0.0, 1.0, 0.0), vec3(0.0, 0.0, 1.0));
    float c = cos(push.angle), s = sin(push.angle);
    vec2 p = corners[gl_VertexIndex];
    p = vec2(p.x * c - p.y * s, p.x * s + p.y * c);
    gl_Position = vec4(p.x * push.aspect, p.y, 0.0, 1.0);
    color = mix(vec3(0.0, 1.0, 0.0), colors[gl_VertexIndex], push.tint);
}
