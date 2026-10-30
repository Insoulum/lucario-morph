package dev.lucario.client.anim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A Bedrock-format animation (.animation.json): rotation and position per bone, keyframes, and Molang. */
public final class BedrockAnimation {
    public final String name;
    public final boolean loop;
    /** Length in seconds; 0 = unset (time runs on without looping). */
    public final double length;

    private final Map<String, Track> tracks = new HashMap<>();

    private static final class Key {
        double time;
        Molang.Expr[] pre;
        Molang.Expr[] post;
        boolean smooth;
    }

    private static final class Channel {
        final List<Key> keys = new ArrayList<>();
        Molang.Expr[] constant;

        void eval(double t, float[] out) {
            if (constant != null) {
                for (int i = 0; i < 3; i++) out[i] = (float) val(constant[i], t);
                return;
            }
            int n = keys.size();
            if (n == 0) {
                out[0] = out[1] = out[2] = 0;
                return;
            }
            Key first = keys.get(0);
            if (t <= first.time) {
                for (int i = 0; i < 3; i++) out[i] = (float) val(first.post[i], t);
                return;
            }
            Key last = keys.get(n - 1);
            if (t >= last.time) {
                for (int i = 0; i < 3; i++) out[i] = (float) val(last.post[i], t);
                return;
            }
            int i = 0;
            while (i + 1 < n && keys.get(i + 1).time <= t) i++;
            Key a = keys.get(i), b = keys.get(i + 1);
            double f = (t - a.time) / (b.time - a.time);
            boolean smooth = a.smooth || b.smooth;
            for (int c = 0; c < 3; c++) {
                double p1 = val(a.post[c], t), p2 = val(b.pre[c], t);
                if (smooth) {
                    double p0 = i > 0 ? val(keys.get(i - 1).post[c], t) : p1;
                    double p3 = i + 2 < n ? val(keys.get(i + 2).pre[c], t) : p2;
                    out[c] = (float) catmullRom(p0, p1, p2, p3, f);
                } else {
                    out[c] = (float) (p1 + (p2 - p1) * f);
                }
            }
        }
    }

    private static final class Track {
        Channel rotation;
        Channel position;
    }

    public BedrockAnimation(String name, JsonObject json) {
        this.name = name;
        this.loop = json.has("loop") && json.get("loop").isJsonPrimitive()
                && json.get("loop").getAsJsonPrimitive().isBoolean() && json.get("loop").getAsBoolean();
        this.length = json.has("animation_length") && json.get("animation_length").isJsonPrimitive()
                && json.get("animation_length").getAsJsonPrimitive().isNumber()
                ? json.get("animation_length").getAsDouble() : 0.0;
        if (json.has("bones")) {
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("bones").entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject b = e.getValue().getAsJsonObject();
                Track tr = new Track();
                if (b.has("rotation")) tr.rotation = parseChannel(b.get("rotation"));
                if (b.has("position")) tr.position = parseChannel(b.get("position"));
                tracks.put(e.getKey(), tr);
            }
        }
    }

    /** Clamp the time into the animation's working range. */
    public double wrap(double time) {
        if (length > 0) return loop ? time % length : Math.min(time, length);
        return time;
    }

    /**
     * Writes the bone's offsets into rot (degrees) and pos (model units); zeros if the bone is absent.
     * @return true if the bone is present in the animation
     */
    public boolean sample(String bone, double time, float[] rot, float[] pos) {
        Track tr = tracks.get(bone);
        if (tr == null) {
            rot[0] = rot[1] = rot[2] = 0;
            pos[0] = pos[1] = pos[2] = 0;
            return false;
        }
        double t = wrap(time);
        if (tr.rotation != null) tr.rotation.eval(t, rot);
        else rot[0] = rot[1] = rot[2] = 0;
        if (tr.position != null) tr.position.eval(t, pos);
        else pos[0] = pos[1] = pos[2] = 0;
        return true;
    }

    // ---------- parsing ----------

    private static Molang.Expr[] vec(JsonElement e) {
        Molang.Expr[] r = new Molang.Expr[3];
        if (e == null || e.isJsonNull()) {
            for (int i = 0; i < 3; i++) r[i] = Molang.parse(null);
        } else if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (int i = 0; i < 3; i++) r[i] = Molang.parse(i < a.size() ? a.get(i) : null);
        } else {
            Molang.Expr x = Molang.parse(e);
            r[0] = r[1] = r[2] = x;
        }
        return r;
    }

    private static Channel parseChannel(JsonElement e) {
        Channel ch = new Channel();
        if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                double time;
                try {
                    time = Double.parseDouble(en.getKey());
                } catch (NumberFormatException ex) {
                    continue; // auxiliary fields (e.g. "lerp_mode" at the channel level)
                }
                Key k = new Key();
                k.time = time;
                JsonElement v = en.getValue();
                if (v.isJsonObject()) {
                    JsonObject o = v.getAsJsonObject();
                    JsonElement pre = o.has("pre") ? o.get("pre") : o.get("post");
                    JsonElement post = o.has("post") ? o.get("post") : o.get("pre");
                    k.pre = vec(pre);
                    k.post = vec(post);
                    k.smooth = o.has("lerp_mode") && "catmullrom".equals(o.get("lerp_mode").getAsString());
                } else {
                    k.pre = k.post = vec(v);
                }
                ch.keys.add(k);
            }
            ch.keys.sort(Comparator.comparingDouble(k -> k.time));
        } else {
            ch.constant = vec(e);
        }
        return ch;
    }

    private static double val(Molang.Expr e, double t) {
        double v = e.eval(t);
        return Double.isNaN(v) || Double.isInfinite(v) ? 0.0 : v;
    }

    private static double catmullRom(double p0, double p1, double p2, double p3, double f) {
        double f2 = f * f, f3 = f2 * f;
        return 0.5 * ((2 * p1) + (-p0 + p2) * f + (2 * p0 - 5 * p1 + 4 * p2 - p3) * f2
                + (-p0 + 3 * p1 - 3 * p2 + p3) * f3);
    }
}
