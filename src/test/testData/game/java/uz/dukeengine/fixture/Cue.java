package uz.dukeengine.fixture;

import java.util.List;

/**
 * A sound played at a moment, named `<moment>.<creature>` — `died.Skeleton` — which is how the client
 * raises it and how the Inspector's "Add Sound…" writes one.
 *
 * @param files one of which is played, the next each time
 */
public record Cue(String name, List<String> files, float gain) {

    static final Cue DEFAULTS = new Cue("", List.of(), 1f);
}
