package com.rtbridge.render;

import com.rtbridge.RTBridgeMod;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * NormalReconstructPass — 从深度图重建世界空间法线。
 *
 * 算法：对深度图做有限差分，叉积得到法线。
 * 精度：近似，足够驱动 RT shadow。
 * 后续：Iris 接入后替换为真实 GBuffer 法线。
 */
public class NormalReconstructPass {

    private static int program  = -1;
    private static int vao      = -1;
    private static int uDepth, uNear, uFar, uFov, uAspect, uRes, uInvViewRot;

    private static final String VERT = """
        #version 330 core
        out vec2 vUV;
        void main() {
            vUV = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(vUV * 2.0 - 1.0, 0.0, 1.0);
        }
        """;

    private static final String FRAG = """
        #version 330 core
        in  vec2 vUV;
        out vec4 outNormal;

        uniform sampler2D u_Depth;
        uniform float u_Near, u_Far, u_Fov, u_Aspect;
        uniform vec2  u_Res;
        uniform mat3  u_InvViewRot; // 视空间→世界空间 旋转部分

        // 深度转线性
        float linearDepth(float d) {
            return (2.0 * u_Near * u_Far) /
                   (u_Far + u_Near - (d * 2.0 - 1.0) * (u_Far - u_Near));
        }

        // 深度+UV转视空间位置
        vec3 viewPos(vec2 uv) {
            float d  = linearDepth(texture(u_Depth, uv).r);
            float ty = tan(u_Fov * 0.5);
            float tx = ty * u_Aspect;
            return vec3((uv.x * 2.0 - 1.0) * tx * d,
                        (uv.y * 2.0 - 1.0) * ty * d,
                        -d);
        }

        void main() {
            vec2 px = 1.0 / u_Res;
            vec3 c  = viewPos(vUV);
            vec3 r  = viewPos(vUV + vec2(px.x, 0.0));
            vec3 u  = viewPos(vUV + vec2(0.0, px.y));
            vec3 N_view  = normalize(cross(r - c, u - c));
            vec3 N_world = normalize(u_InvViewRot * N_view); // 视空间→世界空间
            outNormal = vec4(N_world * 0.5 + 0.5, 1.0); // encode to [0,1]
        }
        """;

    public static void run(int depthTex, float near, float far,
                           float fovY, float aspect, int w, int h,
                           org.joml.Matrix3f invViewRot) {
        if (program < 0) init();
        if (program < 0) return;

        glUseProgram(program);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, depthTex);
        glUniform1i(uDepth,  0);
        glUniform1f(uNear,   near);
        glUniform1f(uFar,    far);
        glUniform1f(uFov,    fovY);
        glUniform1f(uAspect, aspect);
        glUniform2f(uRes,    w, h);

        float[] m = new float[9];
        invViewRot.get(m);
        glUniformMatrix3fv(uInvViewRot, false, m);

        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    private static void init() {
        try {
            int v = compile(GL_VERTEX_SHADER,   VERT);
            int f = compile(GL_FRAGMENT_SHADER, FRAG);
            program = glCreateProgram();
            glAttachShader(program, v);
            glAttachShader(program, f);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
                RTBridgeMod.LOGGER.error("[NormalRecon] 链接失败: {}",
                    glGetProgramInfoLog(program));
                program = -1; return;
            }
            glDeleteShader(v); glDeleteShader(f);

            uDepth  = glGetUniformLocation(program, "u_Depth");
            uNear   = glGetUniformLocation(program, "u_Near");
            uFar    = glGetUniformLocation(program, "u_Far");
            uFov    = glGetUniformLocation(program, "u_Fov");
            uAspect = glGetUniformLocation(program, "u_Aspect");
            uRes    = glGetUniformLocation(program, "u_Res");
            uInvViewRot = glGetUniformLocation(program, "u_InvViewRot");

            vao = glGenVertexArrays();
            RTBridgeMod.LOGGER.info("[NormalRecon] Shader 编译成功");
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.error("[NormalRecon] 初始化失败: {}", e.getMessage());
        }
    }

    private static int compile(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE)
            throw new RuntimeException(glGetShaderInfoLog(s));
        return s;
    }
}
