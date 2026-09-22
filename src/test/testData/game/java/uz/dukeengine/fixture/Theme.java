package uz.dukeengine.fixture;

import java.util.List;

/**
 * What a floor is dressed in: the size of a tile and the tones a floor may be laid in, each a pair of
 * models. The plugin knows none of this by name — it finds the block that has `TileSize` and `Tones`
 * and lays the floor in it — so a game may call it whatever it likes.
 */
public record Theme(String name, float tileSize, List<Tone> tones) {

    static final Theme DEFAULTS = new Theme("", 4f, List.of());

    /** One look a floor may be laid in. */
    public record Tone(String name, String floor, String wall) {
    }
}
