package dev.kizuna.meridian.taunts.fabric;

import dev.kizuna.meridian.taunts.server.TauntLine;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 台词的文本组件。名字原样作为子组件插入：玩家名是字面文本，生物名是实体类型的翻译组件，
 * 由收到消息的客户端按自己的语言文件解析，服务端不把名字拼成字符串。
 */
final class TauntTexts {
    /** 原版聊天格式「<%s> %s」；整行浅灰色，与玩家聊天的白字区分。 */
    static final String CHAT_FORMAT = "chat.type.text";

    private TauntTexts() {
        // 只组装文本。
    }

    static MutableComponent line(TauntLine line, Component addressee) {
        return Component.empty()
                .append(line.before())
                .append(addressee)
                .append(line.after());
    }

    static MutableComponent chat(Component speaker, Component line) {
        return Component.translatable(CHAT_FORMAT, speaker, line).withStyle(ChatFormatting.GRAY);
    }
}
