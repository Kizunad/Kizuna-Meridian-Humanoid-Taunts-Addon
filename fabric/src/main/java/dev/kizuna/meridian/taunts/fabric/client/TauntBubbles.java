package dev.kizuna.meridian.taunts.fabric.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
    /** 与原版文字底色相同：比字形远 0.01 个字体像素，文字始终压在底色上。 */
    private static final float BACKGROUND_DEPTH = 0.01F;
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;
    private final Map<Integer, Bubble> bubbles = new HashMap<>();

    void show(int entityId, Component line, long now) {
        bubbles.put(entityId, new Bubble(line, now + VISIBLE_MILLIS));
    }

    void clear() {
        bubbles.clear();
    }

    void render(WorldRenderContext context) {
        // 先丢掉过期气泡；说话者已卸载的气泡保留到过期，重新进入视野时继续显示。
        long now = Util.getMillis();
        bubbles.values().removeIf(bubble -> bubble.expiresAt() <= now);
        Font textRenderer = Minecraft.getInstance().font;
        for (Map.Entry<Integer, Bubble> entry : bubbles.entrySet()) {
            Entity speaker = context.world().getEntity(entry.getKey());
            if (speaker != null) {
                draw(context, textRenderer, speaker, entry.getValue().line());
            }
        }
    }

    private static void draw(WorldRenderContext context, Font textRenderer, Entity speaker, Component line) {
        // 坐标系与原版名牌一致（镜头相对、面向镜头、Y 轴向下）；原点是尾巴尖端，气泡框与文字自下而上堆在其上。
        Camera camera = context.camera();
        Vec3 offset = speaker.getPosition(context.tickDelta()).subtract(camera.getPosition());
        if (offset.lengthSqr() > MAX_DISTANCE_SQUARED) {
            return;
        }
        List<FormattedCharSequence> rows = rows(line, textRenderer);
        int widest = 0;
        for (FormattedCharSequence row : rows) {
            widest = Math.max(widest, textRenderer.width(row));
        }
        float halfWidth = widest / 2.0F + PADDING;
        int textHeight = (rows.size() - 1) * LINE_HEIGHT + GLYPH_HEIGHT;
        int bottom = -TAIL_HALF_WIDTHS.length;
        int top = bottom - textHeight - 2 * PADDING;
        PoseStack matrices = context.matrixStack();
        matrices.pushPose();
        matrices.translate(offset.x, offset.y + speaker.getBbHeight() + HEAD_CLEARANCE, offset.z);
        matrices.mulPose(camera.rotation());
        matrices.scale(-SCALE, -SCALE, SCALE);
        Matrix4f position = matrices.last().pose();
        VertexConsumer background = context.consumers().getBuffer(RenderType.textBackground());
        drawShape(background, position, halfWidth, top, bottom);
        drawRows(context, textRenderer, position, rows, top + PADDING);
        matrices.popPose();
    }

    static List<FormattedCharSequence> rows(Component line, Font textRenderer) {
        // 按 120 像素折行并守避头规则；单字宽度与原版折行一样按字形和样式量。
        StringSplitter handler = textRenderer.getSplitter();
        return TauntBubbleLines.wrap(line, MAX_WIDTH,
                (codePoint, style) -> handler.stringWidth(FormattedCharSequence.codepoint(codePoint, style)));
    }

    private static void drawShape(VertexConsumer buffer, Matrix4f position, float halfWidth, int top, int bottom) {
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

    private static void fill(VertexConsumer buffer, Matrix4f position, float halfWidth, float top, float bottom) {
        // 水平居中的一条；绕序与原版文字底色相同：左下、右下、右上、左上。
        corner(buffer, position, -halfWidth, bottom);
        corner(buffer, position, halfWidth, bottom);
        corner(buffer, position, halfWidth, top);
        corner(buffer, position, -halfWidth, top);
    }

    private static void corner(VertexConsumer buffer, Matrix4f position, float x, float y) {
        buffer.vertex(position, x, y, BACKGROUND_DEPTH)
                .color(BACKGROUND_COLOR)
                .uv2(FULL_BRIGHT)
                .endVertex();
    }

    private static void drawRows(WorldRenderContext context, Font textRenderer, Matrix4f position,
            List<FormattedCharSequence> rows, int top) {
        // 每行各自居中，行距与原折行一致。
        for (int row = 0; row < rows.size(); row++) {
            FormattedCharSequence text = rows.get(row);
            float left = -textRenderer.width(text) / 2.0F;
            textRenderer.drawInBatch(text, left, top + row * LINE_HEIGHT, TEXT_COLOR, false /* shadow */, position,
                    context.consumers(), Font.DisplayMode.NORMAL, NO_TEXT_BACKGROUND, FULL_BRIGHT);
        }
    }

    private record Bubble(Component line, long expiresAt) { }
}
