package com.nova.autobuild;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Đọc .schem (Sponge v2/v3) và .litematic (Litematica). */
public final class SchematicLoader {
    private SchematicLoader() {}

    public static Schematic load(Path file) throws IOException {
        String n = file.getFileName().toString().toLowerCase();
        NbtCompound root;
        try (InputStream in = Files.newInputStream(file)) {
            root = NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
        }
        Schematic s;
        if (n.endsWith(".litematic")) s = litematic(root);
        else if (n.endsWith(".schem")) s = sponge(root);
        else throw new IOException("Chỉ hỗ trợ .schem và .litematic");
        s.name = file.getFileName().toString();
        return s;
    }

    // ---------------- Sponge .schem ----------------
    private static Schematic sponge(NbtCompound root) {
        NbtCompound s = root.contains("Schematic") ? NbtUtil.compound(root, "Schematic") : root;
        int w = NbtUtil.i(s, "Width"), h = NbtUtil.i(s, "Height"), l = NbtUtil.i(s, "Length");
        NbtCompound palC;
        byte[] data;
        if (s.contains("Blocks")) { // v3
            NbtCompound b = NbtUtil.compound(s, "Blocks");
            palC = NbtUtil.compound(b, "Palette");
            data = NbtUtil.bytes(b, "Data");
        } else {                    // v2
            palC = NbtUtil.compound(s, "Palette");
            data = NbtUtil.bytes(s, "BlockData");
        }
        int max = 0;
        for (String k : palC.keySet()) max = Math.max(max, NbtUtil.i(palC, k));
        BlockState[] pal = new BlockState[max + 1];
        for (String k : palC.keySet()) pal[NbtUtil.i(palC, k)] = parseState(k);

        Schematic out = new Schematic();
        out.sizeX = w; out.sizeY = h; out.sizeZ = l;
        int idx = 0, i = 0;
        while (i < data.length) {
            int v = 0, shift = 0; byte b;
            do { b = data[i++]; v |= (b & 0x7F) << shift; shift += 7; } while ((b & 0x80) != 0 && i < data.length);
            int x = idx % w, z = (idx / w) % l, y = idx / (w * l);
            idx++;
            BlockState st = v < pal.length ? pal[v] : null;
            if (st != null && !st.isAir()) out.blocks.add(new Schematic.Entry(x, y, z, st));
        }
        return out;
    }

    // ---------------- Litematica ----------------
    private static Schematic litematic(NbtCompound root) {
        NbtCompound regions = NbtUtil.compound(root, "Regions");
        Schematic out = new Schematic();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (String key : regions.keySet()) {
            NbtCompound r = NbtUtil.compound(regions, key);
            NbtCompound pos = NbtUtil.compound(r, "Position"), size = NbtUtil.compound(r, "Size");
            int px = NbtUtil.i(pos, "x"), py = NbtUtil.i(pos, "y"), pz = NbtUtil.i(pos, "z");
            int sx = NbtUtil.i(size, "x"), sy = NbtUtil.i(size, "y"), sz = NbtUtil.i(size, "z");
            int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
            int ox = sx < 0 ? px + sx + 1 : px, oy = sy < 0 ? py + sy + 1 : py, oz = sz < 0 ? pz + sz + 1 : pz;

            NbtList pl = NbtUtil.list(r, "BlockStatePalette");
            List<BlockState> palette = new ArrayList<>();
            for (int i = 0; i < pl.size(); i++) {
                NbtCompound e = pl.getCompoundOrEmpty(i);
                palette.add(stateFrom(NbtUtil.str(e, "Name"), NbtUtil.compound(e, "Properties")));
            }
            if (palette.isEmpty()) continue;
            long[] arr = NbtUtil.longs(r, "BlockStates");
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            long mask = (1L << bits) - 1;

            for (int y = 0; y < ay; y++) for (int z = 0; z < az; z++) for (int x = 0; x < ax; x++) {
                long index = ((long) y * az + z) * ax + x;
                int id = (int) readBits(arr, index, bits, mask);
                if (id >= palette.size()) continue;
                BlockState st = palette.get(id);
                if (st.isAir()) continue;
                int wx = ox + x, wy = oy + y, wz = oz + z;
                out.blocks.add(new Schematic.Entry(wx, wy, wz, st));
                minX = Math.min(minX, wx); minY = Math.min(minY, wy); minZ = Math.min(minZ, wz);
                maxX = Math.max(maxX, wx); maxY = Math.max(maxY, wy); maxZ = Math.max(maxZ, wz);
            }
        }
        if (out.blocks.isEmpty()) return out;
        List<Schematic.Entry> norm = new ArrayList<>(out.blocks.size());
        for (Schematic.Entry e : out.blocks)
            norm.add(new Schematic.Entry(e.x() - minX, e.y() - minY, e.z() - minZ, e.state()));
        out.blocks.clear();
        out.blocks.addAll(norm);
        out.sizeX = maxX - minX + 1; out.sizeY = maxY - minY + 1; out.sizeZ = maxZ - minZ + 1;
        return out;
    }

    private static long readBits(long[] arr, long index, int bits, long mask) {
        long start = index * bits;
        int a = (int) (start >>> 6);
        int b = (int) (((index + 1) * bits - 1) >>> 6);
        int off = (int) (start & 63);
        if (a >= arr.length) return 0;
        if (a == b) return (arr[a] >>> off) & mask;
        int endOff = 64 - off;
        long hi = b < arr.length ? arr[b] : 0;
        return ((arr[a] >>> off) | (hi << endOff)) & mask;
    }

    // ---------------- BlockState parsing ----------------
    /** "minecraft:oak_stairs[facing=north,half=bottom]" */
    static BlockState parseState(String s) {
        String name = s, props = "";
        int br = s.indexOf('[');
        if (br >= 0) { name = s.substring(0, br); props = s.substring(br + 1, s.length() - 1); }
        Block block = block(name);
        BlockState st = block.getDefaultState();
        if (!props.isEmpty()) for (String kv : props.split(",")) {
            int eq = kv.indexOf('=');
            if (eq > 0) st = apply(st, kv.substring(0, eq), kv.substring(eq + 1));
        }
        return st;
    }

    private static BlockState stateFrom(String name, NbtCompound props) {
        BlockState st = block(name).getDefaultState();
        for (String k : props.keySet()) st = apply(st, k, NbtUtil.str(props, k));
        return st;
    }

    private static Block block(String name) {
        try { return Registries.BLOCK.get(Identifier.of(name)); }
        catch (Exception e) { return net.minecraft.block.Blocks.AIR; }
    }

    private static BlockState apply(BlockState st, String key, String value) {
        Property<?> p = st.getBlock().getStateManager().getProperty(key);
        return p == null ? st : with(st, p, value);
    }

    private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, String v) {
        return p.parse(v).map(val -> s.with(p, val)).orElse(s);
    }
}
