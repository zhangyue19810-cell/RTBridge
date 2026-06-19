import zipfile, struct
jar = '/data/data/com.termux/files/home/.gradle/caches/modules-2/files-2.1/org.lwjgl/lwjgl-vulkan/3.3.3/2d85b61672fa82ec5045979ac49c7940913eea50/lwjgl-vulkan-3.3.3.jar'
with zipfile.ZipFile(jar) as z:
    data = z.read('org/lwjgl/vulkan/KHRExternalMemoryWin32.class')

# 搜索所有整数常量
for i in range(0, len(data)-4):
    val = struct.unpack_from('>i', data, i)[0]
    if 0 < val <= 32:
        ctx = data[max(0,i-30):i+4]
        print(f'val={val:#x} ctx={ctx}')
