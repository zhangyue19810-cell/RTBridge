f = 'common/src/main/java/com/rtbridge/vulkan/ExternalImage.java'
c = open(f).read()

# 把修补移到 exportToGL 一开始，去掉 patchApplied 守卫，直接无条件修补
old = '''            // 修补 LWJGL 函数指针（只做一次）
            if (!patchApplied) {
                patchApplied = patchGLFunctionPointers(caps);
            }
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.warn("[ExternalImage] GL 能力检查失败: {}", e.getMessage(), e);
            return false;
        }'''

new = '''            // 修补 LWJGL 函数指针（强制每次检查，确保生效）
            patchGLFunctionPointers(caps);
        } catch (Throwable e) {
            RTBridgeMod.LOGGER.warn("[ExternalImage] GL 能力检查失败: {}", e.getMessage(), e);
            return false;
        }'''

c = c.replace(old, new)
open(f, 'w').write(c)
print("Patched:", old not in c)
