package dev.kizuna.meridian.taunts.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.taunts.server.Audience;
import dev.kizuna.meridian.taunts.server.SpeciesTaunts;
import dev.kizuna.meridian.taunts.server.TauntCatalog;
import dev.kizuna.meridian.taunts.server.TauntLine;
import dev.kizuna.meridian.taunts.server.TauntMemory;
import dev.kizuna.meridian.taunts.server.TauntSettings;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * 生人受击发言：Threatening 认领、台词表里有的物种才开口；攻击者是玩家对玩家说，是其他生物对生物说。
 * 没有生物攻击者、打自己、致死一击和冷却中都不说。Threatening 招敌里生人对玩家说的写好的话也从这里发出去。
 * 只在服务端线程调用。
 */
final class TauntSpeaker {
    static final double LISTEN_RADIUS = 16.0;
    private static final double LISTEN_RADIUS_SQUARED = LISTEN_RADIUS * LISTEN_RADIUS;
    private final TauntSettings settings;
    private final TauntCatalog catalog;
    /** 按实体对象记忆，实体卸载后随垃圾回收释放；重新加载的实体从零开始。 */
    private final Map<LivingEntity, TauntMemory> memories = new WeakHashMap<>();

    TauntSpeaker(TauntSettings settings, TauntCatalog catalog) {
        this.settings = settings;
        this.catalog = catalog;
    }

    void onDamage(VanillaDamageFabric.DamageEvent event) {
        LivingEntity speaker = event.target();
        LivingEntity attacker = event.attacker();
        if (attacker == null || attacker == speaker || event.result().body().getVitality().getDead()) {
            return;
        }
        Optional<SpeciesTaunts> taunts = taunts(speaker);
        if (taunts.isEmpty()) {
            return;
        }
        if (memory(speaker).coolingDown(speaker.level().getGameTime(), settings.cooldownTicks())) {
            return;
        }
        Audience audience = attacker instanceof Player ? Audience.PLAYER : Audience.ENTITY;
        say(speaker, taunts.get().lines(audience), audience, attacker.getName());
    }

    boolean sayNow(LivingEntity speaker, Audience audience, Component addressee) {
        // 调试入口：不看冷却与死活，但同样记入冷却和最近台词。没有台词的实体返回 false。
        Optional<SpeciesTaunts> taunts = taunts(speaker);
        if (taunts.isEmpty()) {
            return false;
        }
        say(speaker, taunts.get().lines(audience), audience, addressee);
        return true;
    }

    private Optional<SpeciesTaunts> taunts(Entity entity) {
        // 只有 Threatening 认领的物种才有类人身体；台词表里没有的物种不开口。
        String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        if (HumanoidSpecies.fromEntityId(entityId).isEmpty()) {
            return Optional.empty();
        }
        return catalog.species(entityId);
    }

    private TauntMemory memory(LivingEntity speaker) {
        return memories.computeIfAbsent(speaker, ignored -> new TauntMemory());
    }

    void onSpoke(LivingEntity speaker, ServerPlayer addressee, Component line) {
        // Threatening 招敌里生人对玩家说的写好的话（传言、记仇的人认出玩家）：不抽台词、不看也不记冷却；
        // 照样发给 16 格内的玩家，对着说的那名玩家不在 16 格内也收得到。
        List<ServerPlayer> listeners = new ArrayList<>(listeners(speaker));
        if (!listeners.contains(addressee)) {
            listeners.add(addressee);
        }
        broadcast(speaker, line, listeners);
    }

    private void say(LivingEntity speaker, List<TauntLine> lines, Audience audience, Component addressee) {
        // 抽一句并记下，再把同一句发给 16 格内的玩家。
        long tick = speaker.level().getGameTime();
        int index = memory(speaker).speak(audience, lines.size(), ThreadLocalRandom.current(), tick);
        broadcast(speaker, TauntTexts.line(lines.get(index), addressee), listeners(speaker));
    }

    private static List<ServerPlayer> listeners(LivingEntity speaker) {
        // 说话者 16 格内的玩家。
        return ((ServerLevel) speaker.level()).getPlayers(
                player -> player.distanceToSqr(speaker) <= LISTEN_RADIUS_SQUARED);
    }

    private void broadcast(LivingEntity speaker, Component line, List<ServerPlayer> listeners) {
        // 聊天与气泡各自受开关控制。
        // 聊天走 26.2 的系统消息入口，与 1.20.1 的 displayClientMessage(…, false) 是同一条路径。
        if (settings.chat()) {
            Component message = TauntTexts.chat(speaker.getName(), line);
            for (ServerPlayer listener : listeners) {
                listener.sendSystemMessage(message);
            }
        }
        if (settings.bubble()) {
            BubblePayload.send(listeners, speaker, line);
        }
    }
}
