package dev.kizuna.meridian.taunts.fabric;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
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
 * /meridian_humanoid_taunts say &lt;target&gt; [player|entity]：让目标立刻说一句，用于调试；权限 2，全部节点可补全。
 * {attacker} 换成命令执行者；不写对象时，玩家执行取对玩家台词，其他来源取对生物台词。
 */
final class TauntCommands {
    private static final int PERMISSION_LEVEL = 2;
    private static final String TARGET = "target";
    private static final DynamicCommandExceptionType SILENT = new DynamicCommandExceptionType(
            name -> Component.translatable("commands.meridian_humanoid_taunts.say.silent", name));

    private TauntCommands() {
        // 只组装 Brigadier 节点。
    }

    static LiteralArgumentBuilder<CommandSourceStack> command(TauntSpeaker speaker) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(TauntsFabric.MOD_ID);
        root.requires(source -> source.hasPermission(PERMISSION_LEVEL));
        RequiredArgumentBuilder<CommandSourceStack, EntitySelector> target =
                Commands.argument(TARGET, EntityArgument.entity());
        target.executes(context -> say(context, speaker, defaultAudience(context.getSource())));
        for (Audience audience : Audience.values()) {
            LiteralArgumentBuilder<CommandSourceStack> explicit = Commands.literal(audience.id());
            explicit.executes(context -> say(context, speaker, audience));
            target.then(explicit);
        }
        LiteralArgumentBuilder<CommandSourceStack> say = Commands.literal("say");
        say.then(target);
        root.then(say);
        return root;
    }

    private static int say(CommandContext<CommandSourceStack> context, TauntSpeaker speaker, Audience audience)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Entity target = EntityArgument.getEntity(context, TARGET);
        if (!(target instanceof LivingEntity living) || !speaker.sayNow(living, audience, addressee(source))) {
            throw SILENT.create(target.getName());
        }
        source.sendSuccess(() -> Component.translatable("commands.meridian_humanoid_taunts.say.success",
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
