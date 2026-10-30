package dev.lucario;

import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * List of available morphs (Lucario and Riolu). The "_shiny" suffix enables the shiny texture.
 *
 * Player input parsing (see resolve): the separator is a space OR an underscore,
 * so "/lucario captain shiny" and "/lucario captain_shiny" are the same thing. Full names
 * (lucariocaptain) also work, but the short name ("captain") is usually enough:
 * the family prefix (lucario/riolu) is prepended to it automatically.
 */
public final class Variants {
    public static final String SHINY_SUFFIX = "_shiny";
    /** The shiny modifier word: write it separately ("captain shiny") or with an underscore. */
    public static final String SHINY_WORD = "shiny";

    public static final String DEFAULT_LUCARIO = "lucario";
    public static final String DEFAULT_RIOLU = "riolu";
    public static final String MEGA = "lucariomega";

    public static final List<String> LUCARIO = List.of(
            "lucario", "lucariocafe", "lucariocaptain", "lucariochef", "lucarioconcert",
            "lucariocostumeparty", "lucarioholiday", "lucariomartialarts", "lucariomega",
            "lucariopmd", "lucariopsmd", "lucarioruins", "lucariospace");

    public static final List<String> RIOLU = List.of("riolu", "riolupmd", "riolupsmd");

    /** Morphs whose elytra is not drawn: the costume Lucario already has its own wings on its back. */
    private static final Set<String> NO_ELYTRA = Set.of("lucariocostumeparty");

    public static final List<String> ALL;

    static {
        List<String> all = new ArrayList<>(LUCARIO);
        all.addAll(RIOLU);
        ALL = List.copyOf(all);
    }

    private Variants() {}

    public static String normalize(String v) {
        return v == null ? null : v.toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Parses player input into a variant key. Understands:
     *  - short names: "captain" -> lucariocaptain, "chef" -> lucariochef, "mega" -> lucariomega,
     *    "pmd"/"psmd" -> riolupmd/riolupsmd (the family prefix is added automatically);
     *  - full names (as before): "lucariocaptain", "lucariomega", "riolupmd"...
     *  - the word "shiny" anywhere in the argument: "/lucario captain shiny", "/lucario shiny captain",
     *    "/lucario captain_shiny" - all of them enable the shiny version;
     *  - space-separated words: "costume party" -> lucariocostumeparty.
     * Returns null if no morph was found (the command will show an error).
     */
    public static String resolve(String input, String defaultBase) {
        String v = normalize(input);
        if (v == null || v.isEmpty()) return null;
        boolean shiny = false;
        StringBuilder base = new StringBuilder();
        for (String token : v.split("[\\s_]+")) {
            if (token.isEmpty()) continue;
            if (token.equals(SHINY_WORD)) {
                shiny = !shiny; // "shiny shiny" toggles shiny back off - a fun bonus, harmless
                continue;
            }
            base.append(token);
        }
        String b = base.toString();
        if (b.isEmpty()) return shiny ? defaultBase + SHINY_SUFFIX : defaultBase;
        String full = resolveBase(b, defaultBase);
        if (full == null) return null;
        return shiny ? full + SHINY_SUFFIX : full;
    }

    /** Short name -> variant key: exact match first, then with the family prefix. */
    private static String resolveBase(String b, String defaultBase) {
        List<String> family = defaultBase.equals(DEFAULT_RIOLU) ? RIOLU : LUCARIO;
        if (family.contains(b)) return b;               // full names: lucariocaptain, riolupmd...
        String prefixed = defaultBase + b;              // short ones: captain -> lucariocaptain
        if (family.contains(prefixed)) return prefixed;
        return null;
    }

    public static boolean isShiny(String v) {
        return v != null && v.endsWith(SHINY_SUFFIX);
    }

    public static String baseOf(String v) {
        return isShiny(v) ? v.substring(0, v.length() - SHINY_SUFFIX.length()) : v;
    }

    public static boolean isValid(String v) {
        return v != null && ALL.contains(baseOf(normalize(v)));
    }

    public static boolean isRiolu(String v) {
        return v != null && RIOLU.contains(baseOf(normalize(v)));
    }

    public static boolean isMega(String v) {
        return v != null && MEGA.equals(baseOf(normalize(v)));
    }

    /** The morph belongs to the Lucario family (including Mega and all costumes). */
    public static boolean isLucario(String v) {
        return v != null && LUCARIO.contains(baseOf(normalize(v)));
    }

    /** Do not draw the elytra in this morph (the costume has its own wings, which clash with the elytra). */
    public static boolean hidesElytra(String v) {
        return v != null && NO_ELYTRA.contains(baseOf(normalize(v)));
    }

    /** Short variant name (without the family prefix): lucariocaptain -> "captain", riolupmd -> "pmd". */
    public static String shortName(String variant, String familyPrefix) {
        return variant.startsWith(familyPrefix) && variant.length() > familyPrefix.length()
                ? variant.substring(familyPrefix.length())
                : variant;
    }

    /**
     * Tab suggestions for the variant argument. Supports partially typed text containing a space:
     * after "captain " it suggests "captain shiny". Full names are suggested too (for compatibility),
     * but only if the player started typing exactly one of them.
     */
    public static void suggest(SuggestionsBuilder builder, String defaultBase) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        List<String> family = defaultBase.equals(DEFAULT_RIOLU) ? RIOLU : LUCARIO;
        List<String> shorts = new ArrayList<>();
        for (String v : family) {
            String s = shortName(v, defaultBase);
            if (!s.isEmpty()) shorts.add(s);
        }
        // candidates: the short name, its _shiny version, and the "X shiny" form (the latter only
        // when the player is already typing a space, so the list is not cluttered with 3 near-identical lines)
        boolean typingWithSpaces = remaining.contains(" ");
        List<String> candidates = new ArrayList<>();
        for (String s : shorts) {
            candidates.add(s);
            candidates.add(s + SHINY_SUFFIX);
            if (typingWithSpaces) candidates.add(s + " " + SHINY_WORD);
        }
        candidates.add(SHINY_WORD);
        // full names only as a continuation of already typed text ("lucario..." -> the full keys),
        // with empty input they just clutter the suggestion list
        if (!remaining.isEmpty()) {
            for (String v : family) {
                candidates.add(v);
                candidates.add(v + SHINY_SUFFIX);
            }
        }
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).startsWith(remaining)) builder.suggest(c);
        }
    }

    /** Translation key for a concrete morph's name: lucariomod.name.lucariopmd -> "Lucario pmd". */
    public static String nameKey(String v) {
        return "lucariomod.name." + baseOf(normalize(v));
    }

    /**
     * Translation key for the "title" family name (1.6.1): plain Lucario/Riolu or Mega.
     * Used where a generic morph heading is needed rather than a specific costume
     * (keys lucariomod.title.lucario / .mega / .riolu).
     */
    public static String titleKey(String v) {
        if (isRiolu(v)) return "lucariomod.title.riolu";
        return isMega(v) ? "lucariomod.title.mega" : "lucariomod.title.lucario";
    }

    /** Shared "You are now <name>!" message for the command and GUI; shiny variants use "You are now shiny <name>!". */
    public static Component morphedMessage(String v) {
        Component name = Component.translatable(nameKey(v));
        return isShiny(v)
                ? Component.translatable("lucariomod.cmd.morphed.shiny", name)
                : Component.translatable("lucariomod.cmd.morphed", name);
    }
}
