package dev.kizuna.meridian.taunts.fabric.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;

/**
 * 排在气泡截图前面：完整装配里有 Threatening 的首批五个出身，进服时出身附属自己弹出选出身的界面。
 * 先等它弹出来截一张候选总览；再五个出身各用出身附属的调试指令定一次、用 {@code meridian body origin} 打开
 * 选定后只读的那一张，各截一张；最后合上界面，交回气泡用例。截图写进 {@code bubble-screens/origin-*.png}，
 * 每张对应的出身名字写进 {@code origins.txt}。
 */
final class OriginCardShots {
    // 界面类在出身附属里，Taunts 不编译依赖它，按类名认。
    private static final String SCREEN_CLASS = "dev.kizuna.meridian.origin.fabric.client.OriginScreen";
    private static final String NAMESPACE = "meridian_humanoid";
    // 最后定成寻常农户：没有仇家，后面风声招来的那一场不受出身的仇家打扰。
    private static final List<String> ORIGINS = List.of("orphan", "apprentice", "disgraced", "merchant", "farmer");
    private static final int SETTLE_TICKS = 20;
    private static final int OFFER_TICKS = 600;
    private static final int OPEN_TICKS = 200;
    private static final int COMMAND_FAILED = 0;
    private static final Path SCREENS = Path.of("../bubble-screens");
    private static final Path TEXT = SCREENS.resolve("origins.txt");
    private final String playerName;
    private final Consumer<String> done;
    private Phase phase = Phase.OFFER;
    private int ticks;
    private int markTick;
    private int sentTick;
    private int index;
    private CompletableFuture<Integer> command;

    private enum Phase { OFFER, CHOOSE, OPENED, CLOSING, CAPTURING, DONE }

    OriginCardShots(String playerName, Consumer<String> done) {
        // 做完交回 null，失败交回原因，都由气泡用例写报告。
        this.playerName = playerName;
        this.done = done;
    }

    String phase() {
        // 超时报告里写停在哪一段、第几个出身。
        return phase + (phase == Phase.OFFER ? "" : "@" + ORIGINS.get(Math.min(index, ORIGINS.size() - 1)));
    }

    void tick(Minecraft client) {
        // 每个客户端刻推进一段；截图回调期间停着等。
        ticks++;
        if (phase == Phase.CAPTURING || phase == Phase.DONE) {
            return;
        }
        try {
            switch (phase) {
                case OFFER -> awaitOffer(client);
                case CHOOSE -> choose(client);
                case OPENED -> awaitOpened(client);
                case CLOSING -> close(client);
                default -> throw new IllegalStateException(phase.name());
            }
        } catch (Exception failure) {
            finish(failure.toString());
        }
    }

    private void awaitOffer(Minecraft client) throws Exception {
        // 进服后服务端等身体建好才发候选，界面自己弹出来；排好版、画过几帧再截总览。
        if (!originScreen(client.gui.screen())) {
            if (ticks > OFFER_TICKS) {
                throw new IllegalStateException("进服以后一直没弹出选出身的界面");
            }
            return;
        }
        if (markTick == 0) {
            markTick = ticks;
            Files.createDirectories(SCREENS);
            Files.writeString(TEXT, "");
        }
        if (ticks - markTick >= SETTLE_TICKS) {
            note("origin-offer.png=进服弹出的候选");
            capture(client, "origin-offer.png", Phase.CHOOSE);
        }
    }

    private void choose(Minecraft client) {
        // 合上界面，用调试指令定成这一个出身（和玩家选定一样发天赋）；定成了再让玩家用指令打开自己的那一张。
        if (command == null) {
            client.gui.setScreen(null);
            command = CrisisOmenShots.run(client,
                    "meridian debug origin set " + playerName + " " + NAMESPACE + ":" + ORIGINS.get(index));
            return;
        }
        if (!command.isDone()) {
            return;
        }
        if (command.join() <= COMMAND_FAILED) {
            throw new IllegalStateException("定不成出身：" + ORIGINS.get(index));
        }
        command = null;
        client.player.connection.sendCommand("meridian body origin");
        sentTick = ticks;
        markTick = ticks;
        phase = Phase.OPENED;
    }

    private void awaitOpened(Minecraft client) throws Exception {
        // 打开的是选定后只读的那一张；从界面出现起排好版、画过几帧再截（服务端卡的时候界面来得晚）。
        if (!originScreen(client.gui.screen())) {
            if (ticks - sentTick > OPEN_TICKS) {
                throw new IllegalStateException("meridian body origin 没打开出身界面：" + ORIGINS.get(index));
            }
            markTick = ticks;
            return;
        }
        if (ticks - markTick < SETTLE_TICKS) {
            return;
        }
        String origin = ORIGINS.get(index);
        String shot = "origin-" + origin + ".png";
        note(shot + "=" + Language.getInstance().getOrDefault("origin." + NAMESPACE + "." + origin + ".name"));
        index++;
        capture(client, shot, index < ORIGINS.size() ? Phase.CHOOSE : Phase.CLOSING);
    }

    private void close(Minecraft client) {
        // 合上界面，过几帧（抬头显示恢复）再交回气泡用例。
        if (client.gui.screen() != null) {
            client.gui.setScreen(null);
            markTick = ticks;
            return;
        }
        if (ticks - markTick >= SETTLE_TICKS) {
            finish(null);
        }
    }

    private static boolean originScreen(Screen screen) {
        // 开着的是出身附属的选出身界面。
        return screen != null && screen.getClass().getName().equals(SCREEN_CLASS);
    }

    private void capture(Minecraft client, String name, Phase next) {
        // 拷贝完成后存盘再进下一段；结束不在回调里做，回调在显存回读途中。
        phase = Phase.CAPTURING;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(SCREENS.resolve(name));
                markTick = ticks;
                phase = next;
            } catch (Exception failure) {
                finish(failure.toString());
            }
        });
    }

    private static void note(String line) throws Exception {
        // 每张截图对应哪个出身记一行，给看截图的人对照。
        Files.writeString(TEXT, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private void finish(String failure) {
        // 只交回一次。
        phase = Phase.DONE;
        done.accept(failure);
    }
}
