package com.nova.autobuild;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

/** Mọi truy cập NBT nằm ở đây. Nếu mapping đổi API (1.21.5+ trả Optional), chỉ cần sửa file này. */
final class NbtUtil {
    private NbtUtil() {}
    static int i(NbtCompound c, String k)            { return c.getInt(k, 0); }
    static String str(NbtCompound c, String k)       { return c.getString(k, ""); }
    static NbtCompound compound(NbtCompound c, String k) { return c.getCompoundOrEmpty(k); }
    static NbtList list(NbtCompound c, String k)     { return c.getListOrEmpty(k); }
    static byte[] bytes(NbtCompound c, String k)     { return c.getByteArray(k).orElse(new byte[0]); }
    static long[] longs(NbtCompound c, String k)     { return c.getLongArray(k).orElse(new long[0]); }
}
