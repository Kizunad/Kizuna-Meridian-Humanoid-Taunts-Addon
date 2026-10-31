package dev.kizuna.meridian.taunts.fabric;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import dev.kizuna.meridian.taunts.server.Audience;
import dev.kizuna.meridian.taunts.server.SpeciesTaunts;
import dev.kizuna.meridian.taunts.server.TauntCatalog;
import dev.kizuna.meridian.taunts.server.TauntLine;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.LiteralContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 真实世界里的受击发言：伤害都经 LivingEntity.hurt 进入 Damage，再由观察事件触发台词。
 * 收听方是加入世界玩家列表、截获聊天的假玩家；每个用例单独一批，互不串听。
 */
public final class TauntGameTest implements FabricGameTest {
    private static final String LANGUAGE = "zh_cn";
    private static final String VILLAGER = "minecraft:villager";
    private static final String VINDICATOR_KEY = "entity.minecraft.vindicator";
    private static final int SPAWN_X = 1;
    private static final int SPAWN_Y = 2;
    private static final int SPAWN_Z = 1;
    private static final float LIGHT_HIT = 1.0F;
    private static final float LETHAL_HIT = 1000.0F;
    private static final double NEAR = 3.0;
    private static final double OUT_OF_RANGE = TauntSpeaker.LISTEN_RADIUS + 1;
    /** 默认配置的 6 秒冷却。 */
    private static final int COOLDOWN_TICKS = 120;
    private static final int COOLDOWN_TICK_LIMIT = COOLDOWN_TICKS + 40;
    private static final int PERMISSION_LEVEL = 2;
    private static final String TAUNT = "meridian humanoid " + TauntCommands.NODE + " ";
    private static final TauntCatalog CATALOG = TauntCatalog.parse(TauntsFabric.catalogText(LANGUAGE));

    @GameTest(template = EMPTY_STRUCTURE, batch = "taunts_player")
    public void playerAttackerHearsOwnName(GameTestHelper context) {
        LivingEntity villager = humanoid(context, EntityType.VILLAGER);
        Listener attacker = listener(context, "TauntPlayer", villager, NEAR);
        Listener outsider = listener(context, "TauntOutsider", villager, OUT_OF_RANGE);
        try {
            villager.hurt(villager.damageSources().playerAttack(attacker), LIGHT_HIT);
            Component chat = onlyChat(context, attacker, "玩家攻击者");
            // 村民的默认名字按职业取键，无业村民是 entity.minecraft.villager.none。
            Component speaker = speaker(chat);
            context.assertTrue(speaker.getContents() instanceof TranslatableContents
                    && speaker.equals(villager.getName()), "说话者应是村民自己的可翻译名字：" + speaker);
            context.assertTrue(TextColor.fromLegacyFormat(ChatFormatting.GRAY).equals(chat.getStyle().getColor()),
                    "垃圾话聊天应为浅灰色");
            Component line = line(chat);
            context.assertTrue("TauntPlayer".equals(literal(line.getSiblings().get(1))), "台词里应是攻击者玩家名");
            context.assertTrue(audience(line) == Audience.PLAYER, "玩家攻击者应抽对玩家台词");
            context.assertTrue(chats(outsider).isEmpty(), "17 格外的玩家不应收到垃圾话");
        } finally {
            leave(context, attacker, outsider);
        }
        context.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "taunts_entity")
    public void vindicatorAttackerIsNamedByTranslationKey(GameTestHelper context) {
        LivingEntity villager = humanoid(context, EntityType.VILLAGER);
        LivingEntity vindicator = humanoid(context, EntityType.VINDICATOR);
        Listener bystander = listener(context, "TauntBystander", villager, TauntSpeaker.LISTEN_RADIUS);
        try {
            villager.hurt(villager.damageSources().mobAttack(vindicator), LIGHT_HIT);
            Component line = line(onlyChat(context, bystander, "16 格处的旁观者"));
            Component attacker = line.getSiblings().get(1);
            context.assertTrue(attacker.getContents() instanceof TranslatableContents key
                    && key.getKey().equals(VINDICATOR_KEY), "台词里的攻击者应是卫道士的翻译组件");
            context.assertTrue(audience(line) == Audience.ENTITY, "生物攻击者应抽对生物台词");
        } finally {
            leave(context, bystander);
        }
        context.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "taunts_cooldown", timeoutTicks = COOLDOWN_TICK_LIMIT)
    public void cooldownSilencesRepeatedHitsForSixSeconds(GameTestHelper context) {
        LivingEntity villager = humanoid(context, EntityType.VILLAGER);
        Listener attacker = listener(context, "TauntRepeat", villager, NEAR);
        villager.hurt(villager.damageSources().playerAttack(attacker), LIGHT_HIT);
        villager.invulnerableTime = 0;
        villager.hurt(villager.damageSources().playerAttack(attacker), LIGHT_HIT);
        context.assertTrue(chats(attacker).size() == 1, "冷却内再次受击不应再说");
        context.runAfterDelay(COOLDOWN_TICKS, () -> {
            try {
                villager.invulnerableTime = 0;
                villager.hurt(villager.damageSources().playerAttack(attacker), LIGHT_HIT);
                List<Component> chats = chats(attacker);
                context.assertTrue(chats.size() == 2, "冷却结束后应再说一句，实际 " + chats.size());
                context.assertTrue(!lineKey(line(chats.get(0))).equals(lineKey(line(chats.get(1)))),
                        "同一实体连续两句不应重复");
            } finally {
                leave(context, attacker);
            }
            context.succeed();
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "taunts_lethal")
    public void lethalHitStaysSilent(GameTestHelper context) {
        LivingEntity villager = humanoid(context, EntityType.VILLAGER);
        Listener attacker = listener(context, "TauntKiller", villager, NEAR);
        try {
            villager.hurt(villager.damageSources().playerAttack(attacker), LETHAL_HIT);
            context.assertTrue(!villager.isAlive(), "致死伤害后村民应已死亡");
            context.assertTrue(chats(attacker).isEmpty(), "致死一击不应说垃圾话");
        } finally {
            leave(context, attacker);
        }
        context.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "taunts_command")
    public void tauntCommandSpeaksNowAndCompletes(GameTestHelper context) throws CommandSyntaxException {
        LivingEntity villager = humanoid(context, EntityType.VILLAGER);
        LivingEntity cow = context.spawn(EntityType.COW, SPAWN_X, SPAWN_Y, SPAWN_Z);
        Listener operator = listener(context, "TauntOperator", villager, NEAR);
        try {
            CommandDispatcher<CommandSourceStack> dispatcher =
                    context.getLevel().getServer().getCommands().getDispatcher();
            CommandSourceStack source = operator.createCommandSourceStack().withPermission(PERMISSION_LEVEL);
            String taunt = TAUNT + villager.getStringUUID();
            context.assertTrue(dispatcher.execute(taunt + " entity", source) == 1, "taunt entity 应成功");
            context.assertTrue(dispatcher.execute(taunt, source) == 1, "冷却中的 taunt 也应立刻说");
            List<Component> chats = chats(operator);
            context.assertTrue(chats.size() == 2, "两次 taunt 应各说一句，实际 " + chats.size());
            context.assertTrue(audience(line(chats.get(0))) == Audience.ENTITY, "指定 entity 应抽对生物台词");
            context.assertTrue(audience(line(chats.get(1))) == Audience.PLAYER, "玩家执行且不指定时应抽对玩家台词");
            context.assertTrue("TauntOperator".equals(literal(line(chats.get(1)).getSiblings().get(1))),
                    "命令台词里应是执行者名字");
            requireSilent(dispatcher, source, TAUNT + cow.getStringUUID());
            Set<String> options = suggestions(dispatcher, source, taunt + " ");
            context.assertTrue(options.containsAll(Set.of("player", "entity")), "taunt 应补全 player/entity：" + options);
            // 指令挂在 /meridian humanoid 下：管理员能补全到 taunt，权限 0 看不到；旧根不留别名。
            Set<String> humanoid = suggestions(dispatcher, source, "meridian humanoid ");
            context.assertTrue(humanoid.contains(TauntCommands.NODE), "humanoid 下应补全 taunt：" + humanoid);
            Set<String> player = suggestions(dispatcher, source.withPermission(0), "meridian humanoid ");
            context.assertFalse(player.contains(TauntCommands.NODE), "权限 0 不应看到 taunt：" + player);
            context.assertTrue(dispatcher.getRoot().getChild(TauntsFabric.MOD_ID) == null, "旧根 /" + TauntsFabric.MOD_ID + " 应已删除");
        } finally {
            leave(context, operator);
        }
        context.succeed();
    }

    private static LivingEntity humanoid(GameTestHelper context, EntityType<? extends Mob> type) {
        // 关掉 AI，生人不会在两次命中之间跑开或反击收听方。
        Mob entity = context.spawn(type, SPAWN_X, SPAWN_Y, SPAWN_Z);
        entity.setNoAi(true);
        return entity;
    }

    private static Listener listener(GameTestHelper context, String name, Entity speaker, double distance) {
        // 加入世界玩家列表，收听范围按真实距离筛选。
        ServerLevel world = context.getLevel();
        UUID id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        Listener listener = new Listener(world, new GameProfile(id, name));
        listener.moveTo(speaker.getX() + distance, speaker.getY(), speaker.getZ(), 0, 0);
        world.addNewPlayer(listener);
        return listener;
    }

    private static void leave(GameTestHelper context, Listener... listeners) {
        for (Listener listener : listeners) {
            context.getLevel().removePlayerImmediately(listener, Entity.RemovalReason.DISCARDED);
        }
    }

    private static List<Component> chats(Listener listener) {
        // 只取垃圾话聊天行，命令反馈等其他系统消息不算。
        return listener.messages.stream()
                .filter(message -> message.getContents() instanceof TranslatableContents key
                        && key.getKey().equals(TauntTexts.CHAT_FORMAT))
                .collect(Collectors.toList());
    }

    private static Component onlyChat(GameTestHelper context, Listener listener, String who) {
        List<Component> chats = chats(listener);
        context.assertTrue(chats.size() == 1, who + "应恰好收到一句垃圾话，实际 " + chats.size());
        return chats.get(0);
    }

    private static Component speaker(Component chat) {
        return (Component) ((TranslatableContents) chat.getContents()).getArgs()[0];
    }

    private static Component line(Component chat) {
        return (Component) ((TranslatableContents) chat.getContents()).getArgs()[1];
    }

    private static String literal(Component text) {
        return ((LiteralContents) text.getContents()).text();
    }

    private static String lineKey(Component line) {
        return literal(line.getSiblings().get(0)) + TauntLine.PLACEHOLDER + literal(line.getSiblings().get(2));
    }

    private static Audience audience(Component line) {
        // 按台词前后两段反查它来自村民的哪一组。
        SpeciesTaunts villager = CATALOG.species(VILLAGER).orElseThrow();
        TauntLine spoken = TauntLine.parse(lineKey(line));
        Optional<Audience> found = Arrays.stream(Audience.values())
                .filter(audience -> villager.lines(audience).contains(spoken))
                .findFirst();
        return found.orElseThrow(() -> new IllegalStateException("台词不在村民台词表里：" + lineKey(line)));
    }

    private static void requireSilent(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source,
                                      String command) {
        // 没有台词的实体：命令失败并给出可读原因。
        try {
            dispatcher.execute(command, source);
        } catch (CommandSyntaxException expected) {
            return;
        }
        throw new GameTestAssertException("对没有台词的实体 taunt 应失败：" + command);
    }

    private static Set<String> suggestions(CommandDispatcher<CommandSourceStack> dispatcher,
                                           CommandSourceStack source, String input) {
        var parse = dispatcher.parse(input, source);
        return dispatcher.getCompletionSuggestions(parse).join().getList().stream()
                .map(Suggestion::getText)
                .collect(Collectors.toSet());
    }

    /** 截获聊天的假玩家；FakePlayer 自带的网络处理器丢弃一切数据包。 */
    private static final class Listener extends FakePlayer {
        private final List<Component> messages = new ArrayList<>();

        private Listener(ServerLevel world, GameProfile profile) {
            super(world, profile);
        }

        @Override
        public void displayClientMessage(Component message, boolean overlay) {
            messages.add(message);
        }
    }
}
