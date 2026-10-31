package dev.kizuna.meridian.taunts.fabric.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.kizuna.meridian.taunts.fabric.BubblePayload;
import dev.kizuna.meridian.taunts.server.TauntCatalog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * 单人世界里由服务端线程让玩家打一个关掉 AI 的村民，经正式聊天包与气泡包驱动客户端：
 * 聊天按简体中文解析后应是浅灰色的「<村民> …玩家名…」；气泡出现后与 3.5 秒时各截一张图，
 * 4 秒后再截一张，画面中上部的像素差证明气泡确实画出、仍在且按时消失，变化区域的轮廓证明它是圆角加尾巴的气泡。
 * 最后用真实字体检查整张台词表的折行（标点不落行首、开引号不留行尾、名字不拆开），
 * 再经正式气泡通道给同一村民依次换上两句原版会折错的台词，各截一张图。
 */
public final class TauntBubbleClientTest implements ClientModInitializer {
    private static final int READY_TICKS = 60;
    private static final int TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 20;
    private static final double SPEAKER_DISTANCE = 5.0;
    private static final float HALF_TURN = 180.0F;
    private static final float LEVEL_PITCH = 0.0F;
    private static final float LIGHT_HIT = 1.0F;
    private static final long LATE_MILLIS = 3500;
    private static final long GONE_MARGIN_MILLIS = 250;
    /** 探测区是画面水平居中、上半部分的一块，气泡在 5 格外时整块落在其中，聊天栏不在其中。 */
    private static final double PROBE_LEFT = 0.3;
    private static final double PROBE_RIGHT = 0.7;
    private static final double PROBE_TOP = 0.1;
    private static final double PROBE_BOTTOM = 0.5;
    private static final int CHANGED_LUMINANCE = 48;
    private static final int MIN_CHANGED_PIXELS = 2000;
    private static final int CHANNEL_MASK = 0xFF;
    private static final int GREEN_SHIFT = 8;
    private static final int RED_SHIFT = 16;
    private static final int ALPHA_SHIFT = 24;
    private static final int CHANNELS = 3;
    private static final int[] CHANNEL_SHIFTS = {0, GREEN_SHIFT, RED_SHIFT};
    /** 轮廓只认气泡底色：像素等于原画面与白色按底色不透明度混合，每通道容差 10（两次截图间天空略有漂移）。 */
    private static final float BACKGROUND_ALPHA =
            (TauntBubbles.BACKGROUND_COLOR >>> ALPHA_SHIFT) / (float) CHANNEL_MASK;
    private static final int BLEND_TOLERANCE = 10;
    /** 混合后亮度至少升 24，排除原本就接近白色、盖上底色也看不出的像素。 */
    private static final int MIN_WHITENING = 24;
    /** 尾巴尖那一行的宽度不超过气泡宽度的 1/4，中心与气泡中心相差不超过气泡宽度的 1/20。 */
    private static final int TIP_WIDTH_DIVISOR = 4;
    private static final int TIP_CENTER_DIVISOR = 20;
    private static final Path SCREENS = Path.of("../bubble-screens");
    private static final Path REPORT = Path.of("../clienttest-results.txt");
    private static final Path CHAT_TEXT = SCREENS.resolve("chat.txt");
    private static final String LANGUAGE = "zh_cn";
    private static final String SPEAKER_PREFIX = "<村民> ";
    /** 用户截图里的那句，原版折行把句末「！」单独折到第三行；名字写死，断点不随测试玩家名变化。 */
    private static final String WRAP_NAME = "Player131";
    private static final String WRAP_LINE = "哼！" + WRAP_NAME + "，村长要是知道了，连夜把你列进黑名单！";
    private static final String CATALOG = "/data/meridian_humanoid_taunts/taunts/zh_cn.json";
    private static final Path WRAP_TEXT = SCREENS.resolve("wrap.txt");
    private static final String WRAP_SHOT = "bubble-wrap.png";
    private static final String NAME_SHOT = "bubble-wrap-name.png";
    private final List<Component> messages = new CopyOnWriteArrayList<>();
    private Stage stage = Stage.WAITING;
    private int ticks;
    private int heardTick;
    private int wrapTick;
    private int villagerId;
    private int shotIndex;
    private List<WrapShot> shots;
    private long heardMillis;
    private String playerName;
    private CompletableFuture<Integer> strike;
    private NativeImage visibleImage;
    private NativeImage lateImage;
    private NativeImage hiddenImage;

    private enum Stage { WAITING, STRIKING, SETTLING, SHOWN, LATE, WRAPPING, CAPTURING, DONE }

    @Override
    public void onInitializeClient() {
        // 截图在客户端刻末尾发起主帧缓冲拷贝，此时其中是上一帧完整画面；
        // 26.2 的截图在显存拷贝完成后才回调，等待回调期间停在 CAPTURING。
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> messages.add(message));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        ticks++;
        if (stage == Stage.DONE) {
            return;
        }
        if (ticks > TIMEOUT_TICKS) {
            finish("超时，停在 " + stage);
            return;
        }
        // 26.2 的加载遮罩与提示框挂在 client.gui 上。
        if (ticks < READY_TICKS || client.player == null || client.gui.overlay() != null
                || client.getSingleplayerServer() == null) {
            return;
        }
        client.gui.toastManager().clear();
        if (stage == Stage.CAPTURING) {
            return;
        }
        try {
            switch (stage) {
                case WAITING -> start(client);
                case STRIKING -> awaitChat(client);
                case SETTLING -> awaitSettled(client);
                case SHOWN -> awaitLate(client);
                case LATE -> awaitGone(client);
                case WRAPPING -> awaitWrapped(client);
                default -> throw new IllegalStateException(stage.name());
            }
        } catch (Exception failure) {
            finish(failure.toString());
        }
    }

    private void start(Minecraft client) throws Exception {
        // 平视前方；村民放在视线正前方 5 格、面朝玩家，伤害在服务端线程按玩家攻击结算。
        // 26.2 的 F1 隐藏开关改由 client.gui.hud 维护，界面同样经 client.gui 关闭；玩家档案改为记录类型。
        if (client.gui.hud.isHidden()) {
            client.gui.hud.toggle();
        }
        client.options.pauseOnLostFocus = false;
        client.options.cloudStatus().set(CloudStatus.OFF);
        client.gui.setScreen(null);
        client.player.setXRot(LEVEL_PITCH);
        Files.createDirectories(SCREENS);
        Files.deleteIfExists(WRAP_TEXT);
        playerName = client.player.getGameProfile().name();
        var server = client.getSingleplayerServer();
        UUID playerId = client.player.getUUID();
        strike = server.submit(() -> strike(server.getPlayerList().getPlayer(playerId)));
        stage = Stage.STRIKING;
    }

    private static int strike(ServerPlayer player) {
        // 26.2 创建实体要给出生成原因（只用于生成检查）；服务端玩家直接由 level() 给出所在世界。
        Villager villager = EntityTypes.VILLAGER.create(player.level(), EntitySpawnReason.COMMAND);
        Vec3 ahead = Vec3.directionFromRotation(LEVEL_PITCH, player.getYRot()).scale(SPEAKER_DISTANCE);
        Vec3 position = player.position().add(ahead);
        float facing = Mth.wrapDegrees(player.getYRot() + HALF_TURN);
        villager.snapTo(position.x, position.y, position.z, facing, LEVEL_PITCH);
        villager.setNoAi(true);
        player.level().addFreshEntity(villager);
        villager.hurtServer(player.level(), player.damageSources().playerAttack(player), LIGHT_HIT);
        return villager.getId();
    }

    private void awaitChat(Minecraft client) throws Exception {
        if (!strike.isDone()) {
            return;
        }
        villagerId = strike.join();
        Component chat = messages.stream()
                .filter(message -> message.getString().startsWith(SPEAKER_PREFIX))
                .findFirst()
                .orElse(null);
        if (chat == null) {
            return;
        }
        requireChineseChat(client, chat);
        heardTick = ticks;
        heardMillis = Util.getMillis();
        stage = Stage.SETTLING;
    }

    private void requireChineseChat(Minecraft client, Component chat) throws Exception {
        // 名字由客户端按简体中文解析；聊天整行浅灰色，台词里是玩家自己的名字，不能残留占位符或翻译键。
        String language = client.getLanguageManager().getSelected();
        if (!language.equals(LANGUAGE)) {
            throw new IllegalStateException("客户端语言不是简体中文：" + language);
        }
        String text = chat.getString();
        Files.writeString(CHAT_TEXT, text + "\n");
        if (!TextColor.fromLegacyFormat(ChatFormatting.GRAY).equals(chat.getStyle().getColor())) {
            throw new IllegalStateException("垃圾话聊天不是浅灰色：" + chat.getStyle());
        }
        if (!text.contains(playerName) || text.contains("{attacker}") || text.contains("entity.minecraft.")) {
            throw new IllegalStateException("聊天文本不对：" + text);
        }
    }

    private void awaitSettled(Minecraft client) {
        // 等击退停下、气泡已画进完整的一帧。
        if (ticks - heardTick < SETTLE_TICKS) {
            return;
        }
        capture(client, "bubble-visible.png", image -> {
            visibleImage = image;
            stage = Stage.SHOWN;
        });
    }

    private void awaitLate(Minecraft client) {
        // 3.5 秒时气泡应仍在；截图留到消失后一起比较。
        if (Util.getMillis() - heardMillis < LATE_MILLIS) {
            return;
        }
        capture(client, "bubble-late.png", image -> {
            lateImage = image;
            stage = Stage.LATE;
        });
    }

    private void awaitGone(Minecraft client) {
        // 4 秒后取消失后的截图，回调里比较像素与轮廓。
        if (Util.getMillis() - heardMillis < TauntBubbles.VISIBLE_MILLIS + GONE_MARGIN_MILLIS) {
            return;
        }
        capture(client, "bubble-hidden.png", image -> {
            hiddenImage = image;
            compareGone(client);
        });
    }

    private void compareGone(Minecraft client) throws Exception {
        // 显示、3.5 秒与消失三张图比较：像素变化够多、轮廓是圆角加尾巴，再检查台词表折行并开始折行截图。
        Bounds probe = probe(hiddenImage);
        int shown = changedPixels(visibleImage, hiddenImage, probe);
        int late = changedPixels(lateImage, hiddenImage, probe);
        Bounds outline = outline(visibleImage, hiddenImage, probe);
        Tip tip = tip(visibleImage, hiddenImage, outline);
        Files.writeString(SCREENS.resolve("probe.txt"), "visible=" + shown + "\nlate=" + late + "\n"
                + "outline=" + outline + "\n" + tip + "\n");
        if (shown < MIN_CHANGED_PIXELS || late < MIN_CHANGED_PIXELS) {
            throw new IllegalStateException("气泡区域像素变化不足：visible=" + shown + " late=" + late);
        }
        requireBubbleShape(visibleImage, hiddenImage, outline, tip);
        String splitNameLine = requireCatalogWraps(client.font);
        requireWrappedLine(WRAP_LINE, Fault.LEADING_PUNCTUATION, client.font);
        requireWrappedLine(splitNameLine, Fault.SPLIT_WORD, client.font);
        shots = List.of(new WrapShot(WRAP_LINE, WRAP_SHOT), new WrapShot(splitNameLine, NAME_SHOT));
        showShot(client);
    }

    private void showShot(Minecraft client) {
        // 在服务端线程经正式气泡通道下发，同一实体的新台词替换旧气泡。
        var server = client.getSingleplayerServer();
        UUID playerId = client.player.getUUID();
        int speakerId = villagerId;
        String line = shots.get(shotIndex).line();
        server.execute(() -> sendBubble(server.getPlayerList().getPlayer(playerId), speakerId, line));
        wrapTick = ticks;
        stage = Stage.WRAPPING;
    }

    private void awaitWrapped(Minecraft client) {
        // 换上的气泡画进完整一帧后截图，回调里比较。
        if (ticks - wrapTick < SETTLE_TICKS) {
            return;
        }
        String file = shots.get(shotIndex).file();
        capture(client, file, image -> compareWrapped(client, file, image));
    }

    private void compareWrapped(Minecraft client, String file, NativeImage captured) throws Exception {
        // 像素变化证明换上的气泡确实画出；两句都截完才结束。
        try (NativeImage wrapped = captured) {
            int changed = changedPixels(wrapped, hiddenImage, probe(hiddenImage));
            appendWrapNote(file + " changed=" + changed + "\n");
            if (changed < MIN_CHANGED_PIXELS) {
                throw new IllegalStateException("换上的气泡没有画出：" + file + " changed=" + changed);
            }
        }
        shotIndex++;
        if (shotIndex < shots.size()) {
            showShot(client);
            return;
        }
        finish(null);
    }

    private static void requireWrappedLine(String line, Fault expected, Font textRenderer) throws Exception {
        // 截图用例先确认原版折行确有这类问题，用例才有意义；新折行一类问题都不能有，且挂标点的行只超出一个字宽。
        List<String> vanilla = strings(textRenderer.split(Component.literal(line), TauntBubbles.MAX_WIDTH));
        List<String> wrapped = strings(TauntBubbles.rows(Component.literal(line), textRenderer));
        appendWrapNote("vanilla=" + vanilla + "\nwrapped=" + wrapped + "\n");
        if (!faults(vanilla).contains(expected)) {
            throw new IllegalStateException("原版折行没有出现 " + expected + "，截图用例失效：" + vanilla);
        }
        requireRows(line, wrapped, textRenderer);
    }

    private static String requireCatalogWraps(Font textRenderer) throws Exception {
        // 台词表每一句代入同一个名字，按真实字体折行都不能出问题；顺带按类数出原版折行出问题的句数，
        // 并挑出第一句原版会拆开名字的台词，供第二张截图使用。
        List<String> lines = catalogLines();
        var vanillaFaults = new EnumMap<Fault, Integer>(Fault.class);
        String splitNameLine = null;
        for (String line : lines) {
            Component text = Component.literal(line);
            requireRows(line, strings(TauntBubbles.rows(text, textRenderer)), textRenderer);
            EnumSet<Fault> vanilla = faults(strings(textRenderer.split(text, TauntBubbles.MAX_WIDTH)));
            for (Fault fault : vanilla) {
                vanillaFaults.merge(fault, 1, Integer::sum);
            }
            if (splitNameLine == null && vanilla.contains(Fault.SPLIT_WORD)) {
                splitNameLine = line;
            }
        }
        appendWrapNote("catalog=" + lines.size() + "\nvanilla_faults=" + vanillaFaults + "\n");
        if (splitNameLine == null) {
            throw new IllegalStateException("台词表里没有原版折行会拆开名字的句子，名字截图用例失效");
        }
        return splitNameLine;
    }

    private static void requireRows(String line, List<String> rows, Font textRenderer) {
        // 三类问题都不能有；超出 120 像素的行，超出部分不多于它末尾那个字的宽度。
        EnumSet<Fault> found = faults(rows);
        if (!found.isEmpty()) {
            throw new IllegalStateException("折行有问题 " + found + "：" + line + " → " + rows);
        }
        for (String row : rows) {
            String last = new String(Character.toChars(row.codePointBefore(row.length())));
            if (textRenderer.width(row) > TauntBubbles.MAX_WIDTH + textRenderer.width(last)) {
                throw new IllegalStateException("行宽超出一个字以上：" + line + " → " + row);
            }
        }
    }

    private static EnumSet<Fault> faults(List<String> rows) {
        // 逐个看相邻两行的接缝；首行开头的标点是台词本身的，不算折行问题。
        EnumSet<Fault> found = EnumSet.noneOf(Fault.class);
        for (int index = 1; index < rows.size(); index++) {
            String previous = rows.get(index - 1);
            int before = previous.codePointBefore(previous.length());
            int after = rows.get(index).codePointAt(0);
            if (TauntBubbleLines.noLineStart(after)) {
                found.add(Fault.LEADING_PUNCTUATION);
            }
            if (TauntBubbleLines.noLineEnd(before)) {
                found.add(Fault.TRAILING_OPENING);
            }
            if (TauntBubbleLines.wordPart(before) && TauntBubbleLines.wordPart(after)) {
                found.add(Fault.SPLIT_WORD);
            }
        }
        return found;
    }

    private static List<String> catalogLines() throws Exception {
        // 全部物种、两种说话对象的台词。
        try (InputStream input = TauntBubbleClientTest.class.getResourceAsStream(CATALOG)) {
            if (input == null) {
                throw new IllegalStateException("缺少台词文件 " + CATALOG);
            }
            TauntCatalog catalog = TauntCatalog.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            return catalog.speciesIds().stream()
                    .map(speciesId -> catalog.species(speciesId).orElseThrow())
                    .flatMap(taunts -> Stream.concat(taunts.toEntity().stream(), taunts.toPlayer().stream()))
                    .map(line -> line.before() + WRAP_NAME + line.after())
                    .toList();
        }
    }

    private static void sendBubble(ServerPlayer player, int speakerId, String line) {
        // 与正式发言同一个气泡包：实体网络 ID + 文本组件。
        ServerPlayNetworking.send(player, new BubblePayload(speakerId, Component.literal(line)));
    }

    private static void appendWrapNote(String note) throws Exception {
        // 折行结果逐段追加到 wrap.txt，开测时已清空。
        Files.writeString(WRAP_TEXT, note, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static List<String> strings(List<FormattedCharSequence> rows) {
        // 把行文本还原成字符串，比较断点。
        return rows.stream().map(TauntBubbleClientTest::string).toList();
    }

    private static String string(FormattedCharSequence row) {
        // 按码点逐字拼回，代理对字符也不会被拆开。
        var builder = new StringBuilder();
        row.accept((index, style, codePoint) -> {
            builder.appendCodePoint(codePoint);
            return true;
        });
        return builder.toString();
    }

    private static void requireBubbleShape(NativeImage shown, NativeImage hidden, Bounds outline, Tip tip) {
        // 圆角：轮廓外接框的上沿两角不在气泡里；尾巴：最下一行很窄，且落在气泡水平正中。
        boolean leftCorner = whitened(shown, hidden, outline.left(), outline.top());
        boolean rightCorner = whitened(shown, hidden, outline.right(), outline.top());
        if (leftCorner || rightCorner) {
            throw new IllegalStateException("气泡上沿是直角：" + outline);
        }
        int width = outline.right() - outline.left() + 1;
        if (tip.width() * TIP_WIDTH_DIVISOR > width) {
            throw new IllegalStateException("气泡底部没有收窄的尾巴：" + tip + " " + outline);
        }
        int center = (outline.left() + outline.right()) / 2;
        if (Math.abs(tip.center() - center) * TIP_CENTER_DIVISOR > width) {
            throw new IllegalStateException("尾巴不在气泡正中：" + tip + " " + outline);
        }
    }

    private static Bounds probe(NativeImage image) {
        // 探测区是画面水平居中、上半部分的一块，四边都包含在内。
        int left = (int) (image.getWidth() * PROBE_LEFT);
        int top = (int) (image.getHeight() * PROBE_TOP);
        int right = (int) (image.getWidth() * PROBE_RIGHT) - 1;
        int bottom = (int) (image.getHeight() * PROBE_BOTTOM) - 1;
        return new Bounds(left, top, right, bottom);
    }

    private static Bounds outline(NativeImage shown, NativeImage hidden, Bounds probe) {
        // 气泡底色像素的外接框，即气泡连同尾巴的轮廓范围；黑字在框内，不影响外接框。
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (int y = probe.top(); y <= probe.bottom(); y++) {
            for (int x = probe.left(); x <= probe.right(); x++) {
                if (whitened(shown, hidden, x, y)) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (right < left) {
            throw new IllegalStateException("探测区里找不到气泡轮廓");
        }
        return new Bounds(left, top, right, bottom);
    }

    private static Tip tip(NativeImage shown, NativeImage hidden, Bounds outline) {
        // 轮廓最下一行就是尾巴尖：数出底色像素的个数与横坐标平均值。
        long sum = 0;
        int width = 0;
        for (int x = outline.left(); x <= outline.right(); x++) {
            if (whitened(shown, hidden, x, outline.bottom())) {
                sum += x;
                width++;
            }
        }
        int center = (int) (sum / width);
        return new Tip(width, center);
    }

    private void capture(Minecraft client, String name, CaptureStep next) {
        // 拷贝完成后先存盘再交给下一步；回调里的失败同样结束测试并写入报告（重复关闭图像无害）。
        stage = Stage.CAPTURING;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try {
                image.writeToFile(SCREENS.resolve(name));
                next.accept(image);
            } catch (Exception failure) {
                image.close();
                finish(failure.toString());
            }
        });
    }

    private static int changedPixels(NativeImage shown, NativeImage hidden, Bounds probe) {
        // 统计探测区里亮度变化明显的像素；村民关了 AI、云已关，变化只来自气泡。
        int changed = 0;
        for (int y = probe.top(); y <= probe.bottom(); y++) {
            for (int x = probe.left(); x <= probe.right(); x++) {
                if (changed(shown, hidden, x, y, CHANGED_LUMINANCE)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    private static boolean changed(NativeImage shown, NativeImage hidden, int x, int y, int threshold) {
        // 26.2 公开的取像素入口按 ARGB 排列；亮度是三通道平均，与旧版按 ABGR 拆分结果相同。
        return Math.abs(luminance(shown.getPixel(x, y)) - luminance(hidden.getPixel(x, y))) >= threshold;
    }

    private static boolean whitened(NativeImage shown, NativeImage hidden, int x, int y) {
        // 三个通道都要符合与白色的混合结果，村民轻微移动或天空漂移造成的变化都不算。
        // 三个通道逐一同样检查，按 26.2 的 ARGB 排列取像素不影响结果。
        int after = shown.getPixel(x, y);
        int before = hidden.getPixel(x, y);
        for (int shift : CHANNEL_SHIFTS) {
            int beforeChannel = before >> shift & CHANNEL_MASK;
            int afterChannel = after >> shift & CHANNEL_MASK;
            float expected = beforeChannel + (CHANNEL_MASK - beforeChannel) * BACKGROUND_ALPHA;
            if (Math.abs(afterChannel - expected) > BLEND_TOLERANCE) {
                return false;
            }
        }
        return luminance(after) - luminance(before) >= MIN_WHITENING;
    }

    private static int luminance(int argb) {
        // 按 26.2 的 ARGB 排列拆出红绿蓝三通道。
        int red = argb >> RED_SHIFT & CHANNEL_MASK;
        int green = argb >> GREEN_SHIFT & CHANNEL_MASK;
        int blue = argb & CHANNEL_MASK;
        return (red + green + blue) / CHANNELS;
    }

    private void finish(String failure) {
        // 独立报告让断言与截图失败传递给 Gradle。
        stage = Stage.DONE;
        if (visibleImage != null) {
            visibleImage.close();
        }
        if (lateImage != null) {
            lateImage.close();
        }
        if (hiddenImage != null) {
            hiddenImage.close();
        }
        try {
            Files.writeString(REPORT, "success=" + (failure == null) + "\nerror=" + failure + "\n");
        } catch (Exception reportFailure) {
            throw new IllegalStateException(reportFailure);
        }
        Minecraft.getInstance().stop();
    }

    /** 屏幕像素外接框，四边都包含在内。 */
    private record Bounds(int left, int top, int right, int bottom) { }

    private record Tip(int width, int center) { }

    /** 一张折行截图：经气泡通道换上的台词与截图文件名。 */
    private record WrapShot(String line, String file) { }

    /** 相邻两行接缝处的三类折行问题。 */
    private enum Fault { LEADING_PUNCTUATION, TRAILING_OPENING, SPLIT_WORD }

    /** 截图回调里的下一步；可能因断言或写文件失败而抛出。 */
    @FunctionalInterface
    private interface CaptureStep {
        void accept(NativeImage image) throws Exception;
    }
}
