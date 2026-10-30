package dev.kizuna.meridian.taunts.fabric.client;

import dev.kizuna.meridian.taunts.fabric.TauntChannels;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/** 客户端入口：收气泡包、在实体渲染阶段画气泡，断开连接时清空。 */
public final class TauntsClient implements ClientModInitializer {
    private final TauntBubbles bubbles = new TauntBubbles();

    @Override
    public void onInitializeClient() {
        // 网络线程只解码，存取气泡放回客户端主线程。
        ClientPlayNetworking.registerGlobalReceiver(TauntChannels.BUBBLE, (client, handler, buffer, sender) -> {
            int entityId = buffer.readVarInt();
            Text line = buffer.readText();
            client.execute(() -> bubbles.show(entityId, line, Util.getMeasuringTimeMs()));
        });
        WorldRenderEvents.AFTER_ENTITIES.register(bubbles::render);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(bubbles::clear));
    }
}
