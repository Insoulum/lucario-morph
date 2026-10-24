package dev.lucario.client.anim;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Molang {
   private static final Molang.Expr ZERO = t -> 0.0;

   private Molang() {
   }

   public static Molang.Expr parse(JsonElement el) {
      if (el == null || el.isJsonNull()) {
         return ZERO;
      } else if (!el.isJsonPrimitive()) {
         return ZERO;
      } else {
         JsonPrimitive p = el.getAsJsonPrimitive();
         if (p.isNumber()) {
            double v = p.getAsDouble();
            return t -> v;
         } else {
            String s = p.getAsString().trim();

            try {
               double v = Double.parseDouble(s);
               return t -> v;
            } catch (NumberFormatException var6) {
               try {
                  return new Molang.Parser(s).expr();
               } catch (RuntimeException var5) {
                  return ZERO;
               }
            }
         }
      }
   }

   public interface Expr {
      double eval(double var1);
   }

   private static final class Parser {
      private final String s;
      private int pos;

      Parser(String src) {
         String x = src.toLowerCase(Locale.ROOT).trim();
         if (x.startsWith("return ")) {
            x = x.substring(7);
         }

         if (x.endsWith(";")) {
            x = x.substring(0, x.length() - 1);
         }

         this.s = x;
      }

      private void ws() {
         while (this.pos < this.s.length() && Character.isWhitespace(this.s.charAt(this.pos))) {
            this.pos++;
         }
      }

      private boolean eat(char c) {
         this.ws();
         if (this.pos < this.s.length() && this.s.charAt(this.pos) == c) {
            this.pos++;
            return true;
         } else {
            return false;
         }
      }

      Molang.Expr expr() {
         Molang.Expr l = this.term();

         while (true) {
            while (!this.eat('+')) {
               if (!this.eat('-')) {
                  return l;
               }

               Molang.Expr a = l;
               Molang.Expr b = this.term();
               l = t -> a.eval(t) - b.eval(t);
            }

            Molang.Expr a = l;
            Molang.Expr b = this.term();
            l = t -> a.eval(t) + b.eval(t);
         }
      }

      private Molang.Expr term() {
         Molang.Expr l = this.unary();

         while (true) {
            while (!this.eat('*')) {
               if (!this.eat('/')) {
                  return l;
               }

               Molang.Expr a = l;
               Molang.Expr b = this.unary();
               l = t -> {
                  double d = b.eval(t);
                  return d == 0.0 ? 0.0 : a.eval(t) / d;
               };
            }

            Molang.Expr a = l;
            Molang.Expr b = this.unary();
            l = t -> a.eval(t) * b.eval(t);
         }
      }

      private Molang.Expr unary() {
         if (this.eat('-')) {
            Molang.Expr e = this.unary();
            return t -> -e.eval(t);
         } else {
            return this.eat('+') ? this.unary() : this.primary();
         }
      }

      private Molang.Expr primary() {
         this.ws();
         if (this.pos >= this.s.length()) {
            throw new IllegalArgumentException("unexpected end");
         } else {
            char c = this.s.charAt(this.pos);
            if (c == '(') {
               this.pos++;
               Molang.Expr e = this.expr();
               if (!this.eat(')')) {
                  throw new IllegalArgumentException("missing )");
               } else {
                  return e;
               }
            } else if (Character.isDigit(c) || c == '.') {
               int st = this.pos;

               while (this.pos < this.s.length() && (Character.isDigit(this.s.charAt(this.pos)) || this.s.charAt(this.pos) == '.')) {
                  this.pos++;
               }

               double v = Double.parseDouble(this.s.substring(st, this.pos));
               return t -> v;
            } else if (!Character.isLetter(c) && c != '_') {
               throw new IllegalArgumentException("unexpected '" + c + "'");
            } else {
               int st = this.pos;

               while (
                  this.pos < this.s.length()
                     && (Character.isLetterOrDigit(this.s.charAt(this.pos)) || this.s.charAt(this.pos) == '_' || this.s.charAt(this.pos) == '.')
               ) {
                  this.pos++;
               }

               String id = this.s.substring(st, this.pos);
               if (!this.eat('(')) {
                  return variable(id);
               } else {
                  List<Molang.Expr> args = new ArrayList<>();
                  if (!this.eat(')')) {
                     do {
                        args.add(this.expr());
                     } while (this.eat(','));

                     if (!this.eat(')')) {
                        throw new IllegalArgumentException("missing )");
                     }
                  }

                  return call(id, args);
               }
            }
         }
      }

      private static Molang.Expr variable(String id) {
         switch (id) {
            case "q.anim_time":
            case "query.anim_time":
            case "q.life_time":
            case "query.life_time":
               return t -> t;
            case "math.pi":
               return t -> Math.PI;
            default:
               return Molang.ZERO;
         }
      }

      private static Molang.Expr call(String id, List<Molang.Expr> args) {
         String f = id.startsWith("math.") ? id.substring(5) : id;
         Molang.Expr a = args.size() > 0 ? args.get(0) : Molang.ZERO;
         Molang.Expr b = args.size() > 1 ? args.get(1) : Molang.ZERO;
         Molang.Expr c = args.size() > 2 ? args.get(2) : Molang.ZERO;
         switch (f) {
            case "sin":
               return t -> Math.sin(Math.toRadians(a.eval(t)));
            case "cos":
               return t -> Math.cos(Math.toRadians(a.eval(t)));
            case "abs":
               return t -> Math.abs(a.eval(t));
            case "sqrt":
               return t -> Math.sqrt(Math.max(0.0, a.eval(t)));
            case "floor":
               return t -> Math.floor(a.eval(t));
            case "ceil":
               return t -> Math.ceil(a.eval(t));
            case "round":
               return t -> Math.rint(a.eval(t));
            case "trunc":
               return t -> {
                  double v = a.eval(t);
                  return v < 0.0 ? Math.ceil(v) : Math.floor(v);
               };
            case "min":
               return t -> Math.min(a.eval(t), b.eval(t));
            case "max":
               return t -> Math.max(a.eval(t), b.eval(t));
            case "pow":
               return t -> Math.pow(a.eval(t), b.eval(t));
            case "mod":
               return t -> {
                  double d = b.eval(t);
                  return d == 0.0 ? 0.0 : a.eval(t) % d;
               };
            case "clamp":
               return t -> Math.max(b.eval(t), Math.min(c.eval(t), a.eval(t)));
            case "lerp":
               return t -> {
                  double x = a.eval(t);
                  return x + (b.eval(t) - x) * c.eval(t);
               };
            default:
               return Molang.ZERO;
         }
      }
   }
}
