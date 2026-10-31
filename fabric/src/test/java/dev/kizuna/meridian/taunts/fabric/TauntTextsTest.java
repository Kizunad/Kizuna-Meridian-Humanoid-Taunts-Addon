package dev.kizuna.meridian.taunts.fabric;

import dev.kizuna.meridian.taunts.server.TauntLine;
import org.junit.Test;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.LiteralContents;
import net.minecraft.network.chat.contents.TranslatableContents;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class TauntTextsTest {
    private static final String VINDICATOR_KEY = "entity.minecraft.vindicator";
    private static final String VILLAGER_KEY = "entity.minecraft.villager";

    @Test
    public void entityAttackerStaysTranslatableComponent() {
        // 名字是独立的翻译组件，前后两段是字面文本；服务端拿不到也不需要中文名。
        Component attacker = Component.translatable(VINDICATOR_KEY);
        Component line = TauntTexts.line(TauntLine.parse("你再碰我一下，{attacker}，铁傀儡马上就到！"), attacker);
        List<Component> parts = line.getSiblings();
        assertEquals(3, parts.size());
        assertEquals("你再碰我一下，", literal(parts.get(0)));
        assertSame(attacker, parts.get(1));
        assertEquals(VINDICATOR_KEY, ((TranslatableContents) parts.get(1).getContents()).getKey());
        assertEquals("，铁傀儡马上就到！", literal(parts.get(2)));
        assertSame(ComponentContents.EMPTY, line.getContents());
    }

    @Test
    public void playerAttackerKeepsLiteralName() {
        Component line = TauntTexts.line(TauntLine.parse("{attacker}，你的步法全是破绽。"), Component.literal("Steve"));
        assertEquals("Steve", literal(line.getSiblings().get(1)));
        assertEquals("Steve，你的步法全是破绽。", line.getString());
    }

    @Test
    public void chatUsesVanillaFormatInGray() {
        Component speaker = Component.translatable(VILLAGER_KEY);
        Component line = TauntTexts.line(TauntLine.parse("{attacker}，回去补课吧"), Component.literal("Alex"));
        Component chat = TauntTexts.chat(speaker, line);
        TranslatableContents format = (TranslatableContents) chat.getContents();
        assertEquals(TauntTexts.CHAT_FORMAT, format.getKey());
        assertArrayEquals(new Object[] {speaker, line}, format.getArgs());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), chat.getStyle().getColor());
    }

    private static String literal(Component text) {
        return ((LiteralContents) text.getContents()).text();
    }
}
