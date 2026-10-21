package dev.lucario.client.anim;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Минимальный интерпретатор Molang: арифметика, скобки, math.sin/cos/abs/clamp/min/max/pow/sqrt/...
 * и переменная query.anim_time (q.anim_time). Этого хватает для анимаций Лукарио.
 */
public final class Molang {
    public interface Expr {
        double eval(double animTime);
    }

    private static final Expr ZERO = t -> 0.0;

    private Molang() {}

    public static Expr parse(JsonElement el) {
        if (el == null || el.isJsonNull()) return ZERO;
        if (!el.isJsonPrimitive()) return ZERO;
        JsonPrimitive p = el.getAsJsonPrimitive();
        if (p.isNumber()) {
            double v = p.getAsDouble();
            return t -> v;
        }
        String s = p.getAsString().trim();
        try {
            double v = Double.parseDouble(s);
            return t -> v;
        } catch (NumberFormatException ignored) {
        }
        try {
            return new Parser(s).expr();
        } catch (RuntimeException e) {
            return ZERO;
        }
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String src) {
            String x = src.toLowerCase(Locale.ROOT).trim();
            if (x.startsWith("return ")) x = x.substring(7);
            if (x.endsWith(";")) x = x.substring(0, x.length() - 1);
            this.s = x;
        }

        private void ws() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private boolean eat(char c) {
            ws();
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        Expr expr() {
            Expr l = term();
            for (;;) {
                if (eat('+')) {
                    final Expr a = l, b = term();
                    l = t -> a.eval(t) + b.eval(t);
                } else if (eat('-')) {
                    final Expr a = l, b = term();
                    l = t -> a.eval(t) - b.eval(t);
                } else {
                    return l;
                }
            }
        }

        private Expr term() {
            Expr l = unary();
            for (;;) {
                if (eat('*')) {
                    final Expr a = l, b = unary();
                    l = t -> a.eval(t) * b.eval(t);
                } else if (eat('/')) {
                    final Expr a = l, b = unary();
                    l = t -> {
                        double d = b.eval(t);
                        return d == 0 ? 0 : a.eval(t) / d;
                    };
                } else {
                    return l;
                }
            }
        }

        private Expr unary() {
            if (eat('-')) {
                final Expr e = unary();
                return t -> -e.eval(t);
            }
            if (eat('+')) return unary();
            return primary();
        }

        private Expr primary() {
            ws();
            if (pos >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                Expr e = expr();
                if (!eat(')')) throw new IllegalArgumentException("missing )");
                return e;
            }
            if (Character.isDigit(c) || c == '.') {
                int st = pos;
                while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
                final double v = Double.parseDouble(s.substring(st, pos));
                return t -> v;
            }
            if (Character.isLetter(c) || c == '_') {
                int st = pos;
                while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos))
                        || s.charAt(pos) == '_' || s.charAt(pos) == '.')) pos++;
                String id = s.substring(st, pos);
                if (eat('(')) {
                    List<Expr> args = new ArrayList<>();
                    if (!eat(')')) {
                        do {
                            args.add(expr());
                        } while (eat(','));
                        if (!eat(')')) throw new IllegalArgumentException("missing )");
                    }
                    return call(id, args);
                }
                return variable(id);
            }
            throw new IllegalArgumentException("unexpected '" + c + "'");
        }

        private static Expr variable(String id) {
            switch (id) {
                case "q.anim_time":
                case "query.anim_time":
                case "q.life_time":
                case "query.life_time":
                    return t -> t;
                case "math.pi":
                    return t -> Math.PI;
                default:
                    return ZERO;
            }
        }

        private static Expr call(String id, List<Expr> args) {
            final String f = id.startsWith("math.") ? id.substring(5) : id;
            final Expr a = args.size() > 0 ? args.get(0) : ZERO;
            final Expr b = args.size() > 1 ? args.get(1) : ZERO;
            final Expr c = args.size() > 2 ? args.get(2) : ZERO;
            switch (f) {
                case "sin": return t -> Math.sin(Math.toRadians(a.eval(t)));
                case "cos": return t -> Math.cos(Math.toRadians(a.eval(t)));
                case "abs": return t -> Math.abs(a.eval(t));
                case "sqrt": return t -> Math.sqrt(Math.max(0, a.eval(t)));
                case "floor": return t -> Math.floor(a.eval(t));
                case "ceil": return t -> Math.ceil(a.eval(t));
                case "round": return t -> Math.rint(a.eval(t));
                case "trunc": return t -> { double v = a.eval(t); return v < 0 ? Math.ceil(v) : Math.floor(v); };
                case "min": return t -> Math.min(a.eval(t), b.eval(t));
                case "max": return t -> Math.max(a.eval(t), b.eval(t));
                case "pow": return t -> Math.pow(a.eval(t), b.eval(t));
                case "mod": return t -> { double d = b.eval(t); return d == 0 ? 0 : a.eval(t) % d; };
                case "clamp": return t -> Math.max(b.eval(t), Math.min(c.eval(t), a.eval(t)));
                case "lerp": return t -> { double x = a.eval(t); return x + (b.eval(t) - x) * c.eval(t); };
                default: return ZERO;
            }
        }
    }
}
