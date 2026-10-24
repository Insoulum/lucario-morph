package dev.lucario;

import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.network.chat.Component;

public final class Variants {
   public static final String SHINY_SUFFIX = "_shiny";
   public static final String SHINY_WORD = "shiny";
   public static final String DEFAULT_LUCARIO = "lucario";
   public static final String DEFAULT_RIOLU = "riolu";
   public static final String MEGA = "lucariomega";
   public static final List<String> LUCARIO = List.of(
      "lucario",
      "lucariocafe",
      "lucariocaptain",
      "lucariochef",
      "lucarioconcert",
      "lucariocostumeparty",
      "lucarioholiday",
      "lucariomartialarts",
      "lucariomega",
      "lucariopmd",
      "lucariopsmd",
      "lucarioruins",
      "lucariospace"
   );
   public static final List<String> RIOLU = List.of("riolu", "riolupmd", "riolupsmd");
   private static final Set<String> NO_ELYTRA = Set.of("lucariocostumeparty");
   public static final List<String> ALL;

   private Variants() {
   }

   public static String normalize(String v) {
      return v == null ? null : v.toLowerCase(Locale.ROOT).trim();
   }

   public static String resolve(String input, String defaultBase) {
      String v = normalize(input);
      if (v != null && !v.isEmpty()) {
         boolean shiny = false;
         StringBuilder base = new StringBuilder();

         for (String token : v.split("[\\s_]+")) {
            if (!token.isEmpty()) {
               if (token.equals("shiny")) {
                  shiny = !shiny;
               } else {
                  base.append(token);
               }
            }
         }

         String b = base.toString();
         if (b.isEmpty()) {
            return shiny ? defaultBase + "_shiny" : defaultBase;
         } else {
            String full = resolveBase(b, defaultBase);
            if (full == null) {
               return null;
            } else {
               return shiny ? full + "_shiny" : full;
            }
         }
      } else {
         return null;
      }
   }

   private static String resolveBase(String b, String defaultBase) {
      List<String> family = defaultBase.equals("riolu") ? RIOLU : LUCARIO;
      if (family.contains(b)) {
         return b;
      } else {
         String prefixed = defaultBase + b;
         return family.contains(prefixed) ? prefixed : null;
      }
   }

   public static boolean isShiny(String v) {
      return v != null && v.endsWith("_shiny");
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

   public static boolean hidesElytra(String v) {
      return v != null && NO_ELYTRA.contains(baseOf(normalize(v)));
   }

   public static String shortName(String variant, String familyPrefix) {
      return variant.startsWith(familyPrefix) && variant.length() > familyPrefix.length() ? variant.substring(familyPrefix.length()) : variant;
   }

   /**
    * Patch 13: translation key of the human-readable family name instead of a
    * hardcoded Russian literal ("Риолу" / "Мега-Лукарио" / "Лукарио").
    */
   public static String titleKey(String v) {
      if (isRiolu(v)) {
         return "lucariomod.title.riolu";
      } else {
         return isMega(v) ? "lucariomod.title.mega" : "lucariomod.title.lucario";
      }
   }

   /**
    * 1.6.2: per-variant display name key ("lucariomod.name.lucariopmd" ->
    * "Lucario pmd"), so chat feedback no longer shows the technical variant id
    * in parentheses.
    */
   public static String nameKey(String v) {
      return "lucariomod.name." + baseOf(normalize(v));
   }

   /**
    * 1.6.2: single source of the "You are now <Name>!" feedback for the server
    * command and the client GUI — shiny variants read "You are now a shiny ...".
    */
   public static Component morphedMessage(String v) {
      Component name = Component.translatable(nameKey(v));
      return isShiny(v)
         ? Component.translatable("lucariomod.cmd.morphed.shiny", name)
         : Component.translatable("lucariomod.cmd.morphed", name);
   }

   public static void suggest(SuggestionsBuilder builder, String defaultBase) {
      String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
      List<String> family = defaultBase.equals("riolu") ? RIOLU : LUCARIO;
      List<String> shorts = new ArrayList<>();

      for (String v : family) {
         String s = shortName(v, defaultBase);
         if (!s.isEmpty()) {
            shorts.add(s);
         }
      }

      boolean typingWithSpaces = remaining.contains(" ");
      List<String> candidates = new ArrayList<>();

      for (String s : shorts) {
         candidates.add(s);
         candidates.add(s + "_shiny");
         if (typingWithSpaces) {
            candidates.add(s + " shiny");
         }
      }

      candidates.add("shiny");
      if (!remaining.isEmpty()) {
         for (String vx : family) {
            candidates.add(vx);
            candidates.add(vx + "_shiny");
         }
      }

      for (String c : candidates) {
         if (c.toLowerCase(Locale.ROOT).startsWith(remaining)) {
            builder.suggest(c);
         }
      }
   }

   static {
      List<String> all = new ArrayList<>(LUCARIO);
      all.addAll(RIOLU);
      ALL = List.copyOf(all);
   }
}
