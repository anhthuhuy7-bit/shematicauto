package com.nova.autobuild;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Item;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Menu Auto Build: chọn schematic, tải, đặt gốc, bắt đầu/dừng, chỉnh tốc độ.
 * Chỉ dùng nút bấm (không override input) để ít phụ thuộc thay đổi API chuột/phím giữa các phiên bản.
 * Mở bằng phím (xem SchematicBuilderMod) hoặc từ menu Nova: mc.setScreen(new AutoBuildScreen()).
 */
public class AutoBuildScreen extends Screen {
    private static final double[] REACH = {2.0, 3.0, 4.0, 4.5};
    private static final int[] DELAY = {0, 1, 2, 4, 8};

    // nhớ lựa chọn khi đóng/mở lại menu
    private static String selected;
    private static int page;
    private static String message = "";

    private List<String> files = new ArrayList<>();
    private ButtonWidget toggleBtn;

    public AutoBuildScreen() { super(Text.literal("Auto Build")); }

    @Override public boolean shouldPause() { return false; }   // game vẫn chạy để xây

    @Override
    protected void init() {
        refreshFiles();
        int cx = width / 2, w = 300, left = cx - w / 2;
        int perPage = Math.max(3, (height - 170) / 22);
        int pages = Math.max(1, (files.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);

        int y = 30;
        for (int i = page * perPage; i < Math.min(files.size(), (page + 1) * perPage); i++) {
            String f = files.get(i);
            String label = f.equals(selected) ? "> " + f : f;
            addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> { selected = f; clearAndInit(); })
                    .dimensions(left, y, w, 20).build());
            y += 22;
        }
        y += 4;
        addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> { page = Math.max(0, page - 1); clearAndInit(); })
                .dimensions(left, y, 40, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Làm mới"), b -> { message = "Đã làm mới danh sách."; clearAndInit(); })
                .dimensions(cx - 50, y, 100, 20).build());
        int fp = pages;
        addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> { page = Math.min(fp - 1, page + 1); clearAndInit(); })
                .dimensions(left + w - 40, y, 40, 20).build());

        y += 26;
        addDrawableChild(ButtonWidget.builder(Text.literal("Tải"), b -> load())
                .dimensions(left, y, 96, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Đặt gốc tại đây"), b -> setOrigin())
                .dimensions(left + 102, y, 96, 20).build());
        toggleBtn = addDrawableChild(ButtonWidget.builder(toggleLabel(), b -> toggle())
                .dimensions(left + 204, y, 96, 20).build());

        y += 26;
        AutoBuilder ab = AutoBuilder.INSTANCE;
        addDrawableChild(ButtonWidget.builder(Text.literal("Tốc độ: " + ab.blocksPerTick), b -> {
            ab.blocksPerTick = ab.blocksPerTick % 8 + 1; b.setMessage(Text.literal("Tốc độ: " + ab.blocksPerTick));
        }).dimensions(left, y, 96, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Tầm: " + ab.reach), b -> {
            ab.reach = next(REACH, ab.reach); b.setMessage(Text.literal("Tầm: " + ab.reach));
        }).dimensions(left + 102, y, 96, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Trễ: " + ab.delayTicks), b -> {
            ab.delayTicks = next(DELAY, ab.delayTicks); b.setMessage(Text.literal("Trễ: " + ab.delayTicks));
        }).dimensions(left + 204, y, 96, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Đóng"), b -> close())
                .dimensions(cx - 50, height - 28, 100, 20).build());
    }

    // ---------------- hành động ----------------
    private void load() {
        if (selected == null) { message = "Chọn một schematic trước."; return; }
        var p = MinecraftClient.getInstance().player;
        if (p == null) return;
        try {
            Schematic s = SchematicLoader.load(SchematicBuilderMod.dir().resolve(selected));
            AutoBuilder.INSTANCE.load(s, p.getBlockPos());
            message = "Đã tải " + s.blocks.size() + " block (" + s.sizeX + "x" + s.sizeY + "x" + s.sizeZ + "). Gốc = chỗ bạn đứng.";
        } catch (Exception e) {
            message = "Lỗi: " + e.getMessage();
        }
    }

    private void setOrigin() {
        var p = MinecraftClient.getInstance().player;
        if (p == null || !AutoBuilder.INSTANCE.hasSchematic()) { message = "Chưa tải schematic."; return; }
        AutoBuilder.INSTANCE.setOrigin(p.getBlockPos());
        message = "Đã dời gốc tới vị trí hiện tại.";
    }

    private void toggle() {
        AutoBuilder ab = AutoBuilder.INSTANCE;
        if (ab.isRunning()) { ab.stop(); message = "Đã dừng."; }
        else if (!ab.hasSchematic()) { message = "Chưa tải schematic."; }
        else { ab.start(); message = "Đang xây..."; }
        toggleBtn.setMessage(toggleLabel());
    }

    private Text toggleLabel() { return Text.literal(AutoBuilder.INSTANCE.isRunning() ? "Dừng" : "Bắt đầu"); }

    private void refreshFiles() {
        files = new ArrayList<>();
        try (Stream<Path> s = Files.list(SchematicBuilderMod.dir())) {
            s.map(f -> f.getFileName().toString())
             .filter(n -> n.endsWith(".schem") || n.endsWith(".litematic"))
             .sorted(Comparator.comparing(String::toLowerCase))
             .forEach(files::add);
        } catch (Exception ignored) {}
    }

    private static double next(double[] arr, double cur) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == cur) return arr[(i + 1) % arr.length];
        return arr[0];
    }
    private static int next(int[] arr, int cur) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == cur) return arr[(i + 1) % arr.length];
        return arr[0];
    }

    // ---------------- vẽ ----------------
    @Override
    public void tick() {
        if (toggleBtn != null) toggleBtn.setMessage(toggleLabel());   // tự đổi nhãn khi xây xong
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = width / 2;
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Auto Build - Schematic"), cx, 12, 0xFFFFFFFF);
        if (files.isEmpty())
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Không có file .schem/.litematic trong thư mục schematics"), cx, 40, 0xFFFFAA00);

        AutoBuilder ab = AutoBuilder.INSTANCE;
        int y = height - 82;
        if (ab.hasSchematic()) {
            int done = ab.total() - ab.remaining();
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal(ab.schematic().name + "  " + done + "/" + ab.total()
                            + (ab.isRunning() ? "  [đang xây]" : "  [dừng]")), cx, y, 0xFF55FF55);
            y += 11;
            if (ab.isRunning() && !ab.missingItems().isEmpty()) {
                StringBuilder sb = new StringBuilder("Thiếu: ");
                int n = 0;
                for (Item it : ab.missingItems()) { if (n++ > 0) sb.append(", "); sb.append(it.getName().getString()); if (n == 4) { sb.append("..."); break; } }
                ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(sb.toString()), cx, y, 0xFFFF5555);
            }
        }
        if (!message.isEmpty())
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(message), cx, height - 44, 0xFFCCCCCC);
    }
}
