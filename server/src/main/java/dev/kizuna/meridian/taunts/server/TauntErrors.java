package dev.kizuna.meridian.taunts.server;

/** 垃圾话纯规则的固定错误文本；加载失败随异常抛出，调用方只转述，不拼接内部状态。 */
public final class TauntErrors {
    public static final String CATALOG_FORMAT = "台词文件不是合法的 JSON 对象";
    public static final String CATALOG_VERSION = "台词文件版本不受支持";
    public static final String CATALOG_SPECIES = "台词文件没有物种表";
    public static final String SPECIES_ID = "物种 ID 必须是带命名空间的实体 ID";
    public static final String LINE_LIST = "台词列表缺失或条数不足";
    public static final String LINE_TEXT = "台词必须是非空字符串";
    public static final String LINE_PLACEHOLDER = "台词必须恰好包含一个 {attacker}";
    public static final String SETTINGS_RESOURCE = "缺少垃圾话默认配置资源";
    public static final String SETTINGS_READ = "无法读取垃圾话配置";
    public static final String SETTINGS_FORMAT = "垃圾话配置无法解析";
    public static final String SETTINGS_LANGUAGE = "台词语言必须是小写语言代码";
    public static final String SETTINGS_COOLDOWN = "冷却秒数越界";
    public static final String SETTINGS_SWITCH = "显示开关必须是布尔值";

    private TauntErrors() {
        // 只保存常量。
    }

    static IllegalArgumentException invalid(String problem, String detail) {
        return new IllegalArgumentException(problem + ": " + detail);
    }
}
