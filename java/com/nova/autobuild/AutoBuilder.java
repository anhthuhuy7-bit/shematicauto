package com.nova.autobuild;

import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Auto build từ schematic. Dùng độc lập (lệnh /build) hoặc gọi tick() từ module Nova.
 * Xây từng lớp từ dưới lên, mỗi block click vào một block đã có sẵn bên cạnh.
 */
public final class AutoBuilder {
    public static final AutoBuilder INSTANCE = new AutoBuilder();

    private record Target(BlockPos pos, BlockState state) {}

    // ---- cấu hình (nối vào setting của Nova) ----
    public double reach = 4.0;        // tối đa 4.5 ở survival
    public int delayTicks = 1;        // nghỉ giữa các lần đặt
    public int blocksPerTick = 1;
    public boolean useCreativeItems = true;

    private final List<Target> pending = new ArrayList<>();
    private final Map<BlockPos, Integer> tries = new HashMap<>();
    private final Set<Item> missing = new LinkedHashSet<>();
    private Schematic loaded;
    private BlockPos origin;
    private boolean running;
    private int cooldown, head, total, idleTicks;

    // thuộc tính ảnh hưởng tới việc "đặt đúng chưa"; các thuộc tính redstone/waterlogged/shape bỏ qua
    private static final List<Property<?>> CHECKED = List.of(
            Properties.FACING, Properties.HORIZONTAL_FACING, Properties.AXIS,
            Properties.BLOCK_HALF, Properties.SLAB_TYPE);

    // ---------------- API ----------------
    public void load(Schematic s, BlockPos origin) {
        this.loaded = s;
        this.origin = origin;
        rebuild();
    }

    public void setOrigin(BlockPos p) { this.origin = p; if (loaded != null) rebuild(); }
    public boolean isRunning() { return running; }
    public boolean hasSchematic() { return loaded != null; }
    public int remaining() { return Math.max(0, pending.size() - head); }
    public int total() { return total; }
    public Set<Item> missingItems() { return missing; }
    public Schematic schematic() { return loaded; }

    public void start() { if (loaded != null) { running = true; idleTicks = 0; } }
    public void stop() { running = false; }

    private void rebuild() {
        pending.clear(); tries.clear(); missing.clear(); head = 0;
        for (Schematic.Entry e : loaded.blocks) {
            if (skip(e.state())) continue;
            pending.add(new Target(origin.add(e.x(), e.y(), e.z()), e.state()));
        }
        // từ dưới lên, mỗi lớp quét theo z rồi x để luôn có block đỡ
        pending.sort(Comparator.<Target>comparingInt(t -> t.pos().getY())
                .thenComparingInt(t -> t.pos().getZ()).thenComparingInt(t -> t.pos().getX()));
        total = pending.size();
    }

    /** Các block không tự đặt được riêng lẻ (nửa trên cửa, đầu giường, nước...). */
    private static boolean skip(BlockState s) {
        if (s.isAir()) return true;
        if (s.getBlock() instanceof FluidBlock) return true;
        if (s.getBlock() instanceof PistonHeadBlock) return true;
        if (s.contains(Properties.DOUBLE_BLOCK_HALF) && s.get(Properties.DOUBLE_BLOCK_HALF) == net.minecraft.block.enums.DoubleBlockHalf.UPPER) return true;
        if (s.contains(Properties.BED_PART) && s.get(Properties.BED_PART) == net.minecraft.block.enums.BedPart.HEAD) return true;
        return s.getBlock().asItem() == Items.AIR;
    }

    // ---------------- tick ----------------
    public void tick(MinecraftClient mc) {
        if (!running) return;
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        ClientPlayerInteractionManager im = mc.interactionManager;
        if (p == null || w == null || im == null) return;
        if (mc.currentScreen != null && !(mc.currentScreen instanceof AutoBuildScreen)) return;
        if (cooldown > 0) { cooldown--; return; }

        missing.clear();
        Vec3d eye = p.getEyePos();
        double r2 = reach * reach;
        int placed = 0, scanned = 0;

        for (int i = head; i < pending.size() && scanned < 3000 && placed < blocksPerTick; i++, scanned++) {
            Target t = pending.get(i);
            if (tries.getOrDefault(t.pos(), 0) >= 5) { if (i == head) head++; continue; }
            BlockState cur = w.getBlockState(t.pos());
            if (matches(t.state(), cur)) { if (i == head) head++; continue; }
            if (!cur.isReplaceable()) continue;                       // đang bị block khác chiếm chỗ
            if (eye.squaredDistanceTo(Vec3d.ofCenter(t.pos())) > r2) continue;

            Item item = t.state().getBlock().asItem();
            BlockHitResult hit = findHit(w, t.pos(), t.state());
            if (hit == null) continue;                                // chưa có block đỡ -> đợi lớp dưới
            if (!ensureHeld(p, im, item)) { missing.add(item); continue; }

            if (place(p, im, hit, t.state())) {
                tries.merge(t.pos(), 1, Integer::sum);
                placed++;
            }
        }

        if (placed > 0) { cooldown = delayTicks; idleTicks = 0; return; }

        if (remaining() == 0) {
            running = false;
            p.sendMessage(Text.literal("[AutoBuild] Xây xong " + total + " block."), false);
        } else if (++idleTicks % 100 == 0) {
            String msg = missing.isEmpty()
                    ? "Không có block nào trong tầm với. Hãy đi gần công trình (còn " + remaining() + ")."
                    : "Thiếu vật liệu: " + missingNames();
            p.sendMessage(Text.literal("[AutoBuild] " + msg), false);
        }
    }

    private String missingNames() {
        StringBuilder sb = new StringBuilder();
        for (Item it : missing) { if (sb.length() > 0) sb.append(", "); sb.append(it.getName().getString()); }
        return sb.toString();
    }

    // ---------------- so khớp ----------------
    private static boolean matches(BlockState want, BlockState cur) {
        if (want.getBlock() != cur.getBlock()) return false;
        for (Property<?> pr : CHECKED)
            if (want.contains(pr) && !Objects.equals(want.get(pr), cur.get(pr))) return false;
        return true;
    }

    // ---------------- tìm mặt để click ----------------
    private static boolean isTop(BlockState s) {
        if (s.contains(Properties.BLOCK_HALF)) return s.get(Properties.BLOCK_HALF) == net.minecraft.block.enums.BlockHalf.TOP;
        if (s.contains(Properties.SLAB_TYPE)) return s.get(Properties.SLAB_TYPE) == net.minecraft.block.enums.SlabType.TOP;
        return false;
    }

    private BlockHitResult findHit(ClientWorld w, BlockPos pos, BlockState want) {
        boolean top = isTop(want);
        Direction.Axis axis = want.contains(Properties.AXIS) ? want.get(Properties.AXIS) : null;
        Direction[] order = top
                ? new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN}
                : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction d : order) {
            if (axis != null && d.getAxis() != axis) continue;        // cột/log: hướng trục phụ thuộc mặt click
            BlockPos nb = pos.offset(d);
            BlockState ns = w.getBlockState(nb);
            if (ns.isAir() || ns.isReplaceable() || ns.getBlock() instanceof FluidBlock) continue;
            if (interactive(ns)) continue;                            // tránh mở GUI/cửa/công tắc
            Direction face = d.getOpposite();
            double hx = nb.getX() + 0.5 + face.getOffsetX() * 0.5;
            double hy = nb.getY() + 0.5 + face.getOffsetY() * 0.5;
            double hz = nb.getZ() + 0.5 + face.getOffsetZ() * 0.5;
            if (face.getAxis().isHorizontal()) hy = nb.getY() + (top ? 0.75 : 0.25);
            return new BlockHitResult(new Vec3d(hx, hy, hz), face, nb, false);
        }
        return null;
    }

    private static boolean interactive(BlockState s) {
        Block b = s.getBlock();
        return s.hasBlockEntity() || b instanceof DoorBlock || b instanceof TrapdoorBlock
                || b instanceof FenceGateBlock || b instanceof ButtonBlock || b instanceof LeverBlock
                || b instanceof CraftingTableBlock || b instanceof AnvilBlock || b instanceof BedBlock
                || b instanceof NoteBlock || b instanceof RepeaterBlock || b instanceof ComparatorBlock;
    }

    // ---------------- hướng nhìn để ra đúng facing ----------------
    /** @return {yaw, pitch} hoặc null nếu không cần xoay. */
    private static float[] lookFor(BlockState s) {
        Direction look = null;
        Block b = s.getBlock();
        if (s.contains(Properties.FACING)) {                          // piston, observer, dispenser, dropper...
            look = s.get(Properties.FACING).getOpposite();
        } else if (s.contains(Properties.HORIZONTAL_FACING)) {
            Direction f = s.get(Properties.HORIZONTAL_FACING);
            boolean same = b instanceof StairsBlock || b instanceof FenceGateBlock
                    || b instanceof DoorBlock || b instanceof AnvilBlock;
            look = same ? f : f.getOpposite();                        // lò, rương, repeater... = ngược hướng người chơi
        }
        if (look == null) return null;
        return switch (look) {
            case UP -> new float[]{0f, -90f};
            case DOWN -> new float[]{0f, 90f};
            case SOUTH -> new float[]{0f, 0f};
            case WEST -> new float[]{90f, 0f};
            case NORTH -> new float[]{180f, 0f};
            case EAST -> new float[]{-90f, 0f};
        };
    }

    // ---------------- đặt block ----------------
    private boolean place(ClientPlayerEntity p, ClientPlayerInteractionManager im, BlockHitResult hit, BlockState want) {
        float[] look = lookFor(want);
        float oy = p.getYaw(), op = p.getPitch();
        if (look != null) {
            p.setYaw(look[0]); p.setPitch(look[1]);
            p.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(look[0], look[1], p.isOnGround(), p.horizontalCollision));
        }
        ActionResult res = im.interactBlock(p, Hand.MAIN_HAND, hit);
        boolean ok = res.isAccepted();
        if (ok) p.swingHand(Hand.MAIN_HAND);
        if (look != null) {
            p.setYaw(oy); p.setPitch(op);
            p.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(oy, op, p.isOnGround(), p.horizontalCollision));
        }
        return ok;
    }

    // ---------------- lấy vật liệu vào tay ----------------
    private boolean ensureHeld(ClientPlayerEntity p, ClientPlayerInteractionManager im, Item item) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 9; i++)
            if (inv.getStack(i).isOf(item)) { inv.setSelectedSlot(i); return true; }

        int sel = inv.getSelectedSlot();
        if (p.getAbilities().creativeMode && useCreativeItems) {
            im.clickCreativeStack(new ItemStack(item), 36 + sel);
            return true;
        }
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).isOf(item)) {
                im.clickSlot(p.playerScreenHandler.syncId, i, sel, SlotActionType.SWAP, p);
                return true;
            }
        }
        return false;
    }
}
