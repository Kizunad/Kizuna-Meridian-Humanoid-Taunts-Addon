package dev.kizuna.meridian.taunts.fabric;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** 头顶气泡的 S2C 通道；载荷是说话者的实体网络 ID 与台词文本组件。 */
public final class TauntChannels {
    public static final Identifier BUBBLE = new Identifier(TauntsFabric.MOD_ID, "bubble");

    private TauntChannels() {
        // 只保存通道与编码。
    }

    static void sendBubble(Iterable<ServerPlayerEntity> listeners, Entity speaker, Text line) {
        // 没装本模组客户端的玩家收不到通道，只看聊天。
        for (ServerPlayerEntity listener : listeners) {
            if (!ServerPlayNetworking.canSend(listener, BUBBLE)) {
                continue;
            }
            PacketByteBuf buffer = PacketByteBufs.create();
            buffer.writeVarInt(speaker.getId());
            buffer.writeText(line);
            ServerPlayNetworking.send(listener, BUBBLE, buffer);
        }
    }
}
