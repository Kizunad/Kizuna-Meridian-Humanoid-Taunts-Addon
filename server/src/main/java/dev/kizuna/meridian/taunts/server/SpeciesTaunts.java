package dev.kizuna.meridian.taunts.server;

import java.util.List;

/** 一个物种的两组台词：对其他生物说的与对玩家说的。 */
public record SpeciesTaunts(List<TauntLine> toEntity, List<TauntLine> toPlayer) {
    public SpeciesTaunts {
        toEntity = List.copyOf(toEntity);
        toPlayer = List.copyOf(toPlayer);
    }

    public List<TauntLine> lines(Audience audience) {
        return switch (audience) {
            case ENTITY -> toEntity;
            case PLAYER -> toPlayer;
        };
    }
}
