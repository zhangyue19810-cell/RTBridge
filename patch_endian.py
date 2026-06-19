f = 'common/src/main/java/com/rtbridge/render/MCGBufferCapture.java'
c = open(f).read()

old = '''        depthReadback  = ByteBuffer.allocateDirect(w * h * 4);      // R32F
        normalReadback = ByteBuffer.allocateDirect(w * h * 4 * 2);  // RGBA16F (half=2byte*4ch)'''

new = '''        depthReadback  = ByteBuffer.allocateDirect(w * h * 4)
            .order(java.nio.ByteOrder.nativeOrder());      // R32F
        normalReadback = ByteBuffer.allocateDirect(w * h * 4 * 2)
            .order(java.nio.ByteOrder.nativeOrder());      // RGBA16F (half=2byte*4ch)'''

c = c.replace(old, new)
open(f, 'w').write(c)
print("Patched:", old not in c)
