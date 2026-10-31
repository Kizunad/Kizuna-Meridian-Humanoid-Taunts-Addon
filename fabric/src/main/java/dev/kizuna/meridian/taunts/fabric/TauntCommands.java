package dev.kizuna.meridian.taunts.fabric;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.kizuna.meridian.fabric.MeridianCommands;
import dev.kizuna.meridian.taunts.server.Audience;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * /meridian humanoid taunt &lt;target&gt; [player|entity]：让目标立刻说一句，用于调试；权限 2，全部节点可补全。
 * {attacker} 换成命令执行者；不写对象时，玩家执行取对玩家台词，其他来源取对生物台词。
 */
final class TauntCommands {
    static final String NODE = "taunt";
    private static final String TARGET = "target";
    private static final DynamicCommandExceptionType SILENT = new DynamicCommandExceptionType(
            name -> Component.translatable("commands.meridian_humanoid_taunts.taunt.silent", name));

    private TauntCommands() {
        // 只组装 Brigadier 节点。
    }

    static void register(TauntSpeaker speaker) {
        // 挂到 Framework 注册点的 humanoid 节点下；权限由注册点套在 taunt 节点上，子树不再自设。
        MeridianCommands.register(MeridianCommands.Branch.HUMANOID, NODE, MeridianCommands.GAMEMASTER,
                (node, context) -> node.then(target(speaker)));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, EntitySelector> target(TauntSpeaker speaker) {
        RequiredArgumentBuilder<CommandSourceStack, EntitySelector> target =
                Commands.argument(TARGET, EntityArgument.entity());
        target.executes(context -> taunt(context, speaker, defaultAudience(context.getSource())));
        for (Audience audience : Audience.values()) {
            LiteralArgumentBuilder<CommandSourceStack> explicit = Commands.literal(audience.id());
            explicit.executes(context -> taunt(context, speaker, audience));
            target.then(explicit);
        }
        return target;
    }

    private static int taunt(CommandContext<CommandSourceStack> context, TauntSpeaker speaker, Audience audience)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Entity target = EntityArgument.getEntity(context, TARGET);
        if (!(target instanceof LivingEntity living) || !speaker.sayNow(living, audience, addressee(source))) {
            throw SILENT.create(target.getName());
        }
        source.sendSuccess(() -> Component.translatable("commands.meridian_humanoid_taunts.taunt.success",
                target.getName()), false /* broadcastToOps */);
        return 1;
    }

    private static Audience defaultAudience(CommandSourceStack source) {
        return source.getEntity() instanceof Player ? Audience.PLAYER : Audience.ENTITY;
    }

    private static Component addressee(CommandSourceStack source) {
        // 实体执行者用其名字；控制台、命令方块等没有实体时用来源显示名。
        Entity executor = source.getEntity();
        if (executor == null) {
            return source.getDisplayName();
        }
        return executor.getName();
    }
}
