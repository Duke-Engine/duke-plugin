package uz.dukeengine.fixture;

/**
 * The hero's bar: the skins its edges are painted with, and the words its HUD says. The bar's own
 * layout is the client's `PanelLook`; a game that writes none is drawn with the client's defaults.
 */
public final class Bar {

    /** One piece of the bar's edge: a picture, how far in it is drawn, and what it is tinted. */
    public record Skin(String name, String texture, int inset, float scale, int tint) {
        static final Skin DEFAULTS = new Skin("", null, 0, 1f, 0xFFFFFF);
    }

    /** What the bar says, in the game's own language. */
    public record Hud(String name, String skillsWord, String itemsWord, String depthWord) {
        static final Hud DEFAULTS = new Hud("", "", "", "");
    }
}
