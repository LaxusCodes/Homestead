package dev.laxus.homestead;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

public final class Text {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {}

    /** MiniMessage string to Component. */
    public static Component mm(String s) {
        return MM.deserialize(s);
    }

    /** Same as mm() but with italics off (for item names and lore). */
    public static Component item(String s) {
        return MM.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }
}
