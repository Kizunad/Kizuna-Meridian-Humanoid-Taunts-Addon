package dev.kizuna.meridian.taunts.server;

/** 一句台词按唯一的 {attacker} 拆成前后两段，攻击者名字由调用方以文本组件插入。 */
public record TauntLine(String before, String after) {
    public static final String PLACEHOLDER = "{attacker}";

    public static TauntLine parse(String text) {
        // 去掉占位符后不能只剩空白，占位符必须恰好一次，否则无法替换成通顺的一句话。
        if (text.replace(PLACEHOLDER, "").isBlank()) {
            throw TauntErrors.invalid(TauntErrors.LINE_TEXT, text);
        }
        int start = text.indexOf(PLACEHOLDER);
        if (start < 0 || text.indexOf(PLACEHOLDER, start + 1) >= 0) {
            throw TauntErrors.invalid(TauntErrors.LINE_PLACEHOLDER, text);
        }
        return new TauntLine(text.substring(0, start), text.substring(start + PLACEHOLDER.length()));
    }
}
