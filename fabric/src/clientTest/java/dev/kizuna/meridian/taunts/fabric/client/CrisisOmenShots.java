package dev.kizuna.meridian.taunts.fabric.client;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 接在气泡截图后面，经 Threatening 招敌的调试指令走一遍征兆和来人，用真客户端截图：
 * 气泡用例里那个村民对玩家说第一句传言（气泡和浅灰色聊天，文字按简体中文从 Threatening 的语言文件解析）；
 * 床上留下的字条和它的悬停说明（名字、三行字）；叫来的一伙人，以及头目走进准星时头顶的名号。
 * 最后清掉这伙人（头目一死这一场就打赢了结），用 omen 指定重生旧簿的仇家那张队伍表，再拍一张仇家的字条。
 * 截图写进 {@code bubble-screens/crisis-*.png}，客户端看到的文字写进 {@code crisis.txt}。
 */
final class CrisisOmenShots {
    private static final String RUMOR_KEY = "crisis.meridian_humanoid.covetous.omen.rumor.1";
    private static final String NOTE_KEY = "item.meridian_humanoid.crisis_note";
    private static final String LEADER_KEY = "crisis.meridian_humanoid.covetous.leader_name";
    private static final String KEY_PREFIX = "crisis.";
    private static final String SPEAKER_PREFIX = "<村民> ";
    private static final int SETTLE_TICKS = 20;
    private static final int FACING_TICKS = 140;
    private static final double FACING_DOT = 0.97;
    private static final int RETRY_TICKS = 20;
    private static final int OMEN_TRIES = 10;
    private static final int NOTE_TRIES = 3;
    private static final int NOTE_LINES = 4;
    private static final double BED_DISTANCE = 2.0;
    private static final double NOTE_RADIUS = 3.0;
    private static final float NOTE_PITCH = 25.0F;
    // 两张字条：风声招来的贪卷那伙人照常排；重生旧簿的仇家在 omen 后面指定队伍表排。
    private static final NoteStep COVETOUS_NOTE = new NoteStep("meridian_humanoid:covetous", "", "note",
            "crisis-note.png", Phase.STRIKE);
    private static final NoteStep FOE_NOTE = new NoteStep("meridian_humanoid:old_register",
            " meridian_humanoid:old_register", "foe_note", "crisis-foe-note.png", Phase.DONE);
    private static final double RAIDERS_NEAR = 12.0;
    private static final double RAIDERS_RADIUS = 64.0;
    private static final int RAIDERS_FALLBACK_TICKS = 400;
    private static final int LEADER_TICKS = 400;
    private static final int COMMAND_FAILED = 0;
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid_taunts/crisis_shots");
    private static final Path SCREENS = Path.of("../bubble-screens");
    private static final Path TEXT = SCREENS.resolve("crisis.txt");
    private final List<Component> messages;
    private final String playerName;
    private final Consumer<String> done;
    private Phase phase = Phase.RUMOR;
    private int ticks;
    private int markTick;
    private int tries;
    private CompletableFuture<Integer> command;
    private BlockPos bed;
    private Mob leader;
    private ItemEntity noteItem;
    private NoteStep noteStep = COVETOUS_NOTE;

    private enum Phase {
        RUMOR, RUMOR_HEARD, NOTE, NOTE_SHOWN, NOTE_FACING, STRIKE, RAIDERS, LEADER, FOE_CLEAR, CAPTURING, DONE
    }

    private record NoteStep(String party, String omenSuffix, String label, String shot, Phase after) {
        // 一张字条：哪张队伍表的、omen 后面接什么、记进文字记录的名目、截成哪张、截完去哪一段。
    }

    CrisisOmenShots(List<Component> messages, String playerName, Consumer<String> done) {
        // 聊天由气泡用例统一收着；做完或失败都交回气泡用例写报告。
        this.messages = messages;
        this.playerName = playerName;
        this.done = done;
    }

    String phase() {
        // 超时报告里写停在哪一段。
        return phase.name();
    }

    void tick(Minecraft client) {
        // 每个客户端刻推进一段；截图回调期间停着等。
        ticks++;
        if (phase == Phase.CAPTURING || phase == Phase.DONE) {
            return;
        }
        try {
            switch (phase) {
                case RUMOR -> awaitOmen(client);
                case RUMOR_HEARD -> awaitRumor(client);
                case NOTE -> awaitNote(client);
                case NOTE_SHOWN -> awaitTooltip(client);
                case NOTE_FACING -> awaitFacing(client);
                case STRIKE -> awaitStrike(client);
                case RAIDERS -> awaitRaiders(client);
                case LEADER -> awaitLeader(client);
                case FOE_CLEAR -> awaitFoeClear(client);
                default -> throw new IllegalStateException(phase.name());
            }
        } catch (Exception failure) {
            LOGGER.error("招敌截图停在 {}", phase, failure);
            finish(failure.toString());
        }
    }

    private void awaitOmen(Minecraft client) throws Exception {
        // 没排着的一场由 omen 先排上再给第一次；记录还没接上就隔一秒再试。
        // 头一回先定成正午、清掉原版刷出来的怪（世界时刻随上一局走，夜里截图看不清，来人也会和野怪混在一起）。
        if (command == null) {
            Files.writeString(TEXT, "");
            run(client, "time set noon");
            run(client, "kill @e[type=!minecraft:player,type=!minecraft:villager]");
            command = run(client, "meridian humanoid crisis omen " + playerName);
            return;
        }
        if (!command.isDone() || ticks - markTick < RETRY_TICKS) {
            return;
        }
        markTick = ticks;
        if (command.join() > COMMAND_FAILED) {
            phase = Phase.RUMOR_HEARD;
            return;
        }
        if (++tries >= OMEN_TRIES) {
            throw new IllegalStateException("omen 指令一直没成功");
        }
        command = run(client, "meridian humanoid crisis omen " + playerName);
    }

    private void awaitRumor(Minecraft client) throws Exception {
        // 村民说的是第一句传言：整行浅灰色，文字是语言文件里那句，不是翻译键；气泡画进完整一帧后截图。
        String rumor = Language.getInstance().getOrDefault(RUMOR_KEY);
        Optional<Component> heard = messages.stream()
                .filter(message -> message.getString().equals(SPEAKER_PREFIX + rumor)).findFirst();
        if (heard.isEmpty() || ticks - markTick < SETTLE_TICKS) {
            return;
        }
        if (rumor.equals(RUMOR_KEY) || !TextColor.fromLegacyFormat(ChatFormatting.GRAY)
                .equals(heard.get().getStyle().getColor())) {
            throw new IllegalStateException("传言聊天不对：" + heard.get() + " 样式 " + heard.get().getStyle());
        }
        note("rumor=" + heard.get().getString());
        command = null;
        tries = 0;
        capture(client, "crisis-rumor.png", Phase.NOTE);
    }

    private void awaitNote(Minecraft client) throws Exception {
        // 先在玩家和村民之间放一张床、设成重生点，再给征兆直到最后一次的字条落在床上（中间可能还有一句传言）。
        if (bed == null) {
            bed = client.getSingleplayerServer().submit(() -> placeBed(server(client))).join();
            markTick = ticks;
            return;
        }
        Optional<ItemEntity> found = noteOnBed(client);
        if (found.isPresent()) {
            noteItem = found.get();
            client.player.setXRot(NOTE_PITCH);
            client.gui.setScreen(new NoteTooltip(found.get().getItem()));
            markTick = ticks;
            phase = Phase.NOTE_SHOWN;
            return;
        }
        if ((command != null && !command.isDone()) || ticks - markTick < RETRY_TICKS) {
            return;
        }
        if (tries++ >= NOTE_TRIES) {
            throw new IllegalStateException("给了 " + NOTE_TRIES + " 次征兆，床上还是没有字条");
        }
        markTick = ticks;
        command = run(client, "meridian humanoid crisis omen " + playerName + noteStep.omenSuffix());
    }

    private void awaitTooltip(Minecraft client) throws Exception {
        // 悬停说明照原版取：名字是「字条」那个键的译文，下面三行都是译出来的字，不露翻译键。
        if (ticks - markTick < SETTLE_TICKS) {
            return;
        }
        ItemStack stack = ((NoteTooltip) client.gui.screen()).note;
        List<String> lines = Screen.getTooltipFromItem(client, stack).stream().map(Component::getString).toList();
        note(noteStep.label() + "=" + String.join(" / ", lines));
        String name = Language.getInstance().getOrDefault(NOTE_KEY);
        if (lines.size() != NOTE_LINES || !lines.getFirst().equals(name) || name.equals(NOTE_KEY)
                || lines.stream().anyMatch(line -> line.startsWith(KEY_PREFIX))) {
            throw new IllegalStateException("字条的悬停说明不对：" + lines);
        }
        command = null;
        markTick = ticks;
        phase = Phase.NOTE_FACING;
    }

    private void awaitFacing(Minecraft client) {
        // 掉落物一直在转，侧对镜头时只看得见一条锯齿边；等纸面转到对着镜头再截（转一圈 126 tick，等不到也照截）。
        if (facing(client) || ticks - markTick > FACING_TICKS) {
            capture(client, noteStep.shot(), noteStep.after());
        }
    }

    private boolean facing(Minecraft client) {
        // 原版掉落物绕竖轴转 ItemEntity.getSpin，纸面法线是 (sin, 0, cos)，和视线的水平方向几乎平行才算正对。
        float spin = ItemEntity.getSpin(noteItem.getAge(), noteItem.bobOffs);
        Vec3 look = client.player.getLookAngle().multiply(1.0, 0.0, 1.0).normalize();
        return Math.abs(Math.sin(spin) * look.x + Math.cos(spin) * look.z) >= FACING_DOT;
    }

    private void awaitStrike(Minecraft client) {
        // 关掉说明，切生存模式（原版的怪不追创造模式的玩家），给抗性免得被打倒，再叫排着的那一场立刻来人。
        if (command == null) {
            client.gui.setScreen(null);
            client.player.setXRot(0.0F);
            run(client, "gamemode survival " + playerName);
            run(client, "effect give " + playerName + " minecraft:resistance infinite 4 true");
            command = run(client, "meridian humanoid crisis strike " + playerName);
            return;
        }
        if (!command.isDone()) {
            return;
        }
        if (command.join() <= COMMAND_FAILED) {
            throw new IllegalStateException("strike 指令没叫来人");
        }
        command = null;
        markTick = ticks;
        phase = Phase.RAIDERS;
    }

    private void awaitRaiders(Minecraft client) throws Exception {
        // 头目由客户端按名号认出（名号是那个键的译文）；转头看它，走进 12 格（或等够 20 秒）就关掉 HUD，
        // 免得聊天挡住来人，等画面刷新后截一张。
        if (leader == null || leader.isRemoved()) {
            leader = leader(client).orElse(null);
            return;
        }
        client.player.lookAt(EntityAnchorArgument.Anchor.EYES, leader.getEyePosition());
        if (client.gui.hud.isHidden()) {
            if (ticks - markTick >= SETTLE_TICKS) {
                markTick = ticks;
                capture(client, "crisis-raiders.png", Phase.LEADER);
            }
            return;
        }
        boolean near = client.player.distanceTo(leader) <= RAIDERS_NEAR;
        if (!near && ticks - markTick < RAIDERS_FALLBACK_TICKS) {
            return;
        }
        note("leader=" + leader.getCustomName().getString() + " " + leader.getType().toShortString()
                + " distance=" + Math.round(client.player.distanceTo(leader)) + " party=" + party(client));
        client.gui.hud.toggle();
        markTick = ticks;
    }

    private void awaitLeader(Minecraft client) throws Exception {
        // 头目走进准星时原版才画名号；它一直不近身（唤魔者会隔着距离）就只记一笔，不算失败。HUD 先打开，聊天作旁证。
        if (client.gui.hud.isHidden()) {
            client.gui.hud.toggle();
        }
        if (leader.isRemoved() || ticks - markTick > LEADER_TICKS) {
            note("leader_tag=没走进准星");
            phase = Phase.FOE_CLEAR;
            return;
        }
        client.player.lookAt(EntityAnchorArgument.Anchor.EYES, leader.getEyePosition());
        if (client.crosshairPickEntity == leader) {
            note("leader_tag=在准星里");
            capture(client, "crisis-leader.png", Phase.FOE_CLEAR);
        }
    }

    private void awaitFoeClear(Minecraft client) {
        // 清掉这伙人和床上前一张字条（村民留着说传言）：头目一死这一场就打赢了结，才排得上仇家那一场。
        if (command == null) {
            command = run(client, "kill @e[type=!minecraft:player,type=!minecraft:villager]");
            return;
        }
        if (!command.isDone()) {
            return;
        }
        if (command.join() <= COMMAND_FAILED) {
            throw new IllegalStateException("kill 一个也没清掉");
        }
        command = null;
        tries = 0;
        markTick = ticks;
        noteStep = FOE_NOTE;
        phase = Phase.NOTE;
    }

    private Optional<Mob> leader(Minecraft client) {
        // 客户端世界里名号是头目名号译文的那一只。
        String name = Language.getInstance().getOrDefault(LEADER_KEY);
        if (name.equals(LEADER_KEY)) {
            throw new IllegalStateException("头目名号没有译文：" + LEADER_KEY);
        }
        AABB around = client.player.getBoundingBox().inflate(RAIDERS_RADIUS);
        return client.level.getEntitiesOfClass(Mob.class, around,
                mob -> mob.hasCustomName() && mob.getCustomName().getString().equals(name)).stream().findFirst();
    }

    private String party(Minecraft client) {
        // 头目附近的灾民一路生人，按物种数一数，写进文字记录。
        AABB around = leader.getBoundingBox().inflate(RAIDERS_RADIUS);
        return client.level.getEntitiesOfClass(Mob.class, around, mob -> mob != leader && !mob.hasCustomName()
                && mob.getType().toShortString().matches("vindicator|pillager|evoker")).stream()
                .collect(Collectors.groupingBy(mob -> mob.getType().toShortString(), Collectors.counting()))
                .toString();
    }

    private Optional<ItemEntity> noteOnBed(Minecraft client) {
        // 床上那一格附近、名字是「字条」译文、正文头一行是这张队伍表那一行译文的掉落物。
        String name = Language.getInstance().getOrDefault(NOTE_KEY);
        String first = Language.getInstance().getOrDefault(noteKey(noteStep.party(), 1));
        AABB around = new AABB(bed).inflate(NOTE_RADIUS);
        return client.level.getEntitiesOfClass(ItemEntity.class, around, item -> {
            List<Component> lines = Screen.getTooltipFromItem(client, item.getItem());
            return lines.size() > 1 && lines.get(0).getString().equals(name) && lines.get(1).getString().equals(first);
        }).stream().findFirst();
    }

    private static String noteKey(String party, int line) {
        // Threatening 由队伍表推字条正文的键：crisis.<命名空间>.<名字>.omen.note.<第几行>。
        String[] parts = party.split(":", 2);
        return KEY_PREFIX + parts[0] + "." + parts[1] + ".omen.note." + line;
    }

    private static BlockPos placeBed(ServerPlayer player) {
        // 床脚在玩家正前方 2 格，床头朝前；设成重生点，字条就该留在床上。
        Direction facing = player.getDirection();
        BlockPos foot = BlockPos.containing(player.position()
                .add(Vec3.directionFromRotation(0.0F, player.getYRot()).scale(BED_DISTANCE)));
        BlockState state = Blocks.BED.red().defaultBlockState().setValue(BedBlock.FACING, facing);
        player.level().setBlock(foot, state.setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_ALL);
        player.level().setBlock(foot.relative(facing), state.setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
        var respawn = LevelData.RespawnData.of(player.level().dimension(), foot, 0.0F, 0.0F);
        player.setRespawnPosition(new ServerPlayer.RespawnConfig(respawn, true /* forced */), false /* showMessage */);
        return foot;
    }

    private static ServerPlayer server(Minecraft client) {
        // 单人世界里和客户端玩家同一个 UUID 的服务端玩家。
        return client.getSingleplayerServer().getPlayerList().getPlayer(client.player.getUUID());
    }

    static CompletableFuture<Integer> run(Minecraft client, String command) {
        // 在服务端线程用服务器的最高权限来源直接交给指令树执行：返回值就是结果，指令里抛的异常原样传回来
        // （正式入口会把异常吞成一句「意外错误」）。
        MinecraftServer server = client.getSingleplayerServer();
        return server.submit(() -> {
            try {
                return server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack());
            } catch (CommandSyntaxException invalid) {
                throw new IllegalStateException(command + "：" + invalid.getMessage(), invalid);
            }
        });
    }

    private void capture(Minecraft client, String name, Phase next) {
        // 拷贝完成后存盘再进下一段；截完最后一张就收尾。
        phase = Phase.CAPTURING;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(SCREENS.resolve(name));
                phase = next;
                if (next == Phase.DONE) {
                    finish(null);
                }
            } catch (Exception failure) {
                finish(failure.toString());
            }
        });
    }

    private static void note(String line) throws Exception {
        // 客户端看到的文字一行一条记下来，和截图放在一起给人看。
        Files.writeString(TEXT, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private void finish(String failure) {
        // 交回气泡用例写报告；停在 DONE 以后刻里不再推进，所以只交回一次。
        phase = Phase.DONE;
        done.accept(failure);
    }

    /** 只画字条悬停说明的界面：不压暗画面，床和字条留在说明后面；不暂停单人世界。 */
    private static final class NoteTooltip extends Screen {
        private static final int LEFT_DIVISOR = 2;
        private static final int TOP_DIVISOR = 4;
        private final ItemStack note;

        NoteTooltip(ItemStack note) {
            // 拿客户端掉落物上的那一份，名字和三行字随物品组件同步过来。
            super(Component.empty());
            this.note = note.copy();
        }

        @Override
        public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            // 不压暗、不模糊。
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            // 说明放在画面中上，避开床和字条。
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
            graphics.setTooltipForNextFrame(font, note, width / LEFT_DIVISOR, height / TOP_DIVISOR);
        }

        @Override
        public boolean isPauseScreen() {
            // 单人世界不暂停，来人和字条照常同步。
            return false;
        }
    }
}
