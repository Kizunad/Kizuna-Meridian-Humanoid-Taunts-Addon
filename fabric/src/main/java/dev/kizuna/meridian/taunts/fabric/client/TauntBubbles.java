package dev.kizuna.meridian.taunts.fabric.client;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 说话气泡：每个实体只留最新一句，显示 4 秒。像素风圆角框加底部正中的小尾巴，半透明白底黑字、无描边，
 * 跟随实体插值位置、始终面向镜头，与镜头相距超过 16 格不画，被方块遮挡时照常遮挡。
 */
final class TauntBubbles {
    static final long VISIBLE_MILLIS = 4000;
    private static final double MAX_DISTANCE = 16.0;
    private static final double MAX_DISTANCE_SQUARED = MAX_DISTANCE * MAX_DISTANCE;
    /** 与原版名牌相同的世界缩放：1 个字体像素 = 0.025 格。 */
    private static final float SCALE = 0.025F;
    /** 尾巴尖端离头顶的高度，在原版名牌之上。 */
    private static final float HEAD_CLEARANCE = 0.6F;
    /** 折行宽度；行尾挂一个避头标点时可超出一个字宽。 */
    static final int MAX_WIDTH = 120;
    private static final int LINE_HEIGHT = 10;
    /** 字形实际占 8 像素高，行高里余下的 2 像素是行距，末行不计。 */
    private static final int GLYPH_HEIGHT = 8;
    /** 文字四周留白，单位为字体像素。 */
    private static final int PADDING = 2;
    /** 圆角：框的最外两行逐行内缩 2、1 像素，四角各缺一个小台阶，与原版提示框同一画法。 */
    private static final int[] CORNER_INSETS = {2, 1};
    /** 尾巴自上而下每行的半宽：4 行高，8、6、4、2 像素宽，尖端对准头顶。 */
    private static final int[] TAIL_HALF_WIDTHS = {4, 3, 2, 1};
    private static final int TEXT_COLOR = 0xFF000000;
    static final int BACKGROUND_COLOR = 0xC0FFFFFF;
    /** 文字不再自带底色，底色由气泡形状画出。 */
    private static final int NO_TEXT_BACKGROUND = 0;
    /** 文字不描边。 */
    private static final int NO_OUTLINE = 0;
    /** 与原版文字底色相同：比字形远 0.01 个字体像素，文字始终压在底色上；26.2 的名牌坐标系里远离镜头是 -z。 */
    private static final float BACKGROUND_DEPTH = -0.01F;
    private static final int FULL_BRIGHT = LightCoordsUtil.FULL_BRIGHT;
    /** 每帧提取出的气泡挂在关卡渲染状态上，提交阶段只读这份数据。 */
    private static final RenderStateDataKey<List<BubbleState>> EXTRACTED =
            RenderStateDataKey.create(() -> "meridian_humanoid_taunts:bubbles");
    private final Map<Integer, Bubble> bubbles = new HashMap<>();

    void show(int entityId, Component line, long now) {
        bubbles.put(entityId, new Bubble(line, now + VISIBLE_MILLIS));
    }

    void clear() {
        bubbles.clear();
    }

    void extract(LevelExtractionContext context) {
        // 先丢掉过期气泡；说话者已卸载的气泡保留到过期，重新进入视野时继续显示。
        // 26.2 先把游戏状态提取成渲染状态再提交：这里取说话者的插值位置并折好行，每帧都覆盖上一帧的数据。
        long now = Util.getMillis();
        bubbles.values().removeIf(bubble -> bubble.expiresAt() <= now);
        Font textRenderer = Minecraft.getInstance().font;
        Vec3 camera = context.camera().position();
        List<BubbleState> states = new ArrayList<>();
        for (Map.Entry<Integer, Bubble> entry : bubbles.entrySet()) {
            Entity speaker = context.level().getEntity(entry.getKey());
            if (speaker == null) {
                continue;
            }
            // 与原版提取实体时同一个插值进度，气泡跟住模型；游戏冻结时被冻结的实体停在当前刻位置。
            boolean ignoreFrozenGame = !context.level().tickRateManager().isEntityFrozen(speaker);
            float partialTick = context.deltaTracker().getGameTimeDeltaPartialTick(ignoreFrozenGame);
            Vec3 position = speaker.getPosition(partialTick);
            if (position.distanceToSqr(camera) <= MAX_DISTANCE_SQUARED) {
                states.add(new BubbleState(position, speaker.getBbHeight(), entry.getValue().layout(textRenderer)));
            }
        }
        context.levelState().setData(EXTRACTED, List.copyOf(states));
    }

    void submit(LevelRenderContext context) {
        // 按提取的数据提交底色形状与文字，和原版名牌一样在实体特性之后统一绘制。
        List<BubbleState> states = context.levelState().getData(EXTRACTED);
        if (states == null) {
            return;
        }
        CameraRenderState camera = context.levelState().cameraRenderState;
        for (BubbleState state : states) {
            draw(context.poseStack(), context.submitNodeCollector(), camera, state);
        }
    }

    private static void draw(PoseStack matrices, SubmitNodeCollector collector, CameraRenderState camera,
            BubbleState state) {
        // 坐标系与 26.2 原版名牌一致（镜头相对、面向镜头、Y 轴向下）；原点是尾巴尖端，气泡框与文字自下而上堆在其上。
        List<FormattedCharSequence> rows = state.layout().rows();
        float halfWidth = state.layout().widest() / 2.0F + PADDING;
        int textHeight = (rows.size() - 1) * LINE_HEIGHT + GLYPH_HEIGHT;
        int bottom = -TAIL_HALF_WIDTHS.length;
        int top = bottom - textHeight - 2 * PADDING;
        Vec3 offset = state.position().subtract(camera.pos);
        matrices.pushPose();
        matrices.translate(offset.x, offset.y + state.height() + HEAD_CLEARANCE, offset.z);
        matrices.mulPose(camera.orientation);
        matrices.scale(SCALE, -SCALE, SCALE);
        collector.submitCustomGeometry(matrices, RenderTypes.textBackground(),
                (pose, background) -> drawShape(background, pose, halfWidth, top, bottom));
        drawRows(matrices, collector, state.layout(), top + PADDING);
        matrices.popPose();
    }

    static List<FormattedCharSequence> rows(Component line, Font textRenderer) {
        // 按 120 像素折行并守避头规则；单字宽度与原版折行一样按字形和样式量。
        StringSplitter handler = textRenderer.getSplitter();
        return TauntBubbleLines.wrap(line, MAX_WIDTH,
                (codePoint, style) -> handler.stringWidth(FormattedCharSequence.codepoint(codePoint, style)));
    }

    private static void drawShape(VertexConsumer buffer, PoseStack.Pose position, float halfWidth,
                                  int top, int bottom) {
        // 圆角框按横条拼出：上下各两行逐行内缩，中间整宽；各条互不重叠，半透明底色不会叠深。
        int corners = CORNER_INSETS.length;
        for (int row = 0; row < corners; row++) {
            float rowHalfWidth = halfWidth - CORNER_INSETS[row];
            fill(buffer, position, rowHalfWidth, top + row, top + row + 1);
            fill(buffer, position, rowHalfWidth, bottom - row - 1, bottom - row);
        }
        fill(buffer, position, halfWidth, top + corners, bottom - corners);
        // 尾巴从框底边向下逐行收窄，末行底边就是原点。
        for (int row = 0; row < TAIL_HALF_WIDTHS.length; row++) {
            fill(buffer, position, TAIL_HALF_WIDTHS[row], bottom + row, bottom + row + 1);
        }
    }

    private static void fill(VertexConsumer buffer, PoseStack.Pose position, float halfWidth, float top, float bottom) {
        // 水平居中的一条；绕序与原版文字底色相同：左下、右下、右上、左上。
        corner(buffer, position, -halfWidth, bottom);
        corner(buffer, position, halfWidth, bottom);
        corner(buffer, position, halfWidth, top);
        corner(buffer, position, -halfWidth, top);
    }

    private static void corner(VertexConsumer buffer, PoseStack.Pose position, float x, float y) {
        // 26.2 的顶点写法：位置、颜色、光照依次设置，不再单独结束顶点。
        buffer.addVertex(position, x, y, BACKGROUND_DEPTH)
                .setColor(BACKGROUND_COLOR)
                .setLight(FULL_BRIGHT);
    }

    private static void drawRows(PoseStack matrices, SubmitNodeCollector collector, Layout layout, int top) {
        // 每行各自居中，行距与原折行一致。
        List<FormattedCharSequence> rows = layout.rows();
        for (int row = 0; row < rows.size(); row++) {
            FormattedCharSequence text = rows.get(row);
            float left = -layout.widths()[row] / 2.0F;
            collector.submitText(matrices, left, top + row * LINE_HEIGHT, text, false /* shadow */,
                    Font.DisplayMode.NORMAL, FULL_BRIGHT, TEXT_COLOR, NO_TEXT_BACKGROUND, NO_OUTLINE);
        }
    }

    /** 一条气泡；折行和各行宽度在第一次要画时算好，之后每帧复用，一句话只显示几秒，期间不随资源重载重排。 */
    private static final class Bubble {
        private final Component line;
        private final long expiresAt;
        private Layout layout;

        Bubble(Component line, long expiresAt) {
            this.line = line;
            this.expiresAt = expiresAt;
        }

        long expiresAt() {
            return expiresAt;
        }

        Layout layout(Font textRenderer) {
            if (layout == null) {
                List<FormattedCharSequence> rows = rows(line, textRenderer);
                int[] widths = new int[rows.size()];
                int widest = 0;
                for (int row = 0; row < widths.length; row++) {
                    widths[row] = textRenderer.width(rows.get(row));
                    widest = Math.max(widest, widths[row]);
                }
                layout = new Layout(rows, widths, widest);
            }
            return layout;
        }
    }

    /** 折好的行、各行宽度和最宽一行的宽度，单位为字体像素。 */
    private record Layout(List<FormattedCharSequence> rows, int[] widths, int widest) { }

    /** 一帧里要画的气泡：说话者插值后的脚下位置、碰撞箱高度与排好的文字。 */
    private record BubbleState(Vec3 position, float height, Layout layout) { }
}
