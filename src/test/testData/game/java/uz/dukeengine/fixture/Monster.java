package uz.dukeengine.fixture;

import uz.dukeengine.client3d.Held;
import uz.dukeengine.core.content.AnimationSet;
import uz.dukeengine.core.data.Clip;
import uz.dukeengine.core.data.Group;
import uz.dukeengine.core.data.Link;
import uz.dukeengine.core.module.DamageType;
import uz.dukeengine.core.module.ModuleData;
import uz.dukeengine.core.thing.Geometry;
import uz.dukeengine.core.thing.Kind;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A creature, as one {@code Monster} block writes it: what it is made of, what it is drawn as, how it
 * moves and what it can do.
 *
 * <p>This is the plugin's own game, not anybody's: it exists so the checks can be run over records and
 * data of the shapes a real game has — every kind of field the format holds, one of each — without a
 * second checkout. A syntax added to the engine is written here and the files below are what prove the
 * plugin reads it.
 *
 * @param senseRadius how far it notices the hero
 * @param armor       what a kind of damage is multiplied by before it lands
 * @param animations  the {@link AnimationSet} it moves by; its own clips are the ones it plays differently
 * @param held        what it carries, each a {@code Held} block in {@code Held = [ … ]}
 */
public record Monster(@Group("Identity") String name, String displayName, Set<Kind> kindOf,
        @Group("Body") float senseRadius, Geometry geometry, Map<DamageType, Float> armor,
        @Group("Modules") List<ModuleData> modules,
        @Group("Behaviour") int tempo, boolean flies,
        @Group("Look") String model, String texture, int tint, float modelScale, List<Held> held,
        @Group("Animation") @Link(AnimationSet.class) String animations,
        @Clip String idle, @Clip String walk, @Clip String attack, @Clip String death,
        @Group("Skills") PortraitArt portrait, List<Skill> skills) {

    static final Monster DEFAULTS = new Monster("", "", Set.of(), 90f, null, Map.of(), List.of(),
            1, false,
            null, null, 0xFFFFFF, 1f, List.of(),
            null, null, null, null, null,
            null, List.of());
}
