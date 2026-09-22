package uz.dukeengine.fixture;

import uz.dukeengine.core.data.Link;

/** One thing a creature can do, a {@code Skill} block in its {@code Skills = [ … ]}. */
public record Skill(String key, String name, @Link(uz.dukeengine.core.content.Effect.class) String effect,
        int manaCost, float cooldown) {

    static final Skill DEFAULTS = new Skill("", "", null, 0, 1f);
}
