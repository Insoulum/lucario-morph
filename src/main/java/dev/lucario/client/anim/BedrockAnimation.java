package dev.lucario.client.anim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public final class BedrockAnimation {
   public final String name;
   public final boolean loop;
   public final double length;
   private final Map<String, BedrockAnimation.Track> tracks = new HashMap<>();

   public BedrockAnimation(String name, JsonObject json) {
      this.name = name;
      this.loop = json.has("loop")
         && json.get("loop").isJsonPrimitive()
         && json.get("loop").getAsJsonPrimitive().isBoolean()
         && json.get("loop").getAsBoolean();
      this.length = json.has("animation_length")
            && json.get("animation_length").isJsonPrimitive()
            && json.get("animation_length").getAsJsonPrimitive().isNumber()
         ? json.get("animation_length").getAsDouble()
         : 0.0;
      if (json.has("bones")) {
         for (Entry<String, JsonElement> e : json.getAsJsonObject("bones").entrySet()) {
            if (e.getValue().isJsonObject()) {
               JsonObject b = e.getValue().getAsJsonObject();
               BedrockAnimation.Track tr = new BedrockAnimation.Track();
               if (b.has("rotation")) {
                  tr.rotation = parseChannel(b.get("rotation"));
               }

               if (b.has("position")) {
                  tr.position = parseChannel(b.get("position"));
               }

               this.tracks.put(e.getKey(), tr);
            }
         }
      }
   }

   public double wrap(double time) {
      if (this.length > 0.0) {
         return this.loop ? time % this.length : Math.min(time, this.length);
      } else {
         return time;
      }
   }

   public boolean sample(String bone, double time, float[] rot, float[] pos) {
      BedrockAnimation.Track tr = this.tracks.get(bone);
      if (tr == null) {
         rot[0] = rot[1] = rot[2] = 0.0F;
         pos[0] = pos[1] = pos[2] = 0.0F;
         return false;
      } else {
         double t = this.wrap(time);
         if (tr.rotation != null) {
            tr.rotation.eval(t, rot);
         } else {
            rot[0] = rot[1] = rot[2] = 0.0F;
         }

         if (tr.position != null) {
            tr.position.eval(t, pos);
         } else {
            pos[0] = pos[1] = pos[2] = 0.0F;
         }

         return true;
      }
   }

   private static Molang.Expr[] vec(JsonElement e) {
      Molang.Expr[] r = new Molang.Expr[3];
      if (e != null && !e.isJsonNull()) {
         if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();

            for (int i = 0; i < 3; i++) {
               r[i] = Molang.parse(i < a.size() ? a.get(i) : null);
            }
         } else {
            Molang.Expr x = Molang.parse(e);
            r[0] = r[1] = r[2] = x;
         }
      } else {
         for (int i = 0; i < 3; i++) {
            r[i] = Molang.parse(null);
         }
      }

      return r;
   }

   private static BedrockAnimation.Channel parseChannel(JsonElement e) {
      BedrockAnimation.Channel ch = new BedrockAnimation.Channel();
      if (e.isJsonObject()) {
         Iterator var2 = e.getAsJsonObject().entrySet().iterator();

         while (true) {
            Entry<String, JsonElement> en;
            double time;
            while (true) {
               if (!var2.hasNext()) {
                  ch.keys.sort(Comparator.comparingDouble(kx -> kx.time));
                  return ch;
               }

               en = (Entry<String, JsonElement>)var2.next();

               try {
                  time = Double.parseDouble(en.getKey());
                  break;
               } catch (NumberFormatException var11) {
               }
            }

            BedrockAnimation.Key k = new BedrockAnimation.Key();
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
      } else {
         ch.constant = vec(e);
         return ch;
      }
   }

   private static double val(Molang.Expr e, double t) {
      double v = e.eval(t);
      return !Double.isNaN(v) && !Double.isInfinite(v) ? v : 0.0;
   }

   private static double catmullRom(double p0, double p1, double p2, double p3, double f) {
      double f2 = f * f;
      double f3 = f2 * f;
      return 0.5 * (2.0 * p1 + (-p0 + p2) * f + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * f2 + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * f3);
   }

   private static final class Channel {
      final List<BedrockAnimation.Key> keys = new ArrayList<>();
      Molang.Expr[] constant;

      void eval(double t, float[] out) {
         if (this.constant != null) {
            for (int i = 0; i < 3; i++) {
               out[i] = (float)BedrockAnimation.val(this.constant[i], t);
            }
         } else {
            int n = this.keys.size();
            if (n == 0) {
               out[0] = out[1] = out[2] = 0.0F;
            } else {
               BedrockAnimation.Key first = this.keys.get(0);
               if (t <= first.time) {
                  for (int i = 0; i < 3; i++) {
                     out[i] = (float)BedrockAnimation.val(first.post[i], t);
                  }
               } else {
                  BedrockAnimation.Key last = this.keys.get(n - 1);
                  if (t >= last.time) {
                     for (int i = 0; i < 3; i++) {
                        out[i] = (float)BedrockAnimation.val(last.post[i], t);
                     }
                  } else {
                     int i = 0;

                     while (i + 1 < n && this.keys.get(i + 1).time <= t) {
                        i++;
                     }

                     BedrockAnimation.Key a = this.keys.get(i);
                     BedrockAnimation.Key b = this.keys.get(i + 1);
                     double f = (t - a.time) / (b.time - a.time);
                     boolean smooth = a.smooth || b.smooth;

                     for (int c = 0; c < 3; c++) {
                        double p1 = BedrockAnimation.val(a.post[c], t);
                        double p2 = BedrockAnimation.val(b.pre[c], t);
                        if (smooth) {
                           double p0 = i > 0 ? BedrockAnimation.val(this.keys.get(i - 1).post[c], t) : p1;
                           double p3 = i + 2 < n ? BedrockAnimation.val(this.keys.get(i + 2).pre[c], t) : p2;
                           out[c] = (float)BedrockAnimation.catmullRom(p0, p1, p2, p3, f);
                        } else {
                           out[c] = (float)(p1 + (p2 - p1) * f);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static final class Key {
      double time;
      Molang.Expr[] pre;
      Molang.Expr[] post;
      boolean smooth;
   }

   private static final class Track {
      BedrockAnimation.Channel rotation;
      BedrockAnimation.Channel position;
   }
}
