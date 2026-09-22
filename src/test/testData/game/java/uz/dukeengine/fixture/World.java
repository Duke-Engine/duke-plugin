package uz.dukeengine.fixture;

import uz.dukeengine.core.data.Link;
import uz.dukeengine.core.thing.Layered;
import java.util.List;

/**
 * What every floor is laid in: how tall a storey is, and which themes are worn. A map that says
 * nothing about its storey height takes it from here — both records are `Layered`.
 */
public record World(String name, float levelHeight, @Link(Theme.class) List<String> themes) implements Layered {

    static final World DEFAULTS = new World("", 8f, List.of());
}
