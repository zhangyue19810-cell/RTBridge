f = 'common/src/main/java/com/rtbridge/scene/SceneExtractor.java'
c = open(f).read()

old = '''    // ── 臨時テストシーン：ハードコードされた頂点でRTチェーンを検証 ──────────────────────────
    // ...
    private static final long VK_NULL_HANDLE = 0L;'''

# 先看实际内容
idx = c.find('handleChunkLoad')
print(repr(c[idx:idx+200]))
