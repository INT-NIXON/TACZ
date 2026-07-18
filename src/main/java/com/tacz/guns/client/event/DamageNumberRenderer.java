package com.tacz.guns.client.event;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.GunMod;
import com.tacz.guns.config.client.DamageNumberStyle;
import com.tacz.guns.config.client.RenderConfig;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = GunMod.MOD_ID)
public final class DamageNumberRenderer {
    private static final int MAX_DAMAGE_NUMBERS = 15;
    private static final long AGGREGATION_TICKS = 2L;
    private static final long SHOT_LIFETIME_MS = 1_200L;
    private static final long TOTAL_LIFETIME_MS = 2_000L;
    private static final long FADE_IN_MS = 160L;
    private static final long FADE_OUT_DURATION_MS = 550L;
    private static final long FIXED_HEADSHOT_COLOR_FADE_MS = 1_000L;
    private static final long FIXED_HOLD_MS = 1_000L;
    private static final long FIXED_FADE_IN_MS = 250L;
    private static final long FIXED_FADE_OUT_MS = 1_000L;
    private static final long FIXED_EXPIRE_MS = FIXED_HOLD_MS + FIXED_FADE_OUT_MS + 100L;
    private static final double REFERENCE_DEPTH = 8.0D;
    private static final double MINIMUM_DEPTH = 0.75D;
    private static final float BASE_WORLD_SCALE = 0.0325F;
    private static final float FIXED_SCALE = 1.6F;
    private static final float FIXED_Y_OFFSET = 64.0F;
    private static final DecimalFormat DAMAGE_FORMAT = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final Map<DamageKey, PendingDamage> PENDING = new LinkedHashMap<>();
    private static final Map<DamageKey, DamageNumber> ACTIVE = new LinkedHashMap<>();
    private static final Map<Long, PendingDamage> FIXED_PENDING = new LinkedHashMap<>();
    private static FixedDamage fixedDamage;
    private static ClientLevel trackedLevel;
    private static DamageNumberStyle trackedStyle;
    private static boolean trackedAccumulate;

    private DamageNumberRenderer() {
    }

    public static void addDamage(long shotId, int targetId, float amount, boolean headshot, boolean melee) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (!RenderConfig.DAMAGE_NUMBER_ENABLE.get() || level == null || minecraft.player == null
                || !Float.isFinite(amount) || amount <= 0.0F) {
            return;
        }
        if (melee && !RenderConfig.GUN_MELEE_DAMAGE_NUMBER_ENABLE.get()) {
            return;
        }
        ensureContext(level);
        Entity target = level.getEntity(targetId);
        if (!(target instanceof LivingEntity) || !isWithinRenderDistance(minecraft.player, target)) {
            return;
        }

        long now = Util.getMillis();
        if (trackedStyle == DamageNumberStyle.FIXED) {
            addFixedDamage(shotId, amount, headshot, level.getGameTime(), now);
            return;
        }

        DamageKey shotKey = new DamageKey(shotId, targetId);
        DamageKey displayKey = trackedAccumulate ? new DamageKey(0L, targetId) : shotKey;
        DamageNumber active = ACTIVE.get(displayKey);
        if (active != null) {
            if (active.isExpired(now)) {
                ACTIVE.remove(displayKey);
            } else {
                active.add(amount, headshot, shotId, now);
                return;
            }
        }
        PendingDamage pending = PENDING.get(shotKey);
        if (pending != null) {
            pending.add(amount, headshot, level.getGameTime());
            return;
        }
        if (ACTIVE.size() + PENDING.size() >= MAX_DAMAGE_NUMBERS) {
            return;
        }
        PENDING.put(shotKey, new PendingDamage(amount, headshot, level.getGameTime()));
    }

    private static void addFixedDamage(long shotId, float amount, boolean headshot, long gameTime, long now) {
        if (fixedDamage != null && fixedDamage.isExpired(now)) {
            fixedDamage = null;
        }
        if (trackedAccumulate) {
            if (fixedDamage == null) {
                fixedDamage = new FixedDamage(0L, amount, headshot, shotId, now);
            } else {
                fixedDamage.add(amount, headshot, shotId, now);
            }
            return;
        }
        if (fixedDamage != null) {
            if (fixedDamage.shotId == shotId) {
                fixedDamage.add(amount, headshot, shotId, now);
                return;
            }
            if (shotId < fixedDamage.shotId) {
                return;
            }
        }
        PendingDamage pending = FIXED_PENDING.get(shotId);
        if (pending != null) {
            pending.add(amount, headshot, gameTime);
        } else if (FIXED_PENDING.size() < MAX_DAMAGE_NUMBERS) {
            FIXED_PENDING.put(shotId, new PendingDamage(amount, headshot, gameTime));
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null || !RenderConfig.DAMAGE_NUMBER_ENABLE.get()) {
            clear(level, RenderConfig.DAMAGE_NUMBER_STYLE.get(), RenderConfig.DAMAGE_NUMBER_ACCUMULATE.get());
            return;
        }
        ensureContext(level);

        long gameTime = level.getGameTime();
        long now = Util.getMillis();
        if (trackedStyle == DamageNumberStyle.FLOATING) {
            flushFloatingDamage(minecraft.player, level, gameTime, now);
        } else if (!trackedAccumulate) {
            flushFixedDamage(gameTime, now);
        }

        ACTIVE.entrySet().removeIf(entry -> {
            Entity target = level.getEntity(entry.getKey().targetId);
            return entry.getValue().isExpired(now)
                    || !(target instanceof LivingEntity)
                    || !isWithinRenderDistance(minecraft.player, target);
        });
        if (fixedDamage != null && fixedDamage.isExpired(now)) {
            fixedDamage = null;
        }
    }

    private static void flushFloatingDamage(LocalPlayer player, ClientLevel level, long gameTime, long now) {
        Iterator<Map.Entry<DamageKey, PendingDamage>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<DamageKey, PendingDamage> entry = iterator.next();
            PendingDamage pending = entry.getValue();
            if (gameTime - pending.lastUpdateTick < AGGREGATION_TICKS) {
                continue;
            }
            Entity target = level.getEntity(entry.getKey().targetId);
            if (target instanceof LivingEntity && isWithinRenderDistance(player, target)) {
                DamageKey displayKey = trackedAccumulate ? new DamageKey(0L, entry.getKey().targetId) : entry.getKey();
                DamageNumber active = ACTIVE.get(displayKey);
                if (active == null) {
                    long lifetime = trackedAccumulate ? TOTAL_LIFETIME_MS : SHOT_LIFETIME_MS;
                    ACTIVE.put(displayKey, new DamageNumber(pending.amount, pending.headshot, now, lifetime,
                            horizontalOffset(displayKey), verticalOffset(displayKey), entry.getKey().shotId));
                } else {
                    active.add(pending.amount, pending.headshot, entry.getKey().shotId, now);
                }
            }
            iterator.remove();
        }
    }

    private static void flushFixedDamage(long gameTime, long now) {
        Iterator<Map.Entry<Long, PendingDamage>> iterator = FIXED_PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, PendingDamage> entry = iterator.next();
            PendingDamage pending = entry.getValue();
            if (gameTime - pending.lastUpdateTick < AGGREGATION_TICKS) {
                continue;
            }
            if (fixedDamage == null || entry.getKey() >= fixedDamage.shotId) {
                if (fixedDamage == null) {
                    fixedDamage = new FixedDamage(entry.getKey(), pending.amount, pending.headshot, entry.getKey(), now);
                } else {
                    fixedDamage.replaceShot(entry.getKey(), pending.amount, pending.headshot, now);
                }
            }
            iterator.remove();
        }
    }

    @SubscribeEvent
    public static void onRenderLiving(RenderLivingEvent.Post<?, ?> event) {
        if (!RenderConfig.DAMAGE_NUMBER_ENABLE.get() || RenderConfig.DAMAGE_NUMBER_STYLE.get() != DamageNumberStyle.FLOATING
                || ACTIVE.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        LivingEntity entity = event.getEntity();
        if (player == null || !isWithinRenderDistance(player, entity)) {
            return;
        }

        long now = Util.getMillis();
        for (Map.Entry<DamageKey, DamageNumber> entry : ACTIVE.entrySet()) {
            if (entry.getKey().targetId != entity.getId()) {
                continue;
            }
            DamageNumber number = entry.getValue();
            if (!number.isExpired(now)) {
                renderFloatingNumber(event.getPoseStack(), event, number, now);
            }
        }
    }

    private static void renderFloatingNumber(PoseStack poseStack, RenderLivingEvent.Post<?, ?> event,
                                             DamageNumber number, long now) {
        Minecraft minecraft = Minecraft.getInstance();
        int color = colorWithOpacity(number.headshotFlashAt >= 0L ? 0xFF5555 : 0xFFFFFF,
                getOpacity(number.createdAt, number.lastUpdateAt, number.lifetime, now));
        if (color == 0) {
            return;
        }
        long age = now - number.createdAt;
        float progress = Mth.clamp((float) age / SHOT_LIFETIME_MS, 0.0F, 1.0F);
        float rise = (1.0F - (float) Math.pow(1.0F - progress, 3.0D)) * 0.42F;
        double depthCompensation = getDepthCompensation(minecraft, event.getEntity(), event.getPartialTick());
        Component text = damageText(number.amount);

        poseStack.pushPose();
        poseStack.translate(0.0D, event.getEntity().getBbHeight()
                + (0.38D + rise + number.verticalOffset) * depthCompensation, 0.0D);
        poseStack.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
        poseStack.translate(number.horizontalOffset * depthCompensation, 0.0D, 0.0D);
        float scale = BASE_WORLD_SCALE * (float) depthCompensation;
        poseStack.scale(-scale, -scale, scale);
        Font font = minecraft.font;
        font.drawInBatch(text, -font.width(text) / 2.0F, 0.0F, color, true, poseStack.last().pose(),
                event.getMultiBufferSource(), Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        poseStack.popPose();
    }

    public static void renderFixed(GuiGraphics graphics, int width, int height) {
        if (!RenderConfig.DAMAGE_NUMBER_ENABLE.get() || RenderConfig.DAMAGE_NUMBER_STYLE.get() != DamageNumberStyle.FIXED
                || fixedDamage == null) {
            return;
        }
        long now = Util.getMillis();
        if (fixedDamage.isExpired(now)) {
            return;
        }
        int color = colorWithOpacity(getFixedDamageColor(fixedDamage.headshotFlashAt, now), fixedDamage.updateOpacity(now));
        if (color == 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        Component text = damageText(fixedDamage.amount);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        PoseStack poseStack = graphics.pose();
        poseStack.pushPose();
        poseStack.translate(width / 2.0F, height - FIXED_Y_OFFSET, 0.0F);
        poseStack.scale(FIXED_SCALE, FIXED_SCALE, 1.0F);
        graphics.drawString(font, text, -font.width(text) / 2, -font.lineHeight / 2, color, true);
        poseStack.popPose();
        RenderSystem.disableBlend();
    }

    private static float getOpacity(long createdAt, long lastUpdateAt, long lifetime, long now) {
        float fadeIn = smoothStep((float) (now - createdAt) / FADE_IN_MS);
        long timeSinceUpdate = now - lastUpdateAt;
        long fadeOutStart = lifetime - FADE_OUT_DURATION_MS;
        float fadeOut = timeSinceUpdate > fadeOutStart
                ? 1.0F - smoothStep((float) (timeSinceUpdate - fadeOutStart) / FADE_OUT_DURATION_MS)
                : 1.0F;
        return Math.min(fadeIn, fadeOut);
    }

    private static Component damageText(float amount) {
        return Component.literal(DAMAGE_FORMAT.format(amount));
    }

    private static int colorWithOpacity(int rgb, float opacity) {
        int alpha = Mth.clamp(Math.round(opacity * 255.0F), 0, 255);
        // Font treats alpha values below four as unspecified and forces them opaque.
        if (alpha < 4) {
            return 0;
        }
        return alpha << 24 | rgb;
    }

    private static int getFixedDamageColor(long headshotFlashAt, long now) {
        if (headshotFlashAt < 0L) {
            return 0xFFFFFF;
        }
        float progress = smoothStep((float) (now - headshotFlashAt) / FIXED_HEADSHOT_COLOR_FADE_MS);
        int red = Mth.lerpInt(progress, 0xFF, 0xFF);
        int green = Mth.lerpInt(progress, 0x55, 0xFF);
        int blue = Mth.lerpInt(progress, 0x55, 0xFF);
        return red << 16 | green << 8 | blue;
    }

    private static float smoothStep(float value) {
        float clamped = Mth.clamp(value, 0.0F, 1.0F);
        return clamped * clamped * (3.0F - 2.0F * clamped);
    }

    private static double getDepthCompensation(Minecraft minecraft, LivingEntity entity, float partialTick) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPosition = camera.getPosition();
        Vector3f look = camera.getLookVector();
        double targetX = Mth.lerp(partialTick, entity.xOld, entity.getX());
        double targetY = Mth.lerp(partialTick, entity.yOld, entity.getY()) + entity.getBbHeight();
        double targetZ = Mth.lerp(partialTick, entity.zOld, entity.getZ());
        double depth = (targetX - cameraPosition.x) * look.x()
                + (targetY - cameraPosition.y) * look.y()
                + (targetZ - cameraPosition.z) * look.z();
        return Math.max(depth, MINIMUM_DEPTH) / REFERENCE_DEPTH;
    }

    private static boolean isWithinRenderDistance(LocalPlayer player, Entity entity) {
        double distanceSqr = player.distanceToSqr(entity);
        double viewDistance = Minecraft.getInstance().options.renderDistance().get() * 16.0D;
        return distanceSqr <= viewDistance * viewDistance && entity.shouldRenderAtSqrDistance(distanceSqr);
    }

    private static float horizontalOffset(DamageKey key) {
        long mixed = mixKey(key);
        return ((mixed & 1023L) / 1023.0F - 0.5F) * 0.46F;
    }

    private static float verticalOffset(DamageKey key) {
        long mixed = mixKey(key);
        return ((mixed >>> 10 & 255L) / 255.0F) * 0.18F;
    }

    private static long mixKey(DamageKey key) {
        long mixed = key.shotId ^ (key.targetId * 0x9E3779B97F4A7C15L);
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return mixed;
    }

    private static void ensureContext(ClientLevel level) {
        DamageNumberStyle style = RenderConfig.DAMAGE_NUMBER_STYLE.get();
        boolean accumulate = RenderConfig.DAMAGE_NUMBER_ACCUMULATE.get();
        if (level != trackedLevel || style != trackedStyle || accumulate != trackedAccumulate) {
            clear(level, style, accumulate);
        }
    }

    private static void clear(ClientLevel level, DamageNumberStyle style, boolean accumulate) {
        PENDING.clear();
        ACTIVE.clear();
        FIXED_PENDING.clear();
        fixedDamage = null;
        trackedLevel = level;
        trackedStyle = style;
        trackedAccumulate = accumulate;
    }

    private record DamageKey(long shotId, int targetId) {
    }

    private static final class PendingDamage {
        private float amount;
        private boolean headshot;
        private long lastUpdateTick;

        private PendingDamage(float amount, boolean headshot, long lastUpdateTick) {
            this.amount = amount;
            this.headshot = headshot;
            this.lastUpdateTick = lastUpdateTick;
        }

        private void add(float amount, boolean headshot, long updateTick) {
            this.amount += amount;
            this.headshot |= headshot;
            this.lastUpdateTick = updateTick;
        }
    }

    private static final class DamageNumber {
        private float amount;
        private final long createdAt;
        private long lastUpdateAt;
        private final long lifetime;
        private final float horizontalOffset;
        private final float verticalOffset;
        private long latestColorShotId;
        private boolean latestShotHeadshot;
        private long headshotFlashAt;

        private DamageNumber(float amount, boolean headshot, long now, long lifetime,
                             float horizontalOffset, float verticalOffset, long shotId) {
            this.amount = amount;
            this.createdAt = now;
            this.lastUpdateAt = now;
            this.lifetime = lifetime;
            this.horizontalOffset = horizontalOffset;
            this.verticalOffset = verticalOffset;
            this.latestColorShotId = shotId;
            this.latestShotHeadshot = headshot;
            this.headshotFlashAt = headshot ? now : -1L;
        }

        private void add(float amount, boolean headshot, long shotId, long now) {
            this.amount += amount;
            this.lastUpdateAt = now;
            this.updateHeadshotColor(shotId, headshot, now);
        }

        private void updateHeadshotColor(long shotId, boolean headshot, long now) {
            if (shotId > this.latestColorShotId) {
                this.latestColorShotId = shotId;
                this.latestShotHeadshot = headshot;
                this.headshotFlashAt = headshot ? now : -1L;
            } else if (shotId == this.latestColorShotId && headshot && !this.latestShotHeadshot) {
                this.latestShotHeadshot = true;
                this.headshotFlashAt = now;
            }
        }

        private boolean isExpired(long now) {
            return now - this.lastUpdateAt >= this.lifetime;
        }
    }

    private static final class FixedDamage {
        private long shotId;
        private float amount;
        private long lastUpdateAt;
        private long opacityUpdatedAt;
        private float opacity;
        private long latestColorShotId;
        private boolean latestShotHeadshot;
        private long headshotFlashAt;

        private FixedDamage(long shotId, float amount, boolean headshot, long colorShotId, long now) {
            this.shotId = shotId;
            this.amount = amount;
            this.lastUpdateAt = now;
            this.opacityUpdatedAt = now;
            this.opacity = 0.0F;
            this.latestColorShotId = colorShotId;
            this.latestShotHeadshot = headshot;
            this.headshotFlashAt = headshot ? now : -1L;
        }

        private void add(float amount, boolean headshot, long colorShotId, long now) {
            this.updateOpacity(now);
            this.amount += amount;
            this.lastUpdateAt = now;
            if (colorShotId > this.latestColorShotId) {
                this.latestColorShotId = colorShotId;
                this.latestShotHeadshot = headshot;
                this.headshotFlashAt = headshot ? now : -1L;
            } else if (colorShotId == this.latestColorShotId && headshot && !this.latestShotHeadshot) {
                this.latestShotHeadshot = true;
                this.headshotFlashAt = now;
            }
        }

        private void replaceShot(long shotId, float amount, boolean headshot, long now) {
            this.updateOpacity(now);
            this.shotId = shotId;
            this.amount = amount;
            this.lastUpdateAt = now;
            this.latestColorShotId = shotId;
            this.latestShotHeadshot = headshot;
            this.headshotFlashAt = headshot ? now : -1L;
        }

        private float updateOpacity(long now) {
            long elapsed = Math.max(now - this.opacityUpdatedAt, 0L);
            this.opacityUpdatedAt = now;
            boolean shouldShow = now - this.lastUpdateAt < FIXED_HOLD_MS;
            float duration = shouldShow ? FIXED_FADE_IN_MS : FIXED_FADE_OUT_MS;
            float change = elapsed / duration;
            this.opacity = Mth.clamp(this.opacity + (shouldShow ? change : -change), 0.0F, 1.0F);
            return smoothStep(this.opacity);
        }

        private boolean isExpired(long now) {
            return now - this.lastUpdateAt >= FIXED_EXPIRE_MS;
        }
    }
}
