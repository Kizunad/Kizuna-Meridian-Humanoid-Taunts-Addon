package dev.kizuna.meridian.taunts.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 一种语言的台词表：{@code species} 按实体 ID 列出两组台词，{@code voice} 只给人看，加载时忽略。
 * 每组条数必须多于 {@link TauntMemory#RECENT_LINES}，否则「最近几句不重复」无法成立。
 */
public final class TauntCatalog {
    public static final int SCHEMA_VERSION = 1;
    public static final int MIN_LINES = TauntMemory.RECENT_LINES + 1;
    private static final Pattern ENTITY_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private final Map<String, SpeciesTaunts> species;

    private TauntCatalog(Map<String, SpeciesTaunts> species) {
        this.species = Map.copyOf(species);
    }

    public static TauntCatalog parse(String json) {
        // 读取：只接受 JSON 对象；校验版本与物种表；逐物种解析两组台词。
        JsonObject root = object(json);
        JsonElement version = root.get("schema_version");
        boolean numeric = version != null && version.isJsonPrimitive() && version.getAsJsonPrimitive().isNumber();
        if (!numeric || version.getAsInt() != SCHEMA_VERSION) {
            throw TauntErrors.invalid(TauntErrors.CATALOG_VERSION, String.valueOf(version));
        }
        JsonElement table = root.get("species");
        if (table == null || !table.isJsonObject() || table.getAsJsonObject().size() == 0) {
            throw TauntErrors.invalid(TauntErrors.CATALOG_SPECIES, String.valueOf(table));
        }
        Map<String, SpeciesTaunts> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : table.getAsJsonObject().entrySet()) {
            if (!ENTITY_ID.matcher(entry.getKey()).matches() || !entry.getValue().isJsonObject()) {
                throw TauntErrors.invalid(TauntErrors.SPECIES_ID, entry.getKey());
            }
            parsed.put(entry.getKey(), speciesTaunts(entry.getKey(), entry.getValue().getAsJsonObject()));
        }
        return new TauntCatalog(parsed);
    }

    public Optional<SpeciesTaunts> species(String entityId) {
        return Optional.ofNullable(species.get(entityId));
    }

    public Set<String> speciesIds() {
        return species.keySet();
    }

    private static JsonObject object(String json) {
        // 语法错误与非对象根节点同属格式错误。
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                throw TauntErrors.invalid(TauntErrors.CATALOG_FORMAT, root.toString());
            }
            return root.getAsJsonObject();
        } catch (JsonParseException failure) {
            throw TauntErrors.invalid(TauntErrors.CATALOG_FORMAT, failure.getMessage());
        }
    }

    private static SpeciesTaunts speciesTaunts(String speciesId, JsonObject entry) {
        List<TauntLine> toEntity = lines(speciesId, entry, Audience.ENTITY);
        List<TauntLine> toPlayer = lines(speciesId, entry, Audience.PLAYER);
        return new SpeciesTaunts(toEntity, toPlayer);
    }

    private static List<TauntLine> lines(String speciesId, JsonObject entry, Audience audience) {
        // 每条必须是字符串；条数下限保证排除最近几句后仍有可选台词。
        JsonElement list = entry.get(audience.dataKey());
        String location = speciesId + "." + audience.dataKey();
        if (list == null || !list.isJsonArray() || list.getAsJsonArray().size() < MIN_LINES) {
            throw TauntErrors.invalid(TauntErrors.LINE_LIST, location);
        }
        JsonArray array = list.getAsJsonArray();
        List<TauntLine> lines = new ArrayList<>(array.size());
        for (JsonElement line : array) {
            if (!line.isJsonPrimitive() || !line.getAsJsonPrimitive().isString()) {
                throw TauntErrors.invalid(TauntErrors.LINE_TEXT, location);
            }
            lines.add(TauntLine.parse(line.getAsString()));
        }
        return lines;
    }
}
