package com.tacz.guns.client.gui.overlay;

import com.tacz.guns.client.event.DamageNumberRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

public class DamageNumberOverlay implements IGuiOverlay {
    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int width, int height) {
        DamageNumberRenderer.renderFixed(graphics, width, height);
    }
}
