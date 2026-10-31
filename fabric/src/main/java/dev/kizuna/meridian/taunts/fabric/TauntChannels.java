package dev.kizuna.meridian.taunts.fabric;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** 头顶气泡的 S2C 通道；载荷是说话者的实体网络 ID 与台词文本组件。 */
public final class TauntChannels {
    public static final ResourceLocation BUBBLE = new ResourceLocation(TauntsFabric.MOD_ID, "bubble");

    private TauntChannels() {
        // 只保存通道与编码。
    }

    static void sendBubble(Iterable<ServerPlayer> listeners, Entity speaker, Component line) {
        // 没装本模组客户端的玩家收不到通道，只看聊天。
        for (ServerPlayer listener : listeners) {
            if (!ServerPlayNetworking.canSend(listener, BUBBLE)) {
                continue;
            }
            FriendlyByteBuf buffer = PacketByteBufs.create();
            buffer.writeVarInt(speaker.getId());
            buffer.writeComponent(line);
            ServerPlayNetworking.send(listener, BUBBLE, buffer);
        }
    }
}
