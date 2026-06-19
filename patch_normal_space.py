# 1. NormalReconstructPass 加 invView 旋转部分的 uniform，转换法线到世界空间
f = 'common/src/main/java/com/rtbridge/render/NormalReconstructPass.java'
c = open(f).read()

old = '''    private static int program  = -1;
    private static int vao      = -1;
    private static int uDepth, uNear, uFar, uFov, uAspect, uRes;'''

new = '''    private static int program  = -1;
    private static int vao      = -1;
    private static int uDepth, uNear, uFar, uFov, uAspect, uRes, uInvViewRot;'''

c = c.replace(old, new)

old2 = '''        uniform sampler2D u_Depth;
        uniform float u_Near, u_Far, u_Fov, u_Aspect;
        uniform vec2  u_Res;'''

new2 = '''        uniform sampler2D u_Depth;
        uniform float u_Near, u_Far, u_Fov, u_Aspect;
        uniform vec2  u_Res;
        uniform mat3  u_InvViewRot; // 视空间→世界空间 旋转部分'''

c = c.replace(old2, new2)

old3 = '''            vec3 N  = normalize(cross(r - c, u - c));
            outNormal = vec4(N * 0.5 + 0.5, 1.0); // encode to [0,1]'''

new3 = '''            vec3 N_view  = normalize(cross(r - c, u - c));
            vec3 N_world = normalize(u_InvViewRot * N_view); // 视空间→世界空间
            outNormal = vec4(N_world * 0.5 + 0.5, 1.0); // encode to [0,1]'''

c = c.replace(old3, new3)

old4 = '''    public static void run(int depthTex, float near, float far,
                           float fovY, float aspect, int w, int h) {
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

        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
        glUseProgram(0);
    }'''

new4 = '''    public static void run(int depthTex, float near, float far,
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
    }'''

c = c.replace(old4, new4)

old5 = '''            uRes    = glGetUniformLocation(program, "u_Res");'''
new5 = '''            uRes    = glGetUniformLocation(program, "u_Res");
            uInvViewRot = glGetUniformLocation(program, "u_InvViewRot");'''
c = c.replace(old5, new5)

open(f, 'w').write(c)
print("NormalReconstructPass patched:",
      old not in c, old2 not in c, old3 not in c, old4 not in c, old5 not in c)
