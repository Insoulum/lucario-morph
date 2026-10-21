package dev.lucario;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Список доступных образов (Лукарио и Риолу). Суффикс "_shiny" включает шайни-текстуру. */
public final class Variants {
    public static final String SHINY_SUFFIX = "_shiny";

    public static final String DEFAULT_LUCARIO = "lucario";
    public static final String DEFAULT_RIOLU = "riolu";
    public static final String MEGA = "lucariomega";

    public static final List<String> LUCARIO = List.of(
            "lucario", "lucariocafe", "lucariocaptain", "lucariochef", "lucarioconcert",
            "lucariocostumeparty", "lucarioholiday", "lucariomartialarts", "lucariomega",
            "lucariopmd", "lucariopsmd", "lucarioruins", "lucariospace");

    public static final List<String> RIOLU = List.of("riolu", "riolupmd", "riolupsmd");

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
     * Разбирает ввод игрока: понимает короткие слова "mega", "mega_shiny" и "shiny"
     * (последнее — шайни-версия образа по умолчанию для данной команды).
     */
    public static String resolve(String input, String defaultBase) {
        String v = normalize(input);
        if (v == null) return null;
        if (v.equals("shiny")) return defaultBase + SHINY_SUFFIX;
        if (v.equals("mega")) return MEGA;
        if (v.equals("mega" + SHINY_SUFFIX)) return MEGA + SHINY_SUFFIX;
        return v;
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

    public static boolean isLucario(String v) {
        return v != null && LUCARIO.contains(baseOf(normalize(v)));
    }

    /** Подсказки для /lucario (Tab). */
    public static List<String> lucarioSuggestions() {
        return withShiny(LUCARIO, "mega", "mega" + SHINY_SUFFIX, "shiny");
    }

    /** Подсказки для /riolu (Tab). */
    public static List<String> rioluSuggestions() {
        return withShiny(RIOLU, "shiny");
    }

    private static List<String> withShiny(List<String> base, String... extra) {
        List<String> out = new ArrayList<>(base);
        for (String s : base) out.add(s + SHINY_SUFFIX);
        for (String s : extra) out.add(s);
        return out;
    }

    /** Название образа для сообщения игроку. */
    public static String title(String v) {
        if (isRiolu(v)) return "Риолу";
        if (isMega(v)) return "Мега-Лукарио";
        return "Лукарио";
    }
}
