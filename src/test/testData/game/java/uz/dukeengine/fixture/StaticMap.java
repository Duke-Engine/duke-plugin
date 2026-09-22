package uz.dukeengine.fixture;

import uz.dukeengine.core.data.Grid;
import uz.dukeengine.core.data.Link;
import uz.dukeengine.core.data.Relief;
import uz.dukeengine.core.thing.Layered;
import java.util.List;

/**
 * A floor drawn once, read as `.map` rather than `.duke`. Its cells are the grid the editor paints,
 * its relief the storey each corner stands at, and everything else a layer put down on it.
 *
 * @param cells  a row a line; `#` is stone and a digit the storey a cell stands on
 * @param relief the corners between the cells, a whole number each
 */
public record StaticMap(String name, String displayName, int seed, float levelHeight,
        Entrance entrance, @Grid(solid = "#") List<String> cells, @Relief List<String> relief,
        List<Room> rooms, List<Spawn> monsters, List<Spawn> props) implements Layered {

    static final StaticMap DEFAULTS = new StaticMap("", "", 0, 0f, null, List.of(), List.of(),
            List.of(), List.of(), List.of());

    /** Where the hero comes in: a record read from one line, `Entrance = [2, 3]`. */
    public record Entrance(int x, int y) {
    }

    /** A room cut out of the floor, `Rooms = ["1 1 4 3", …]`. */
    public record Room(int x, int y, int width, int height) {
        public static Room of(String written) {
            var words = written.trim().split("\\s+");
            return new Room(Integer.parseInt(words[0]), Integer.parseInt(words[1]),
                    Integer.parseInt(words[2]), Integer.parseInt(words[3]));
        }
    }

    /** One thing standing on a cell, `Monsters = ["Skeleton 4 2", …]`. */
    public record Spawn(@Link(Monster.class) String kind, int x, int y) {
        public static Spawn of(String written) {
            var words = written.trim().split("\\s+");
            return new Spawn(words[0], Integer.parseInt(words[1]), Integer.parseInt(words[2]));
        }
    }
}
