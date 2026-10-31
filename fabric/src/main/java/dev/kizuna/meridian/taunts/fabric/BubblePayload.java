package dev.kizuna.meridian.taunts.fabric;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * 头顶气泡的 S2C 包；载荷是说话者的实体网络 ID 与台词文本组件。通道 ID 与编解码只在这里定义一份。
 * 文本组件与原版系统聊天同一编码：26.2 起按带注册表的结构编码，不再是 1.20.1 的 JSON 字符串。
 */
public record BubblePayload(int entityId, Component line) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BubblePayload> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(TauntsFabric.MOD_ID, "bubble"));
    static final StreamCodec<RegistryFriendlyByteBuf, BubblePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BubblePayload::entityId,
            ComponentSerialization.TRUSTED_STREAM_CODEC, BubblePayload::line,
            BubblePayload::new);

    static void send(Iterable<ServerPlayer> listeners, Entity speaker, Component line) {
        // 没装本模组客户端的玩家收不到通道，只看聊天。
        for (ServerPlayer listener : listeners) {
            if (!ServerPlayNetworking.canSend(listener, TYPE)) {
                continue;
            }
            ServerPlayNetworking.send(listener, new BubblePayload(speaker.getId(), line));
        }
    }

    @Override
    public Type<BubblePayload> type() {
        // 收发两端都按这个类型查找通道。
        return TYPE;
    }
}
