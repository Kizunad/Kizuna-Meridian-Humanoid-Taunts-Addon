package dev.kizuna.meridian.taunts.fabric;

import dev.kizuna.meridian.taunts.server.TauntLine;
import net.minecraft.text.LiteralTextContent;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.text.TextContent;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Formatting;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class TauntTextsTest {
    private static final String VINDICATOR_KEY = "entity.minecraft.vindicator";
    private static final String VILLAGER_KEY = "entity.minecraft.villager";

    @Test
    public void entityAttackerStaysTranslatableComponent() {
        // 名字是独立的翻译组件，前后两段是字面文本；服务端拿不到也不需要中文名。
        Text attacker = Text.translatable(VINDICATOR_KEY);
        Text line = TauntTexts.line(TauntLine.parse("你再碰我一下，{attacker}，铁傀儡马上就到！"), attacker);
        List<Text> parts = line.getSiblings();
        assertEquals(3, parts.size());
        assertEquals("你再碰我一下，", literal(parts.get(0)));
        assertSame(attacker, parts.get(1));
        assertEquals(VINDICATOR_KEY, ((TranslatableTextContent) parts.get(1).getContent()).getKey());
        assertEquals("，铁傀儡马上就到！", literal(parts.get(2)));
        assertSame(TextContent.EMPTY, line.getContent());
    }

    @Test
    public void playerAttackerKeepsLiteralName() {
        Text line = TauntTexts.line(TauntLine.parse("{attacker}，你的步法全是破绽。"), Text.literal("Steve"));
        assertEquals("Steve", literal(line.getSiblings().get(1)));
        assertEquals("Steve，你的步法全是破绽。", line.getString());
    }

    @Test
    public void chatUsesVanillaFormatInGray() {
        Text speaker = Text.translatable(VILLAGER_KEY);
        Text line = TauntTexts.line(TauntLine.parse("{attacker}，回去补课吧"), Text.literal("Alex"));
        Text chat = TauntTexts.chat(speaker, line);
        TranslatableTextContent format = (TranslatableTextContent) chat.getContent();
        assertEquals(TauntTexts.CHAT_FORMAT, format.getKey());
        assertArrayEquals(new Object[] {speaker, line}, format.getArgs());
        assertEquals(TextColor.fromFormatting(Formatting.GRAY), chat.getStyle().getColor());
    }

    private static String literal(Text text) {
        return ((LiteralTextContent) text.getContent()).string();
    }
}
