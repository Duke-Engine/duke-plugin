package uz.dukeengine.fixture;

/** The face in the panel: a record written after its field's {@code =}, with its own fields under it. */
public record PortraitArt(String texture, float yaw, float pitch, float zoom) {

    static final PortraitArt DEFAULTS = new PortraitArt(null, 0f, 0f, 1f);
}
