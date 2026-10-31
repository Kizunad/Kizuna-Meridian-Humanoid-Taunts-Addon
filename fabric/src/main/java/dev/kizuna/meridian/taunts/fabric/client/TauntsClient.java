package dev.kizuna.meridian.taunts.fabric.client;

import dev.kizuna.meridian.taunts.fabric.BubblePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.util.Util;

/** 客户端入口：收气泡包、每帧提取并提交气泡，断开连接时清空。 */
public final class TauntsClient implements ClientModInitializer {
    private final TauntBubbles bubbles = new TauntBubbles();

    @Override
    public void onInitializeClient() {
        // 26.2 的收包回调已在客户端主线程，直接存入气泡。
        // 原「实体渲染后直接绘制」拆成两步：提取结束时取位置与折行，收集提交时交给原版特性渲染。
        ClientPlayNetworking.registerGlobalReceiver(BubblePayload.TYPE,
                (payload, context) -> bubbles.show(payload.entityId(), payload.line(), Util.getMillis()));
        LevelExtractionEvents.END_EXTRACTION.register(bubbles::extract);
        LevelRenderEvents.COLLECT_SUBMITS.register(bubbles::submit);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(bubbles::clear));
    }
}
