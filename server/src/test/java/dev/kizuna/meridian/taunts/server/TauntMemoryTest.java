package dev.kizuna.meridian.taunts.server;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TauntMemoryTest {
    private static final long SEED = 20261030L;
    private static final int ROUNDS = 500;
    private static final int LINE_COUNT = 20;
    private static final long COOLDOWN_TICKS = 120;
    private static final long FIRST_TICK = 100;

    @Test
    public void neverRepeatsAnyOfTheLastFiveLines() {
        TauntMemory memory = new TauntMemory();
        Random random = new Random(SEED);
        List<Integer> spoken = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            int index = memory.speak(Audience.PLAYER, LINE_COUNT, random, round);
            int windowStart = Math.max(0, spoken.size() - TauntMemory.RECENT_LINES);
            assertFalse("第 " + round + " 句重复了最近几句", spoken.subList(windowStart, spoken.size()).contains(index));
            spoken.add(index);
        }
    }

    @Test
    public void smallestGroupCyclesThroughEveryLine() {
        // 只有 RECENT_LINES + 1 条时每次只剩一条可选，六句一轮不重样。
        TauntMemory memory = new TauntMemory();
        Random random = new Random(SEED);
        List<Integer> firstRound = new ArrayList<>();
        for (int round = 0; round < TauntCatalog.MIN_LINES; round++) {
            firstRound.add(memory.speak(Audience.ENTITY, TauntCatalog.MIN_LINES, random, round));
        }
        assertEquals(TauntCatalog.MIN_LINES, firstRound.stream().distinct().count());
        int next = memory.speak(Audience.ENTITY, TauntCatalog.MIN_LINES, random, TauntCatalog.MIN_LINES);
        assertEquals(firstRound.get(0).intValue(), next);
    }

    @Test
    public void cooldownStartsAtTheLastLine() {
        TauntMemory memory = new TauntMemory();
        assertFalse(memory.coolingDown(FIRST_TICK, COOLDOWN_TICKS));
        memory.speak(Audience.PLAYER, LINE_COUNT, new Random(SEED), FIRST_TICK);
        assertTrue(memory.coolingDown(FIRST_TICK + COOLDOWN_TICKS - 1, COOLDOWN_TICKS));
        assertFalse(memory.coolingDown(FIRST_TICK + COOLDOWN_TICKS, COOLDOWN_TICKS));
    }
}
