package dev.lucario.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lucario.client.anim.BedrockAnimation;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.Function;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

@Environment(EnvType.CLIENT)
public final class LucarioModel {
   public static final float TARGET_HEIGHT_BLOCKS = 1.8F;
   private static final float BASE_MODEL_HEIGHT_UNITS = 49.1F;
   public static final float RENDER_SCALE = 0.58655804F;
   public static final float ROT_SIGN_X = 1.0F;
   public static final float ROT_SIGN_Y = 1.0F;
   public static final float ROT_SIGN_Z = 1.0F;
   private static final double CROUCH_HIP_DEG = 40.0;
   private static final double CROUCH_TORSO_LEAN_DEG = 25.0;
   private static final double CROUCH_GROUND_FIX = 1.4;
   private static final double FLAT_CUBE_PUFF = 0.03;
   private static final double SIT_HIP_LIFT_DEG = 4.0;
   private static final double SIT_KNEE_OPEN_DEG = 16.0;
   public final ModelPart root;
   public final Map<String, ModelPart> parts = new LinkedHashMap<>();
   public final Map<String, float[]> base = new HashMap<>();
   public final Map<String, BedrockAnimation> animations;
   public final Map<String, String> parentOf;
   public final Map<String, Float> crouchPitch;
   public final Map<String, Float> sitPitch;
   public final float crouchDrop;
   public final Identifier texture;
   public final Identifier shinyTexture;
   public final float feetY;
   public final ModelPart elytraLeft;
   public final ModelPart elytraRight;
   public final Model<LivingEntityRenderState> elytraModel;
   public final Model<LivingEntityRenderState> entityModel;

   private LucarioModel(ModelPart root, LucarioModel.Definition d) {
      this.root = root;
      this.animations = d.animations;
      this.parentOf = d.parentOf;
      this.crouchPitch = d.crouchPitch;
      this.sitPitch = d.sitPitch;
      this.crouchDrop = d.crouchDrop;
      this.texture = d.texture;
      this.shinyTexture = d.shinyTexture;
      this.feetY = d.feetY;
      ModelPart elytra = elytraLayer().bakeRoot();
      this.elytraLeft = elytra.getChild("left_wing");
      this.elytraRight = elytra.getChild("right_wing");
      this.elytraModel = new Model<LivingEntityRenderState>(elytra, RenderTypes::entityCutout) {
         {
            Objects.requireNonNull(LucarioModel.this);
         }

         public void setupAnim(LivingEntityRenderState state) {
         }
      };
      this.entityModel = new Model<LivingEntityRenderState>(root, RenderTypes::entityCutout) {
         {
            Objects.requireNonNull(LucarioModel.this);
         }

         public void setupAnim(LivingEntityRenderState state) {
            if (state instanceof LucarioStateAccess access && access.lucario$getVariant() != null) {
               LucarioRenderer.pose(LucarioModel.this, state, access);
            }
         }
      };
   }

   public BedrockAnimation animation(String shortName) {
      return this.animations.get(shortName);
   }

   public List<String> chainTo(String bone) {
      List<String> chain = new ArrayList<>();
      String cur = bone;
      int guard = 0;

      while (cur != null && this.parts.containsKey(cur) && guard++ < 64) {
         chain.add(cur);
         cur = this.parentOf.get(cur);
      }

      Collections.reverse(chain);
      return chain;
   }

   public static boolean hiddenByDefault(String n) {
      return n.contains("anger")
         || n.contains("squint")
         || n.contains("emote")
         || n.contains("happy")
         || n.contains("calm")
         || n.contains("wide")
         || n.startsWith("mouth_open")
         || n.contains("teeth_clench")
         || n.equals("smile")
         || n.startsWith("guiding_plate")
         || n.endsWith("_aura")
         || n.contains("Pivot");
   }

   public static LucarioModel.Definition loadDefinition(ResourceManager rm, String variant) throws IOException {
      JsonObject geoRoot = readJson(rm, Identifier.fromNamespaceAndPath("lucariomod", "models/" + variant + ".geo.json"));
      JsonObject geo = geoRoot.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
      JsonObject desc = geo.getAsJsonObject("description");
      int texW = desc.get("texture_width").getAsInt();
      int texH = desc.get("texture_height").getAsInt();
      Map<String, JsonObject> defs = new LinkedHashMap<>();

      for (JsonElement e : geo.getAsJsonArray("bones")) {
         JsonObject b = e.getAsJsonObject();
         defs.put(b.get("name").getAsString(), b);
      }

      MeshDefinition data = new MeshDefinition();
      Map<String, PartDefinition> built = new HashMap<>();
      Set<String> positiveBoxes = new HashSet<>();

      for (JsonObject b : defs.values()) {
         if (b.has("cubes")) {
            for (JsonElement ce : b.getAsJsonArray("cubes")) {
               JsonObject c = ce.getAsJsonObject();
               if (!isNegativeCube(c)) {
                  positiveBoxes.add(inflatedBoxKey(c));
               }
            }
         }
      }

      for (String name : defs.keySet()) {
         buildBone(name, defs, built, data.getRoot(), 0, positiveBoxes);
      }

      LayerDefinition layer = LayerDefinition.create(data, texW, texH);
      double minFoot = Double.POSITIVE_INFINITY;
      double minAny = Double.POSITIVE_INFINITY;

      for (JsonObject bx : defs.values()) {
         String n = bx.get("name").getAsString();
         if (!hiddenByDefault(n) && bx.has("cubes")) {
            for (JsonElement cex : bx.getAsJsonArray("cubes")) {
               JsonObject c = cex.getAsJsonObject();
               double y = vec(c, "origin")[1] - (c.has("inflate") ? c.get("inflate").getAsDouble() : 0.0);
               minAny = Math.min(minAny, y);
               if (n.contains("foot")) {
                  minFoot = Math.min(minFoot, y);
               }
            }
         }
      }

      double feet = Double.isInfinite(minFoot) ? (Double.isInfinite(minAny) ? 0.0 : minAny) : minFoot;
      LucarioModel.Definition d = new LucarioModel.Definition(
         layer,
         (float)feet,
         new ArrayList<>(defs.keySet()),
         Identifier.fromNamespaceAndPath("lucariomod", "textures/entity/" + variant + ".png"),
         Identifier.fromNamespaceAndPath("lucariomod", "textures/entity/" + variant + "_shiny.png")
      );

      for (Entry<String, JsonObject> e : defs.entrySet()) {
         JsonObject bxx = e.getValue();
         if (bxx.has("parent") && defs.containsKey(bxx.get("parent").getAsString())) {
            d.parentOf.put(e.getKey(), bxx.get("parent").getAsString());
         }
      }

      computeCrouch(d, defs, feet);
      computeSit(d, defs);
      Identifier animId = Identifier.fromNamespaceAndPath("lucariomod", "animations/" + variant + ".animation.json");
      JsonObject anims = readJson(rm, animId).getAsJsonObject("animations");

      for (Entry<String, JsonElement> ex : anims.entrySet()) {
         String full = ex.getKey();
         String shortName = full.substring(full.lastIndexOf(46) + 1);
         d.animations.put(shortName, new BedrockAnimation(shortName, ex.getValue().getAsJsonObject()));
      }

      return d;
   }

   public static LucarioModel bake(LucarioModel.Definition d) {
      ModelPart root = d.layer.bakeRoot();
      LucarioModel m = new LucarioModel(root, d);
      Function<String, ModelPart> getter = namex -> {
         List<String> path = new ArrayList<>();
         String cur = namex;

         while (cur != null) {
            path.add(cur);
            cur = m.parentOf.get(cur);
         }

         Collections.reverse(path);
         ModelPart px = root;

         for (String n : path) {
            if (px == null || !px.hasChild(n)) {
               return null;
            }

            px = px.getChild(n);
         }

         return px;
      };

      for (String name : d.boneOrder) {
         ModelPart p = getter.apply(name);
         if (p != null) {
            m.parts.put(name, p);
            m.base.put(name, new float[]{p.x, p.y, p.z, p.xRot, p.yRot, p.zRot});
            if (hiddenByDefault(name)) {
               p.visible = false;
            }
         }
      }

      return m;
   }

   private static LayerDefinition elytraLayer() {
      MeshDefinition data = new MeshDefinition();
      PartDefinition root = data.getRoot();
      CubeDeformation d = new CubeDeformation(1.0F);
      root.addOrReplaceChild("left_wing", CubeListBuilder.create().texOffs(22, 0).addBox(-10.0F, 0.0F, 0.0F, 10.0F, 20.0F, 2.0F, d), PartPose.ZERO);
      root.addOrReplaceChild("right_wing", CubeListBuilder.create().texOffs(22, 0).mirror(true).addBox(0.0F, 0.0F, 0.0F, 10.0F, 20.0F, 2.0F, d), PartPose.ZERO);
      return LayerDefinition.create(data, 64, 32);
   }

   private static void computeCrouch(LucarioModel.Definition d, Map<String, JsonObject> defs, double feetY) {
      if (defs.containsKey("leg_right") && defs.containsKey("leg_right3")) {
         double hipY = vec(defs.get("leg_right"), "pivot")[1];
         double kneeY = vec(defs.get("leg_right3"), "pivot")[1];
         double l1 = hipY - kneeY;
         double l2 = kneeY - feetY;
         if (!(l1 < 0.5) && !(l2 < 0.5)) {
            double alpha = Math.toRadians(40.0);
            double gamma = Math.asin(Math.min(1.0, l1 / l2 * Math.sin(alpha)));
            double beta = gamma + alpha;
            d.crouchDrop = (float)(l1 + l2 - (l1 * Math.cos(alpha) + l2 * Math.cos(gamma)) + 1.4);

            for (String side : new String[]{"right", "left"}) {
               d.crouchPitch.put("leg_" + side, (float)(-alpha));
               d.crouchPitch.put("leg_" + side + "3", (float)beta);
               d.crouchPitch.put("foot_" + side, (float)(-gamma));
            }

            if (defs.containsKey("torso")) {
               d.crouchPitch.put("torso", (float)Math.toRadians(25.0));
            }
         }
      }
   }

   private static void computeSit(LucarioModel.Definition d, Map<String, JsonObject> defs) {
      for (String side : new String[]{"right", "left"}) {
         String hip = "leg_" + side;
         String mid = hip + "2";
         String knee = hip + "3";
         if (defs.containsKey(hip) && defs.containsKey(knee)) {
            float hipBase = (float)Math.toRadians(vec(defs.get(hip), "rotation")[0]);
            float midBase = defs.containsKey(mid) ? (float)Math.toRadians(vec(defs.get(mid), "rotation")[0]) : 0.0F;
            float kneeBase = (float)Math.toRadians(vec(defs.get(knee), "rotation")[0]);
            d.sitPitch.put(hip, (float)((-Math.PI / 2) - hipBase - Math.toRadians(4.0)));
            d.sitPitch.put(knee, (float)((Math.PI / 2) - kneeBase - midBase - Math.toRadians(16.0)));
         }
      }
   }

   private static PartDefinition buildBone(
      String name, Map<String, JsonObject> defs, Map<String, PartDefinition> built, PartDefinition root, int depth, Set<String> positiveBoxes
   ) {
      PartDefinition done = built.get(name);
      if (done != null) {
         return done;
      } else {
         JsonObject b = defs.get(name);
         String parentName = b.has("parent") ? b.get("parent").getAsString() : null;
         PartDefinition parentData = root;
         double[] parentJavaPivot = new double[]{0.0, 0.0, 0.0};
         if (parentName != null && defs.containsKey(parentName) && depth < 64) {
            parentData = buildBone(parentName, defs, built, root, depth + 1, positiveBoxes);
            parentJavaPivot = javaPivot(defs.get(parentName));
         }

         double[] bp = vec(b, "pivot");
         double[] jp = javaPivot(b);
         double[] rot = vec(b, "rotation");
         CubeListBuilder pb = CubeListBuilder.create();
         List<JsonObject> rotatedCubes = new ArrayList<>();
         if (b.has("cubes")) {
            for (JsonElement ce : b.getAsJsonArray("cubes")) {
               JsonObject c = ce.getAsJsonObject();
               if (hasRotation(c)) {
                  rotatedCubes.add(c);
               } else if (!isShadowTwin(c, positiveBoxes)) {
                  addCube(pb, c, bp[0], bp[1], bp[2]);
               }
            }
         }

         PartDefinition self = parentData.addOrReplaceChild(
            name,
            pb,
            PartPose.offsetAndRotation(
               (float)(jp[0] - parentJavaPivot[0]),
               (float)(jp[1] - parentJavaPivot[1]),
               (float)(jp[2] - parentJavaPivot[2]),
               rad(rot[0], 1.0F),
               rad(rot[1], 1.0F),
               rad(rot[2], 1.0F)
            )
         );
         built.put(name, self);
         int i = 0;

         for (JsonObject c : rotatedCubes) {
            double[] cp = vec(c, "pivot");
            double[] cr = vec(c, "rotation");
            CubeListBuilder cb = CubeListBuilder.create();
            addCube(cb, c, cp[0], cp[1], cp[2]);
            self.addOrReplaceChild(
               name + "#" + i++,
               cb,
               PartPose.offsetAndRotation(
                  (float)(cp[0] - bp[0]), (float)(bp[1] - cp[1]), (float)(cp[2] - bp[2]), rad(cr[0], 1.0F), rad(cr[1], 1.0F), rad(cr[2], 1.0F)
               )
            );
         }

         return self;
      }
   }

   private static boolean isNegativeCube(JsonObject c) {
      for (double s : vec(c, "size")) {
         if (s < 0.0) {
            return true;
         }
      }

      return false;
   }

   private static String inflatedBoxKey(JsonObject c) {
      double[] o = vec(c, "origin");
      double[] s = vec(c, "size");
      double inflate = c.has("inflate") ? c.get("inflate").getAsDouble() : 0.0;
      double[] lo = new double[3];
      double[] hi = new double[3];

      for (int i = 0; i < 3; i++) {
         lo[i] = Math.min(o[i], o[i] + s[i]) - inflate;
         hi[i] = Math.max(o[i], o[i] + s[i]) + inflate;
      }

      return String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f", lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
   }

   private static boolean isShadowTwin(JsonObject c, Set<String> positiveBoxes) {
      return isNegativeCube(c) && positiveBoxes.contains(inflatedBoxKey(c));
   }

   private static void addCube(CubeListBuilder pb, JsonObject c, double px, double py, double pz) {
      double[] o = vec(c, "origin");
      double[] s = vec(c, "size");
      double[] lo = new double[3];
      double[] sz = new double[3];

      for (int i = 0; i < 3; i++) {
         lo[i] = Math.min(o[i], o[i] + s[i]);
         sz[i] = Math.abs(s[i]);
      }

      double inflate = c.has("inflate") ? c.get("inflate").getAsDouble() : 0.0;
      JsonArray uv = c.getAsJsonArray("uv");
      boolean mirror = c.has("mirror") && c.get("mirror").getAsBoolean();
      pb.texOffs(uv.get(0).getAsInt(), uv.get(1).getAsInt())
         .mirror(mirror)
         .addBox(
            (float)(lo[0] - px),
            (float)(py - lo[1] - sz[1]),
            (float)(lo[2] - pz),
            (float)sz[0],
            (float)sz[1],
            (float)sz[2],
            new CubeDeformation(
               (float)(inflate + (sz[0] == 0.0 ? 0.03 : 0.0)), (float)(inflate + (sz[1] == 0.0 ? 0.03 : 0.0)), (float)(inflate + (sz[2] == 0.0 ? 0.03 : 0.0))
            )
         );
   }

   private static boolean hasRotation(JsonObject c) {
      if (!c.has("rotation")) {
         return false;
      } else {
         double[] r = vec(c, "rotation");
         return r[0] != 0.0 || r[1] != 0.0 || r[2] != 0.0;
      }
   }

   private static double[] javaPivot(JsonObject bone) {
      double[] p = vec(bone, "pivot");
      return new double[]{p[0], 24.0 - p[1], p[2]};
   }

   private static float rad(double deg, float sign) {
      return (float)Math.toRadians(deg) * sign;
   }

   static double[] vec(JsonObject o, String key) {
      double[] r = new double[]{0.0, 0.0, 0.0};
      if (o.has(key) && o.get(key).isJsonArray()) {
         JsonArray a = o.getAsJsonArray(key);

         for (int i = 0; i < 3 && i < a.size(); i++) {
            r[i] = a.get(i).getAsDouble();
         }
      }

      return r;
   }

   private static JsonObject readJson(ResourceManager rm, Identifier id) throws IOException {
      Resource res = rm.getResourceOrThrow(id);

      JsonObject var4;
      try (BufferedReader r = res.openAsReader()) {
         var4 = JsonParser.parseReader(r).getAsJsonObject();
      }

      return var4;
   }

   public static final class Definition {
      final LayerDefinition layer;
      final float feetY;
      final List<String> boneOrder;
      final Map<String, String> parentOf = new HashMap<>();
      final Map<String, BedrockAnimation> animations = new HashMap<>();
      final Map<String, Float> crouchPitch = new HashMap<>();
      final Map<String, Float> sitPitch = new HashMap<>();
      float crouchDrop;
      final Identifier texture;
      final Identifier shinyTexture;

      Definition(LayerDefinition layer, float feetY, List<String> boneOrder, Identifier texture, Identifier shinyTexture) {
         this.layer = layer;
         this.feetY = feetY;
         this.boneOrder = boneOrder;
         this.texture = texture;
         this.shinyTexture = shinyTexture;
      }
   }
}
