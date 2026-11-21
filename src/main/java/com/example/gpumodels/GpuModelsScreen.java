package com.example.gpumodels;

import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The main GUI: a list of the models in run/gpumodels, a 3D preview (drag to rotate, scroll to zoom),
 * an animation picker for the preview, and buttons to apply the model to your player.
 */
public final class GpuModelsScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int ROW_H = 12;

    private final Screen parent;

    private List<String> files = new ArrayList<>();
    private boolean loadedOnce;
    private int selected = -1;
    private int listScroll;

    private LoadedModel preview;
    private String previewName;
    private String previewError;
    private int clip = -1; // -1 = rest pose
    private float yaw = 20f;
    private float pitch = 8f;
    private float zoom = 1f;
    private boolean dragging;
    private String status = "";

    // layout (GUI units)
    private int listX, listY, listW, listH;
    private int pvX, pvY, pvW, pvH;

    public GpuModelsScreen(Screen parent) {
        super(Component.literal("Cola's GPU models"));
        this.parent = parent;
    }

    // =========================== setup ===========================

    @Override
    protected void init() {
        listX = 10;
        listY = 26;
        listW = Math.min(170, width / 3);
        listH = Math.max(40, height - 92);
        pvX = listX + listW + 10;
        pvY = 26;
        pvW = Math.max(40, width - pvX - 10);
        pvH = Math.max(40, listH - 24);

        int bw = (width - 20 - 8) / 3;
        int x1 = 10;
        int x2 = 10 + bw + 4;
        int x3 = 10 + 2 * (bw + 4);
        int row1 = height - 50;
        int row2 = height - 26;
        addRenderableWidget(Button.builder(Component.literal("Apply to my player"), b -> apply())
                .bounds(x1, row1, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Remove from my player"), b -> remove())
                .bounds(x2, row1, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reload script"), b -> reloadScript())
                .bounds(x3, row1, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Open folder"), b -> openFolder())
                .bounds(x1, row2, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> {
                    closePreview();
                    refresh();
                    status = "List refreshed.";
                })
                .bounds(x2, row2, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(x3, row2, bw, 20).build());

        int animY = pvY + pvH + 4;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> stepClip(-1))
                .bounds(pvX, animY, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> stepClip(1))
                .bounds(pvX + pvW - 20, animY, 20, 20).build());

        if (!loadedOnce) {
            loadedOnce = true;
            refresh();
        }
    }

    private void refresh() {
        String keep = selectedName();
        if (keep == null) keep = ModelRenderer.appliedPlayerModelName();
        files = new ArrayList<>(ModelRenderer.listModelFiles());
        files.sort(String.CASE_INSENSITIVE_ORDER);
        selected = keep != null ? files.indexOf(keep) : -1;
        if (selected < 0 && !files.isEmpty()) selected = 0;
        ensurePreview();
    }

    private String selectedName() {
        return selected >= 0 && selected < files.size() ? files.get(selected) : null;
    }

    // =========================== preview model ===========================

    private void ensurePreview() {
        String name = selectedName();
        if (name == null) {
            closePreview();
            return;
        }
        if (preview != null && name.equals(previewName)) return;
        closePreview();
        previewName = name;
        previewError = null;
        clip = -1;
        Path file = ModelRenderer.resolveModelFile(name);
        if (file == null) {
            previewError = "File not found.";
            return;
        }
        try {
            preview = LoadedModel.load(file, ModelRenderer.modelDir());
            preview.setClip(-1);
        } catch (Throwable t) { // Throwable on purpose: also reports missing Assimp classes or natives
            LOGGER.error("[gpumodels] could not load the preview of " + name, t);
            previewError = "Could not load this model (see the log).";
            preview = null;
        }
    }

    private void closePreview() {
        if (preview != null) {
            preview.close();
            preview = null;
        }
        previewName = null;
    }

    private void stepClip(int delta) {
        if (preview == null) return;
        int n = preview.skeleton().clipCount();
        if (n == 0) return;
        clip += delta;
        if (clip < -1) clip = n - 1;
        if (clip > n - 1) clip = -1;
        preview.setClip(clip);
    }

    // =========================== buttons ===========================

    private void apply() {
        String name = selectedName();
        if (name == null) {
            status = "Select a model first.";
            return;
        }
        status = ModelRenderer.applyToLocalPlayer(name)
                ? "Applying " + name + " (it loads in the next frame)."
                : "File not found: " + name;
    }

    private void remove() {
        ModelRenderer.removeFromLocalPlayer();
        status = "Model removed from your player.";
    }

    private void reloadScript() {
        status = Scripting.reload() ? "Script reloaded." : "No model is applied to your player.";
    }

    private void openFolder() {
        Path dir = ModelRenderer.modelDir();
        try {
            Files.createDirectories(dir);
            Util.getPlatform().openFile(dir.toFile());
            status = "Opened " + dir;
        } catch (Exception e) {
            LOGGER.error("[gpumodels] could not open the folder", e);
            status = "Could not open the folder. It is: " + dir;
        }
    }

    // =========================== drawing ===========================

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick); // background and buttons
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);

        drawList(g);

        // preview panel
        g.fill(pvX, pvY, pvX + pvW, pvY + pvH, 0xAA101018);
        g.flush(); // everything drawn so far must reach the screen before we draw the 3D model
        if (preview != null) {
            double s = minecraft.getWindow().getGuiScale();
            int fullH = minecraft.getWindow().getHeight();
            int fx = (int) Math.round(pvX * s);
            int fw = (int) Math.round(pvW * s);
            int fh = (int) Math.round(pvH * s);
            int fy = fullH - (int) Math.round((pvY + pvH) * s);
            ModelRenderer.drawPreview(preview, fx, fy, fw, fh, yaw, pitch, zoom);
        }
        drawInfo(g);

        // animation picker label
        int animY = pvY + pvH + 4;
        String animText = preview == null ? "" : (clip < 0 || clip >= preview.skeleton().clipCount()
                ? "Rest pose"
                : preview.skeleton().clipNames().get(clip));
        animText = font.plainSubstrByWidth(animText, Math.max(10, pvW - 50));
        g.drawCenteredString(font, animText, pvX + pvW / 2, animY + 6, 0xFFFFFFFF);

        // status line
        if (!status.isEmpty()) {
            String shown = font.plainSubstrByWidth(status, width - 20);
            g.drawString(font, shown, 10, height - 62, 0xFFFFFF55, true);
        }
    }

    private void drawList(GuiGraphics g) {
        g.fill(listX, listY, listX + listW, listY + listH, 0xAA000000);
        int rows = Math.max(1, (listH - 4) / ROW_H);
        int maxScroll = Math.max(0, files.size() - rows);
        listScroll = Mth.clamp(listScroll, 0, maxScroll);
        String applied = ModelRenderer.appliedPlayerModelName();

        if (files.isEmpty()) {
            g.drawString(font, "No models found.", listX + 4, listY + 4, 0xFFAAAAAA, false);
            g.drawString(font, "Use \"Open folder\" and", listX + 4, listY + 16, 0xFFAAAAAA, false);
            g.drawString(font, "put .glb files there.", listX + 4, listY + 28, 0xFFAAAAAA, false);
            return;
        }
        for (int r = 0; r < rows; r++) {
            int i = listScroll + r;
            if (i >= files.size()) break;
            int ry = listY + 2 + r * ROW_H;
            if (i == selected) g.fill(listX + 1, ry - 1, listX + listW - 1, ry + ROW_H - 1, 0x66FFFFFF);
            String name = files.get(i);
            boolean isApplied = name.equals(applied);
            String text = font.plainSubstrByWidth(name, listW - 10);
            g.drawString(font, text, listX + 4, ry + 1, isApplied ? 0xFF55FF55 : 0xFFFFFFFF, false);
        }
    }

    private void drawInfo(GuiGraphics g) {
        List<String> lines = new ArrayList<>();
        int color = 0xFFFFFFFF;
        if (previewError != null) {
            lines.add(previewError);
            color = 0xFFFF5555;
        } else if (preview != null) {
            String name = preview.name();
            lines.add(name);
            lines.add(String.format(Locale.ROOT, "Size: %.2f wide, %.2f tall, %.2f deep (blocks)",
                    preview.width(), preview.height(), preview.depth()));
            lines.add(preview.gpu().partNames().size() + " parts, " + preview.skeleton().boneCount() + " bones, "
                    + preview.skeleton().clipCount() + " animations");
            lines.add(Scripting.scriptExists(name) ? "Script: found" : "Script: none");
            if (name.equals(ModelRenderer.appliedPlayerModelName())) lines.add("Applied to your player");
        } else {
            lines.add("Select a model on the left.");
        }
        int y = pvY + 4;
        for (String line : lines) {
            g.drawString(font, font.plainSubstrByWidth(line, pvW - 8), pvX + 4, y, color, true);
            y += 10;
        }
        if (preview != null) {
            g.drawString(font, "Drag: rotate   Scroll: zoom", pvX + 4, pvY + pvH - 12, 0xFFAAAAAA, true);
        }
    }

    // =========================== input ===========================

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (inside(mouseX, mouseY, listX, listY, listW, listH)) {
                int row = (int) ((mouseY - listY - 2) / ROW_H);
                int index = listScroll + row;
                if (row >= 0 && index < files.size()) {
                    selected = index;
                    ensurePreview();
                }
                return true;
            }
            if (inside(mouseX, mouseY, pvX, pvY, pvW, pvH)) {
                dragging = true;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && button == 0) {
            yaw += (float) dragX * 1.2f;
            pitch = Mth.clamp(pitch + (float) dragY * 0.8f, -80f, 80f);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inside(mouseX, mouseY, listX, listY, listW, listH)) {
            listScroll -= (int) Math.signum(scrollY);
            return true;
        }
        if (inside(mouseX, mouseY, pvX, pvY, pvW, pvH)) {
            zoom = Mth.clamp(zoom * (1f + 0.1f * (float) scrollY), 0.3f, 4f);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // =========================== closing ===========================

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        closePreview();
        super.removed();
    }
}
