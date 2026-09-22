package uz.dukeengine.fixture;

import uz.dukeengine.core.module.ModuleData;
import uz.dukeengine.core.module.ModuleGroup;

/**
 * What makes a creature come for the hero. A module the game wrote rather than the engine, named by the
 * class it is written in — `Brain` for `Brain.Data` — which is what a script is.
 */
@ModuleGroup("Behaviour")
public final class Brain {

    /** @param giveUpAfter seconds of losing him before it goes back where it stood */
    public record Data(float giveUpAfter, float leash) implements ModuleData {
        static final Data DEFAULTS = new Data(4f, 0f);
    }
}
