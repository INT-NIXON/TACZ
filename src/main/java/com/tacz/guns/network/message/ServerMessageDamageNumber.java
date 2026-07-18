package com.tacz.guns.network.message;

import com.tacz.guns.client.event.DamageNumberRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ServerMessageDamageNumber {
    private final long shotId;
    private final int targetId;
    private final float amount;
    private final boolean headshot;
    private final boolean melee;

    public ServerMessageDamageNumber(long shotId, int targetId, float amount, boolean headshot, boolean melee) {
        this.shotId = shotId;
        this.targetId = targetId;
        this.amount = amount;
        this.headshot = headshot;
        this.melee = melee;
    }

    public static void encode(ServerMessageDamageNumber message, FriendlyByteBuf buf) {
        buf.writeLong(message.shotId);
        buf.writeInt(message.targetId);
        buf.writeFloat(message.amount);
        buf.writeBoolean(message.headshot);
        buf.writeBoolean(message.melee);
    }

    public static ServerMessageDamageNumber decode(FriendlyByteBuf buf) {
        return new ServerMessageDamageNumber(buf.readLong(), buf.readInt(), buf.readFloat(), buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(ServerMessageDamageNumber message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> handleClient(message));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(ServerMessageDamageNumber message) {
        DamageNumberRenderer.addDamage(message.shotId, message.targetId, message.amount, message.headshot, message.melee);
    }
}
