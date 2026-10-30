package dev.kizuna.meridian.taunts.fabric;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.kizuna.meridian.taunts.server.Audience;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/**
 * /meridian_humanoid_taunts say &lt;target&gt; [player|entity]：让目标立刻说一句，用于调试；权限 2，全部节点可补全。
 * {attacker} 换成命令执行者；不写对象时，玩家执行取对玩家台词，其他来源取对生物台词。
 */
final class TauntCommands {
    private static final int PERMISSION_LEVEL = 2;
    private static final String TARGET = "target";
    private static final DynamicCommandExceptionType SILENT = new DynamicCommandExceptionType(
            name -> Text.translatable("commands.meridian_humanoid_taunts.say.silent", name));

    private TauntCommands() {
        // 只组装 Brigadier 节点。
    }

    static LiteralArgumentBuilder<ServerCommandSource> command(TauntSpeaker speaker) {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal(TauntsFabric.MOD_ID);
        root.requires(source -> source.hasPermissionLevel(PERMISSION_LEVEL));
        RequiredArgumentBuilder<ServerCommandSource, EntitySelector> target =
                CommandManager.argument(TARGET, EntityArgumentType.entity());
        target.executes(context -> say(context, speaker, defaultAudience(context.getSource())));
        for (Audience audience : Audience.values()) {
            LiteralArgumentBuilder<ServerCommandSource> explicit = CommandManager.literal(audience.id());
            explicit.executes(context -> say(context, speaker, audience));
            target.then(explicit);
        }
        LiteralArgumentBuilder<ServerCommandSource> say = CommandManager.literal("say");
        say.then(target);
        root.then(say);
        return root;
    }

    private static int say(CommandContext<ServerCommandSource> context, TauntSpeaker speaker, Audience audience)
            throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        Entity target = EntityArgumentType.getEntity(context, TARGET);
        if (!(target instanceof LivingEntity living) || !speaker.sayNow(living, audience, addressee(source))) {
            throw SILENT.create(target.getName());
        }
        source.sendFeedback(() -> Text.translatable("commands.meridian_humanoid_taunts.say.success",
                target.getName()), false /* broadcastToOps */);
        return 1;
    }

    private static Audience defaultAudience(ServerCommandSource source) {
        return source.getEntity() instanceof PlayerEntity ? Audience.PLAYER : Audience.ENTITY;
    }

    private static Text addressee(ServerCommandSource source) {
        // 实体执行者用其名字；控制台、命令方块等没有实体时用来源显示名。
        Entity executor = source.getEntity();
        if (executor == null) {
            return source.getDisplayName();
        }
        return executor.getName();
    }
}
