package dev.kizuna.meridian.taunts.server;

/** 台词的说话对象：攻击者是玩家时取 to_player，是其他生物时取 to_entity。 */
public enum Audience {
    PLAYER("player"),
    ENTITY("entity");

    private final String id;

    Audience(String id) {
        this.id = id;
    }

    public String id() {
        // 命令参数里使用的短名。
        return id;
    }

    public String dataKey() {
        // 台词文件里对应的列表名。
        return "to_" + id;
    }
}
