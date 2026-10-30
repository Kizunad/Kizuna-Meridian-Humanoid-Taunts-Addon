package dev.kizuna.meridian.taunts.server;

import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TauntCatalogTest {
    private static final String SHIPPED = "/data/meridian_humanoid_taunts/taunts/zh_cn.json";
    private static final int MIN_TO_ENTITY = 10;
    private static final int MIN_TO_PLAYER = 50;
    private static final String ENOUGH_LINES = "[\"a{attacker}\",\"b{attacker}\",\"c{attacker}\","
            + "\"d{attacker}\",\"e{attacker}\",\"f{attacker}\"]";

    @Test
    public void shippedCatalogCoversSevenHumanoidsWithEnoughLines() throws IOException {
        TauntCatalog catalog = TauntCatalog.parse(shipped());
        Set<String> humanoids = HumanoidSpecies.values().stream()
                .map(HumanoidSpecies::entityId)
                .collect(Collectors.toSet());
        assertEquals(humanoids, catalog.speciesIds());
        for (String species : catalog.speciesIds()) {
            SpeciesTaunts taunts = catalog.species(species).orElseThrow();
            assertTrue(species + " to_entity", taunts.toEntity().size() >= MIN_TO_ENTITY);
            assertTrue(species + " to_player", taunts.toPlayer().size() >= MIN_TO_PLAYER);
            requireReadable(species, taunts.toEntity());
            requireReadable(species, taunts.toPlayer());
        }
    }

    @Test
    public void lineSplitsAroundTheOnlyPlaceholder() {
        TauntLine line = TauntLine.parse("你再碰我一下，{attacker}，铁傀儡马上就到！");
        assertEquals("你再碰我一下，", line.before());
        assertEquals("，铁傀儡马上就到！", line.after());
        TauntLine leading = TauntLine.parse("{attacker}，你的步法全是破绽。");
        assertEquals("", leading.before());
    }

    @Test
    public void rejectsLinesWithoutExactlyOnePlaceholderOrText() {
        assertThrows(IllegalArgumentException.class, () -> TauntLine.parse("没有占位符"));
        assertThrows(IllegalArgumentException.class, () -> TauntLine.parse("{attacker} 和 {attacker}"));
        assertThrows(IllegalArgumentException.class, () -> TauntLine.parse(" {attacker} "));
        assertThrows(IllegalArgumentException.class, () -> TauntLine.parse(""));
    }

    @Test
    public void rejectsMalformedCatalogs() {
        String valid = catalog("minecraft:villager", ENOUGH_LINES, ENOUGH_LINES);
        assertEquals(Set.of("minecraft:villager"), TauntCatalog.parse(valid).speciesIds());
        assertRejected("[]");
        assertRejected("{\"schema_version\":2,\"species\":{}}");
        assertRejected("{\"schema_version\":\"1\",\"species\":{}}");
        assertRejected("{\"schema_version\":1,\"species\":{}}");
        assertRejected(catalog("villager", ENOUGH_LINES, ENOUGH_LINES));
        assertRejected(catalog("minecraft:villager", "[\"a{attacker}\"]", ENOUGH_LINES));
        assertRejected(catalog("minecraft:villager", ENOUGH_LINES, ENOUGH_LINES.replace("\"f{attacker}\"", "6")));
        assertRejected("{\"schema_version\":1,\"species\":{\"minecraft:villager\":{\"to_entity\":"
                + ENOUGH_LINES + "}}}");
    }

    private static void requireReadable(String species, List<TauntLine> lines) {
        // 占位符已被拆掉，剩下的前后两段合起来必须是一句非空台词。
        for (TauntLine line : lines) {
            String text = line.before() + line.after();
            assertFalse(species + " 空台词", text.isBlank());
            assertFalse(species + " 残留占位符", text.contains(TauntLine.PLACEHOLDER));
        }
    }

    private static String catalog(String species, String toEntity, String toPlayer) {
        return "{\"schema_version\":1,\"species\":{\"" + species + "\":{\"voice\":\"只给人看\","
                + "\"to_entity\":" + toEntity + ",\"to_player\":" + toPlayer + "}}}";
    }

    private static void assertRejected(String json) {
        assertThrows(IllegalArgumentException.class, () -> TauntCatalog.parse(json));
    }

    private static String shipped() throws IOException {
        try (InputStream input = TauntCatalogTest.class.getResourceAsStream(SHIPPED)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
