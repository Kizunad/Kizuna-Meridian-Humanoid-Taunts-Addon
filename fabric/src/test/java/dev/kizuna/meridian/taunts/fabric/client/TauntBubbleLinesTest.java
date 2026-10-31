package dev.kizuna.meridian.taunts.fabric.client;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TauntBubbleLinesTest {
    /** 每字 10 像素、每行 40 像素：正好四个字一行，断点一目了然。 */
    private static final float GLYPH_WIDTH = 10;
    private static final float ROW_WIDTH = 40;
    private static final float WIDE_GLYPH = 50;

    @Test
    public void plainTextFillsEachRowGreedily() {
        // 没有标点时与原版一样逐字填满。
        assertEquals(List.of("一二三四", "五六七八", "九"), rows("一二三四五六七八九"));
    }

    @Test
    public void lonePunctuationHangsAtTheEndOfThePreviousRow() {
        // 截图里的情形：句末「！」不再独占一行，挂在上一行末尾，该行超出一个字宽。
        assertEquals(List.of("一二三四！"), rows("一二三四！"));
        assertEquals(List.of("一二三四，", "五六"), rows("一二三四，五六"));
        assertEquals(List.of("一二三四」", "五"), rows("一二三四」五"));
    }

    @Test
    public void punctuationRunTakesThePreviousCharacterAlong() {
        // 连续标点不能都挂上去，改为把上一行最后一个字带下来领着它们。
        assertEquals(List.of("一二三", "四！？"), rows("一二三四！？"));
        assertEquals(List.of("一二三", "四……"), rows("一二三四……"));
        assertEquals(List.of("一二", "三」！？"), rows("一二三」！？"));
    }

    @Test
    public void wordsMoveToTheNextRowWhole() {
        // 玩家名这类英文数字串不拆开，整串带到下一行；名字后的单个标点照常挂行尾，连续标点随名字一起换行。
        assertEquals(List.of("一二三", "Ab_1"), rows("一二三Ab_1"));
        assertEquals(List.of("一二Ab，", "三"), rows("一二Ab，三"));
        assertEquals(List.of("一二", "Ab！？"), rows("一二Ab！？"));
    }

    @Test
    public void openingBracketsStayWithTheTextAfterThem() {
        // 开引号与左括号不留在行尾，随后面的字换行；括号里的名字也整串带下来。
        assertEquals(List.of("一二三", "「四五」"), rows("一二三「四五」"));
        assertEquals(List.of("一二", "『（三"), rows("一二『（三"));
        assertEquals(List.of("一二", "（Ab）"), rows("一二（Ab）"));
    }

    @Test
    public void rowsThatCannotBeFixedKeepTheGreedyBreak() {
        // 整行都是标点、英文串比一行还长、单字比一行还宽时无处可调，仍然逐行推进、不出空行。
        assertEquals(List.of("！！！！", "！！"), rows("！！！！！！"));
        assertEquals(List.of("ABCD", "EFGH", "IJ"), rows("ABCDEFGHIJ"));
        float[] widths = {WIDE_GLYPH, WIDE_GLYPH};
        assertEquals(List.of(1, 2), TauntBubbleLines.rowEnds(new int[] {'一', '二'}, widths, ROW_WIDTH));
    }

    @Test
    public void stylesSurviveTheBreak() {
        // 比一行还长的名字只能拆开，各段都保留原样式；前面的字与后面的标点仍是无样式文本。
        Component line = Component.literal("一")
                .append(Component.literal("ABCDEFG").withStyle(ChatFormatting.RED))
                .append("！");
        List<FormattedCharSequence> rows = TauntBubbleLines.wrap(line, ROW_WIDTH, (codePoint, style) -> GLYPH_WIDTH);
        assertEquals(List.of("一", "ABCD", "EFG！"), strings(rows));
        TextColor red = TextColor.fromLegacyFormat(ChatFormatting.RED);
        assertNull(styles(rows.get(0)).get(0).getColor());
        assertEquals(red, styles(rows.get(1)).get(0).getColor());
        List<Style> last = styles(rows.get(2));
        assertEquals(red, last.get(0).getColor());
        assertEquals(red, last.get(2).getColor());
        assertNull(last.get(3).getColor());
    }

    private static List<String> rows(String text) {
        // 每个字同宽，断点只取决于字数与避头规则。
        return strings(TauntBubbleLines.wrap(Component.literal(text), ROW_WIDTH, (codePoint, style) -> GLYPH_WIDTH));
    }

    private static List<String> strings(List<FormattedCharSequence> rows) {
        // 把行文本还原成字符串，整体比较断点。
        return rows.stream().map(TauntBubbleLinesTest::string).toList();
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

    private static List<Style> styles(FormattedCharSequence row) {
        // 逐字取出样式，检查跨行后样式是否保留。
        var styles = new ArrayList<Style>();
        row.accept((index, style, codePoint) -> styles.add(style));
        return styles;
    }
}
