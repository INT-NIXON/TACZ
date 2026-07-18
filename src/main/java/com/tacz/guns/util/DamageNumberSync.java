package com.tacz.guns.util;

import com.tacz.guns.network.NetworkHandler;
import com.tacz.guns.network.message.ServerMessageDamageNumber;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.concurrent.atomic.AtomicLong;

public final class DamageNumberSync {
    private static final AtomicLong ATTACK_SEQUENCE = new AtomicLong();

    private DamageNumberSync() {
    }

    public static float getRemainingHealth(Entity entity) {
        if (entity instanceof LivingEntity livingEntity) {
            return Math.max(livingEntity.getHealth(), 0.0F) + Math.max(livingEntity.getAbsorptionAmount(), 0.0F);
        }
        return 0.0F;
    }

    public static long nextAttackId() {
        return ATTACK_SEQUENCE.incrementAndGet();
    }

    public static void send(Entity owner, Entity target, long shotId, float amount, boolean headshot) {
        send(owner, target, shotId, amount, headshot, false);
    }

    public static void sendMelee(Entity owner, Entity target, long attackId, float amount) {
        send(owner, target, attackId, amount, false, true);
    }

    private static void send(Entity owner, Entity target, long attackId, float amount, boolean headshot, boolean melee) {
        if (!(owner instanceof ServerPlayer player) || !(target instanceof LivingEntity) || owner == target || amount <= 0.0F) {
            return;
        }
        NetworkHandler.sendToClientPlayer(new ServerMessageDamageNumber(attackId, target.getId(), amount, headshot, melee), player);
    }
}
