package dev.lucario;

import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Список доступных образов (Лукарио и Риолу). Суффикс "_shiny" включает шайни-текстуру.
 *
 * Разбор ввода игрока (см. resolve): разделителем считается пробел ИЛИ подчёркивание,
 * поэтому "/lucario captain shiny" и "/lucario captain_shiny" — одно и то же. Полные имена
 * (lucariocaptain) тоже работают, но обычно достаточно короткого имени ("captain"):
 * к нему автоматически подставляется префикс семейства (lucario/riolu).
 */
public final class Variants {
    public static final String SHINY_SUFFIX = "_shiny";
    /** Слово-модификатор шайни: можно писать отдельно ("captain shiny") или через подчёркивание. */
    public static final String SHINY_WORD = "shiny";

    public static final String DEFAULT_LUCARIO = "lucario";
    public static final String DEFAULT_RIOLU = "riolu";
    public static final String MEGA = "lucariomega";

    public static final List<String> LUCARIO = List.of(
            "lucario", "lucariocafe", "lucariocaptain", "lucariochef", "lucarioconcert",
            "lucariocostumeparty", "lucarioholiday", "lucariomartialarts", "lucariomega",
            "lucariopmd", "lucariopsmd", "lucarioruins", "lucariospace");

    public static final List<String> RIOLU = List.of("riolu", "riolupmd", "riolupsmd");

    /** Образы, у которых элитры не рисуются: у костюмного Лукарио уже есть свои крылья на спине. */
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
     * Разбирает ввод игрока в ключ варианта. Понимает:
     *  - короткие имена: "captain" -> lucariocaptain, "chef" -> lucariochef, "mega" -> lucariomega,
     *    "pmd"/"psmd" -> riolupmd/riolupsmd (префикс семейства подставляется сам);
     *  - полные имена (как раньше): "lucariocaptain", "lucariomega", "riolupmd"...
     *  - слово "shiny" где угодно в аргументе: "/lucario captain shiny", "/lucario shiny captain",
     *    "/lucario captain_shiny" — всё включит шайни-версию;
     *  - слова через пробел: "costume party" -> lucariocostumeparty.
     * Если образ найти не удалось, возвращает null (команда покажет ошибку).
     */
    public static String resolve(String input, String defaultBase) {
        String v = normalize(input);
        if (v == null || v.isEmpty()) return null;
        boolean shiny = false;
        StringBuilder base = new StringBuilder();
        for (String token : v.split("[\\s_]+")) {
            if (token.isEmpty()) continue;
            if (token.equals(SHINY_WORD)) {
                shiny = !shiny; // "shiny shiny" выключает шайни обратно — забавный бонус, не мешает
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

    /** Короткое имя -> ключ варианта: сначала точное совпадение, потом с префиксом семейства. */
    private static String resolveBase(String b, String defaultBase) {
        List<String> family = defaultBase.equals(DEFAULT_RIOLU) ? RIOLU : LUCARIO;
        if (family.contains(b)) return b;               // полные имена: lucariocaptain, riolupmd...
        String prefixed = defaultBase + b;              // короткие: captain -> lucariocaptain
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

    /** Не рисовать элитры в этом образе (у костюма свои крылья — они конфликтуют с элитрами). */
    public static boolean hidesElytra(String v) {
        return v != null && NO_ELYTRA.contains(baseOf(normalize(v)));
    }

    /** Короткое имя варианта (без префикса семейства): lucariocaptain -> "captain", riolupmd -> "pmd". */
    public static String shortName(String variant, String familyPrefix) {
        return variant.startsWith(familyPrefix) && variant.length() > familyPrefix.length()
                ? variant.substring(familyPrefix.length())
                : variant;
    }

    /**
     * Подсказки Tab для аргумента варианта. Поддерживает частично введённый текст с пробелом:
     * после "captain " предложит "captain shiny". Полные имена тоже подсказываются (совместимость),
     * но только если игрок начал вводить именно их.
     */
    public static void suggest(SuggestionsBuilder builder, String defaultBase) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        List<String> family = defaultBase.equals(DEFAULT_RIOLU) ? RIOLU : LUCARIO;
        List<String> shorts = new ArrayList<>();
        for (String v : family) {
            String s = shortName(v, defaultBase);
            if (!s.isEmpty()) shorts.add(s);
        }
        // кандидаты: короткое имя, его _shiny-версия и форма "X shiny" (последняя — только
        // если игрок уже вводит пробел, чтобы не засорять список из 3 почти одинаковых строк)
        boolean typingWithSpaces = remaining.contains(" ");
        List<String> candidates = new ArrayList<>();
        for (String s : shorts) {
            candidates.add(s);
            candidates.add(s + SHINY_SUFFIX);
            if (typingWithSpaces) candidates.add(s + " " + SHINY_WORD);
        }
        candidates.add(SHINY_WORD);
        // полные имена — только как продолжение уже введённого текста ("lucario..." -> полные ключи),
        // при пустом вводе они только засоряют список подсказок
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

    /** Ключ перевода имени конкретного образа: lucariomod.name.lucariopmd -> "Lucario pmd". */
    public static String nameKey(String v) {
        return "lucariomod.name." + baseOf(normalize(v));
    }

    /** Единое сообщение "Вы теперь <имя>!" для команды и GUI; шайни-варианты — "Вы теперь шайни <имя>!". */
    public static Component morphedMessage(String v) {
        Component name = Component.translatable(nameKey(v));
        return isShiny(v)
                ? Component.translatable("lucariomod.cmd.morphed.shiny", name)
                : Component.translatable("lucariomod.cmd.morphed", name);
    }
}
