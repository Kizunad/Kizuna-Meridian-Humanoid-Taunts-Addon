package dev.kizuna.meridian.taunts.fabric.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * 气泡折行：逐字按宽度填满一行（与原版无空格时的断法相同），再按避头尾规则调整断点：
 * 句读号与结束引号、括号不出现在行首，开引号与左括号不留在行尾，英文与数字串（玩家名）不拆开。
 * 单个结束标点挂在上一行末尾，该行最多超出一个字宽；其余情况往回找最近的合规断点，
 * 把上一行末尾的字、开引号或整个英文串一起带到下一行。
 */
final class TauntBubbleLines {
    /** 不能出现在行首的标点：句读号、结束引号与括号、省略号、破折号，以及对应的半角标点。 */
    private static final String NO_LINE_START = "！？。，、；：」』）》】〉”’…—～!?.,;:)]}";
    /** 不能留在行尾的标点：开引号、左括号与左书名号，以及对应的半角标点。 */
    private static final String NO_LINE_END = "「『（《【〈“‘([{";
    /** 英文与数字串只认 ASCII 范围，与原版玩家名允许的字符一致。 */
    private static final int ASCII_LIMIT = 0x80;

    private TauntBubbleLines() {
        // 纯计算，不持有状态。
    }

    static List<FormattedCharSequence> wrap(Component line, float maxWidth, GlyphWidth width) {
        // 先摊平成带样式的单字，按断点切行；每个字保留原样式，名字组件的样式不丢。
        List<Glyph> glyphs = glyphs(line);
        int[] codePoints = new int[glyphs.size()];
        float[] widths = new float[glyphs.size()];
        for (int index = 0; index < glyphs.size(); index++) {
            Glyph glyph = glyphs.get(index);
            codePoints[index] = glyph.codePoint();
            widths[index] = width.of(glyph.codePoint(), glyph.style());
        }
        var rows = new ArrayList<FormattedCharSequence>();
        int start = 0;
        for (int end : rowEnds(codePoints, widths, maxWidth)) {
            rows.add(row(glyphs.subList(start, end)));
            start = end;
        }
        return List.copyOf(rows);
    }

    static List<Integer> rowEnds(int[] codePoints, float[] widths, float maxWidth) {
        // 每行的结束下标（不含）；每行至少一个字，所以必然走完全句。
        var ends = new ArrayList<Integer>();
        int start = 0;
        while (start < codePoints.length) {
            int filled = fill(widths, start, maxWidth);
            int end = breakAt(codePoints, start, filled);
            ends.add(end);
            start = end;
        }
        return List.copyOf(ends);
    }

    static boolean noLineStart(int codePoint) {
        // 码点在避头标点表里，就不能出现在行首。
        return NO_LINE_START.indexOf(codePoint) >= 0;
    }

    static boolean noLineEnd(int codePoint) {
        // 码点在避尾标点表里，就不能留在行尾。
        return NO_LINE_END.indexOf(codePoint) >= 0;
    }

    static boolean wordPart(int codePoint) {
        // 英文字母、数字与下划线连成的串视为一个词，玩家名整串换行。
        return codePoint < ASCII_LIMIT && (Character.isLetterOrDigit(codePoint) || codePoint == '_');
    }

    private static int fill(float[] widths, int start, float maxWidth) {
        // 贪心放字直到放不下；一个字都放不下时也放一个，超宽的单字独占一行。
        float used = 0;
        int end = start;
        while (end < widths.length && used + widths[end] <= maxWidth) {
            used += widths[end];
            end++;
        }
        return Math.max(end, start + 1);
    }

    private static int breakAt(int[] codePoints, int start, int end) {
        // 贪心断点已到句末或本身合规，就是结果。
        if (end >= codePoints.length || breakable(codePoints, end)) {
            return end;
        }
        // 只有一个结束标点会落到行首：挂在本行末尾，本行超出的正是这个标点的宽度。
        int afterPunctuation = end + 1;
        boolean lonePunctuation = noLineStart(codePoints[end])
                && (afterPunctuation >= codePoints.length || !noLineStart(codePoints[afterPunctuation]));
        if (lonePunctuation) {
            return afterPunctuation;
        }
        // 其余情况往回找最近的合规断点：末字领着连续标点、开引号随后文、整个英文串一起换行。
        for (int candidate = end - 1; candidate > start; candidate--) {
            if (breakable(codePoints, candidate)) {
                return candidate;
            }
        }
        // 退无可退（整行是一个超长英文串，或除首字外全是标点），保留贪心断点。
        return end;
    }

    private static boolean breakable(int[] codePoints, int index) {
        // 在 index 之前断行：下一行不以结束标点开头，本行不以开引号结尾，也不拆开英文与数字串。
        int before = codePoints[index - 1];
        int after = codePoints[index];
        boolean splitsWord = wordPart(before) && wordPart(after);
        return !noLineStart(after) && !noLineEnd(before) && !splitsWord;
    }

    private static List<Glyph> glyphs(Component line) {
        // 翻译组件在这里按客户端语言解析，与原版折行看到的文字一致。
        var glyphs = new ArrayList<Glyph>();
        line.visit((style, segment) -> {
            segment.codePoints().forEach(codePoint -> glyphs.add(new Glyph(codePoint, style)));
            return Optional.empty();
        }, Style.EMPTY);
        return glyphs;
    }

    private static FormattedCharSequence row(List<Glyph> glyphs) {
        // 逐字拼回带样式的行文本，绘制与量宽都按它进行。
        var parts = new ArrayList<FormattedCharSequence>();
        for (Glyph glyph : glyphs) {
            parts.add(FormattedCharSequence.codepoint(glyph.codePoint(), glyph.style()));
        }
        return FormattedCharSequence.composite(parts);
    }

    /** 单字宽度，单位为字体像素；渲染时由字体量出，测试里用固定宽度。 */
    @FunctionalInterface
    interface GlyphWidth {
        float of(int codePoint, Style style);
    }

    private record Glyph(int codePoint, Style style) { }
}
