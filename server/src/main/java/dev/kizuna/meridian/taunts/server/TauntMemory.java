package dev.kizuna.meridian.taunts.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.OptionalLong;
import java.util.random.RandomGenerator;

/** 单个说话者的冷却与最近台词：每说一句记下时刻和索引，最近 RECENT_LINES 句不会再被抽中。 */
public final class TauntMemory {
    public static final int RECENT_LINES = 5;
    private final Deque<Spoken> recent = new ArrayDeque<>();
    private OptionalLong lastTick = OptionalLong.empty();

    public boolean coolingDown(long tick, long cooldownTicks) {
        // 从没说过话的实体不在冷却中；时刻取世界游戏刻。
        return lastTick.isPresent() && tick - lastTick.getAsLong() < cooldownTicks;
    }

    public int speak(Audience audience, int lineCount, RandomGenerator random, long tick) {
        // 在同组里排除最近说过的索引后均匀抽取；台词表保证每组条数多于 RECENT_LINES。
        List<Integer> candidates = new ArrayList<>(lineCount);
        for (int index = 0; index < lineCount; index++) {
            if (!recent.contains(new Spoken(audience, index))) {
                candidates.add(index);
            }
        }
        int chosen = candidates.get(random.nextInt(candidates.size()));
        recent.addLast(new Spoken(audience, chosen));
        if (recent.size() > RECENT_LINES) {
            recent.removeFirst();
        }
        lastTick = OptionalLong.of(tick);
        return chosen;
    }

    private record Spoken(Audience audience, int index) { }
}
