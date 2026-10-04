package com.nova.autobuild;

import net.minecraft.block.BlockState;
import java.util.ArrayList;
import java.util.List;

/** Danh sách block tương đối so với góc nhỏ nhất (0,0,0) của schematic. */
public final class Schematic {
    public record Entry(int x, int y, int z, BlockState state) {}

    public final List<Entry> blocks = new ArrayList<>();
    public int sizeX, sizeY, sizeZ;
    public String name = "";
}
