f = 'common/src/main/java/com/rtbridge/render/MCGBufferCapture.java'
c = open(f).read()

old = '''    public void captureFromDepth(float projNear, float projFar, float fovY, float aspectRatio) {'''
new = '''    public void captureFromDepth(float projNear, float projFar, float fovY, float aspectRatio,
                                  org.joml.Matrix3f invViewRot) {'''
c = c.replace(old, new)

old2 = '''        NormalReconstructPass.run(depthTexId, projNear, projFar, fovY, aspectRatio, width, height);'''
new2 = '''        NormalReconstructPass.run(depthTexId, projNear, projFar, fovY, aspectRatio, width, height, invViewRot);'''
c = c.replace(old2, new2)

open(f, 'w').write(c)
print("MCGBufferCapture patched:", old not in c, old2 not in c)
