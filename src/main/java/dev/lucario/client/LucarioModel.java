package dev.lucario.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lucario.LucarioMod;
import dev.lucario.client.anim.BedrockAnimation;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

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
import java.util.Set;
import java.util.function.Function;

/**
 * Lucario model assembled from a Bedrock .geo.json into a ModelPart tree.
 *
 * Bedrock -> Java model coordinate conversion:
 *   x, z unchanged; y_java = 24 - y_bedrock (the Y axis of Java models points down);
 *   rotation angles from the Bedrock file are used as-is (Bedrock already stores them in the "Java-compatible" sign).
 * If some limb bends the wrong way -- see the README note about ROT_SIGN_*.
 */
@Environment(EnvType.CLIENT)
public final class LucarioModel {
    /** Player height in blocks that Lucario is scaled to (ears included). */
    public static final float TARGET_HEIGHT_BLOCKS = 1.8f;
    /** Base model height in Bedrock units (from the soles to the ear tips). */
    private static final float BASE_MODEL_HEIGHT_UNITS = 49.1f;
    /** The same scale for all costumes so hats don't shrink Lucario. */
    public static final float RENDER_SCALE = TARGET_HEIGHT_BLOCKS * 16.0f / BASE_MODEL_HEIGHT_UNITS;

    // Rotation signs (if limbs "bend the wrong way", set the offending axis to -1).
    public static final float ROT_SIGN_X = 1f, ROT_SIGN_Y = 1f, ROT_SIGN_Z = 1f;

    // Sneaking: how many degrees the hips bend and the torso leans, plus the "feet on the ground" fix (Bedrock units).
    private static final double CROUCH_HIP_DEG = 40.0;
    private static final double CROUCH_TORSO_LEAN_DEG = 25.0;
    private static final double CROUCH_GROUND_FIX = 1.4;
    /** How much (Bedrock units) the flat axis of a zero-thickness cube is inflated. */
    private static final double FLAT_CUBE_PUFF = 0.03;
    // Sitting pose: hips lifted slightly, knees spread a bit -- the legs come out straight, not bent.
    private static final double SIT_HIP_LIFT_DEG = 4.0;
    private static final double SIT_KNEE_OPEN_DEG = 16.0;

    public final ModelPart root;
    /** All bones by name (including roots). Each baked model has its own -- see LucarioModels. */
    public final Map<String, ModelPart> parts = new LinkedHashMap<>();
    /** x, y, z, xRot, yRot, zRot -- the bone's original pose. */
    public final Map<String, float[]> base = new HashMap<>();
    /** Animations are shared between bakes: BedrockAnimation is immutable once parsed. */
    public final Map<String, BedrockAnimation> animations;
    /** Parent of each bone (root bones have no entry). */
    public final Map<String, String> parentOf;
    /** Extra pitch (radians) added to the bones while sneaking: hips, knees, feet, torso. */
    public final Map<String, Float> crouchPitch;
    /** Extra pitch (radians) of the hips and knees in the sitting pose (in a vehicle). */
    public final Map<String, Float> sitPitch;
    /** How many Bedrock units the body drops when sneaking so the feet stay on the ground. */
    public final float crouchDrop;
    public final Identifier texture;
    public final Identifier shinyTexture;
    /** Offset that raises the soles to ground level (in Bedrock units, y up). */
    public final float feetY;
    /** Elytra wings -- their own pair of bones per model (they used to be shared -- the same "shared pose" bug). */
    public final ModelPart elytraLeft;
    public final ModelPart elytraRight;
    /** Wraps the elytra wing bones in a Model for submission (setupAnim is empty: the pose is set before the submit). */
    public final Model<LivingEntityRenderState> elytraModel;
    /** Wrapper of the root in a Model for the new render submit pipeline (26.x).
     *  setupAnim is called by the pipeline when drawing the submit -- here the pose is re-applied
     *  from that specific player's state, so identical morphs don't "copy" each other. */
    public final Model<LivingEntityRenderState> entityModel;

    private LucarioModel(ModelPart root, Definition d) {
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
        this.elytraModel = new Model<>(elytra, RenderTypes::entityCutout) {
            @Override
            public void setupAnim(LivingEntityRenderState state) {
            }
        };
        this.entityModel = new Model<>(root, RenderTypes::entityCutout) {
            @Override
            public void setupAnim(LivingEntityRenderState state) {
                if (state instanceof LucarioStateAccess access && access.lucario$getVariant() != null) {
                    LucarioRenderer.pose(LucarioModel.this, state, access);
                }
            }
        };
    }

    public BedrockAnimation animation(String shortName) {
        return animations.get(shortName);
    }

    /** Chain of bones from the root to and including bone (needed to find the hand position for an item). */
    public List<String> chainTo(String bone) {
        List<String> chain = new ArrayList<>();
        String cur = bone;
        int guard = 0;
        while (cur != null && parts.containsKey(cur) && guard++ < 64) {
            chain.add(cur);
            cur = parentOf.get(cur);
        }
        Collections.reverse(chain);
        return chain;
    }

    /** Face-expression bones and utility bones that the animations only show at the right moment. */
    public static boolean hiddenByDefault(String n) {
        return n.contains("anger") || n.contains("squint") || n.contains("emote")
                || n.contains("happy") || n.contains("calm") || n.contains("wide")
                || n.startsWith("mouth_open") || n.contains("teeth_clench") || n.equals("smile")
                || n.startsWith("guiding_plate") || n.endsWith("_aura")
                || n.contains("Pivot");
    }

    // ------------------------------------------------------------------ loading

    /**
     * Everything expensive to parse from JSON and independent of the player: the geometry layer,
     * animations, bone parents, sneaking/sitting adjustments. Cached per base variant and shared;
     * a personal ModelPart tree is baked from it for every player (see bake).
     */
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

        Definition(LayerDefinition layer, float feetY, List<String> boneOrder,
                   Identifier texture, Identifier shinyTexture) {
            this.layer = layer;
            this.feetY = feetY;
            this.boneOrder = boneOrder;
            this.texture = texture;
            this.shinyTexture = shinyTexture;
        }
    }

    /** Parses the variant's Bedrock geometry and animations (the heavy part, done once). */
    public static Definition loadDefinition(ResourceManager rm, String variant) throws IOException {
        JsonObject geoRoot = readJson(rm, Identifier.fromNamespaceAndPath(LucarioMod.MOD_ID, "models/" + variant + ".geo.json"));
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
        // Dimensions (after normalization and inflate) of all regular cubes -- for finding twin cubes
        Set<String> positiveBoxes = new HashSet<>();
        for (JsonObject b : defs.values()) {
            if (!b.has("cubes")) continue;
            for (JsonElement ce : b.getAsJsonArray("cubes")) {
                JsonObject c = ce.getAsJsonObject();
                if (!isNegativeCube(c)) positiveBoxes.add(inflatedBoxKey(c));
            }
        }
        for (String name : defs.keySet()) buildBone(name, defs, built, data.getRoot(), 0, positiveBoxes);

        LayerDefinition layer = LayerDefinition.create(data, texW, texH);

        // soles: the lowest point of the cubes in the foot* bones
        double minFoot = Double.POSITIVE_INFINITY, minAny = Double.POSITIVE_INFINITY;
        for (JsonObject b : defs.values()) {
            String n = b.get("name").getAsString();
            if (hiddenByDefault(n) || !b.has("cubes")) continue;
            for (JsonElement ce : b.getAsJsonArray("cubes")) {
                JsonObject c = ce.getAsJsonObject();
                double y = vec(c, "origin")[1] - (c.has("inflate") ? c.get("inflate").getAsDouble() : 0);
                minAny = Math.min(minAny, y);
                if (n.contains("foot")) minFoot = Math.min(minFoot, y);
            }
        }
        double feet = Double.isInfinite(minFoot) ? (Double.isInfinite(minAny) ? 0 : minAny) : minFoot;

        Definition d = new Definition(layer, (float) feet, new ArrayList<>(defs.keySet()),
                Identifier.fromNamespaceAndPath(LucarioMod.MOD_ID, "textures/entity/" + variant + ".png"),
                Identifier.fromNamespaceAndPath(LucarioMod.MOD_ID, "textures/entity/" + variant + "_shiny.png"));

        // Parents from the geo (needed both for bone lookup and for chains)
        for (Map.Entry<String, JsonObject> e : defs.entrySet()) {
            JsonObject b = e.getValue();
            if (b.has("parent") && defs.containsKey(b.get("parent").getAsString())) {
                d.parentOf.put(e.getKey(), b.get("parent").getAsString());
            }
        }

        computeCrouch(d, defs, feet);
        computeSit(d, defs);
        // animations (1.7.6): the variant's own file is optional. With one, the variant plays
        // only it (that's how mega lives: its file is untouched); without one, it plays the species
        // default (lucario/riolu). All regular Lucario variants and both extra Riolu variants share
        // one default set -- minus 13 files from the jar, and cafe/holiday "on their own" got
        // specialist and the dance, which their old files didn't have. The own file remains
        // a customization point: drop one in -- the variant plays it in full.
        Identifier ownAnim = Identifier.fromNamespaceAndPath(LucarioMod.MOD_ID, "animations/" + variant + ".animation.json");
        Identifier animId = ownAnim;
        if (rm.getResource(ownAnim).isEmpty()) {
            String species = variant.startsWith("riolu") ? "riolu" : "lucario";
            animId = Identifier.fromNamespaceAndPath(LucarioMod.MOD_ID, "animations/" + species + ".animation.json");
            LucarioMod.LOGGER.info("[Lucario Morph] Вариант {}: своего набора анимаций нет — играю стандарт вида ({})",
                    variant, animId.getPath());
        }
        JsonObject anims = readJson(rm, animId).getAsJsonObject("animations");
        for (Map.Entry<String, JsonElement> e : anims.entrySet()) {
            String full = e.getKey();
            String shortName = full.substring(full.lastIndexOf('.') + 1);
            d.animations.put(shortName, new BedrockAnimation(shortName, e.getValue().getAsJsonObject()));
        }
        return d;
    }

    /** Bakes a personal model instance from the definition: a fresh ModelPart tree per player. */
    public static LucarioModel bake(Definition d) {
        ModelPart root = d.layer.bakeRoot();
        LucarioModel m = new LucarioModel(root, d);

        // Bone lookup by name: descend from the root along the parent chain
        Function<String, ModelPart> getter = name -> {
            List<String> path = new ArrayList<>();
            String cur = name;
            while (cur != null) {
                path.add(cur);
                cur = m.parentOf.get(cur);
            }
            Collections.reverse(path);
            ModelPart p = root;
            for (String n : path) {
                if (p == null || !p.hasChild(n)) return null;
                p = p.getChild(n);
            }
            return p;
        };

        for (String name : d.boneOrder) {
            ModelPart p = getter.apply(name);
            if (p == null) continue;
            m.parts.put(name, p);
            m.base.put(name, new float[]{p.x, p.y, p.z, p.xRot, p.yRot, p.zRot});
            if (hiddenByDefault(name)) p.visible = false;
        }
        return m;
    }

    /** Vanilla elytra wing geometry (a shared layer, but the bones are baked per model). */
    private static LayerDefinition elytraLayer() {
        MeshDefinition data = new MeshDefinition();
        PartDefinition root = data.getRoot();
        CubeDeformation d = new CubeDeformation(1.0F);
        // The cubes face INWARD from the axes (as in vanilla): folded wings meet at the spine,
        // and in flight they unfold outward. The cubes used to face outward -- the halves hung at the sides with a gap.
        // The mirroring (mirror) of the halves is swapped: our model is drawn in an X-flipped frame
        // (scale(-1,-1,1)), so the texture halves of the elytra end up on opposite wings.
        // Vanilla mirrors the left wing -- here, given our frame, we mirror the right one.
        root.addOrReplaceChild("left_wing", CubeListBuilder.create().texOffs(22, 0)
                .addBox(-10.0F, 0.0F, 0.0F, 10.0F, 20.0F, 2.0F, d), PartPose.ZERO);
        root.addOrReplaceChild("right_wing", CubeListBuilder.create().texOffs(22, 0).mirror(true)
                .addBox(0.0F, 0.0F, 0.0F, 10.0F, 20.0F, 2.0F, d), PartPose.ZERO);
        return LayerDefinition.create(data, 64, 32);
    }

    /**
     * Sneaking from the model's own leg lengths: hip pitches forward by alpha, knee (leg*3) back by beta, foot levels out;
     * the body drops by crouchDrop and the torso leans forward. Works for both Lucario and Riolu (they share bone names).
     */
    private static void computeCrouch(Definition d, Map<String, JsonObject> defs, double feetY) {
        if (!defs.containsKey("leg_right") || !defs.containsKey("leg_right3")) return;
        double hipY = vec(defs.get("leg_right"), "pivot")[1];
        double kneeY = vec(defs.get("leg_right3"), "pivot")[1];
        double l1 = hipY - kneeY;
        double l2 = kneeY - feetY;
        if (l1 < 0.5 || l2 < 0.5) return;

        double alpha = Math.toRadians(CROUCH_HIP_DEG);
        double gamma = Math.asin(Math.min(1.0, l1 / l2 * Math.sin(alpha)));
        double beta = gamma + alpha;
        d.crouchDrop = (float) ((l1 + l2) - (l1 * Math.cos(alpha) + l2 * Math.cos(gamma)) + CROUCH_GROUND_FIX);

        for (String side : new String[]{"right", "left"}) {
            d.crouchPitch.put("leg_" + side, (float) -alpha);
            d.crouchPitch.put("leg_" + side + "3", (float) beta);
            d.crouchPitch.put("foot_" + side, (float) -gamma);
        }
        if (defs.containsKey("torso")) d.crouchPitch.put("torso", (float) Math.toRadians(CROUCH_TORSO_LEAN_DEG));
    }

    /**
     * Sitting pose from the leg geometry: the hips lift forward by almost 90 degrees (with a correction so the torso
     * doesn't tip back), the knees fold back, so the legs come out straight. Fits all three characters.
     */
    private static void computeSit(Definition d, Map<String, JsonObject> defs) {
        for (String side : new String[]{"right", "left"}) {
            String hip = "leg_" + side;
            String mid = hip + "2";
            String knee = hip + "3";
            if (!defs.containsKey(hip) || !defs.containsKey(knee)) continue;
            float hipBase = (float) Math.toRadians(vec(defs.get(hip), "rotation")[0]);
            float midBase = defs.containsKey(mid) ? (float) Math.toRadians(vec(defs.get(mid), "rotation")[0]) : 0f;
            float kneeBase = (float) Math.toRadians(vec(defs.get(knee), "rotation")[0]);
            d.sitPitch.put(hip, (float) (-Math.PI / 2.0 - hipBase - Math.toRadians(SIT_HIP_LIFT_DEG)));
            d.sitPitch.put(knee, (float) (Math.PI / 2.0 - kneeBase - midBase - Math.toRadians(SIT_KNEE_OPEN_DEG)));
        }
    }

    private static PartDefinition buildBone(String name, Map<String, JsonObject> defs,
                                            Map<String, PartDefinition> built, PartDefinition root, int depth,
                                            Set<String> positiveBoxes) {
        PartDefinition done = built.get(name);
        if (done != null) return done;
        JsonObject b = defs.get(name);

        String parentName = b.has("parent") ? b.get("parent").getAsString() : null;
        PartDefinition parentData = root;
        double[] parentJavaPivot = {0, 0, 0};
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
                if (hasRotation(c)) rotatedCubes.add(c);
                else if (!isShadowTwin(c, positiveBoxes)) addCube(pb, c, bp[0], bp[1], bp[2]);
            }
        }

        PartDefinition self = parentData.addOrReplaceChild(name, pb, PartPose.offsetAndRotation(
                (float) (jp[0] - parentJavaPivot[0]),
                (float) (jp[1] - parentJavaPivot[1]),
                (float) (jp[2] - parentJavaPivot[2]),
                rad(rot[0], ROT_SIGN_X), rad(rot[1], ROT_SIGN_Y), rad(rot[2], ROT_SIGN_Z)));
        built.put(name, self);

        // cubes with their own rotation/pivot -> a separate child part
        int i = 0;
        for (JsonObject c : rotatedCubes) {
            double[] cp = vec(c, "pivot");
            double[] cr = vec(c, "rotation");
            CubeListBuilder cb = CubeListBuilder.create();
            addCube(cb, c, cp[0], cp[1], cp[2]);
            self.addOrReplaceChild(name + "#" + (i++), cb, PartPose.offsetAndRotation(
                    (float) (cp[0] - bp[0]), (float) (bp[1] - cp[1]), (float) (cp[2] - bp[2]),
                    rad(cr[0], ROT_SIGN_X), rad(cr[1], ROT_SIGN_Y), rad(cr[2], ROT_SIGN_Z)));
        }
        return self;
    }

    /** Whether the cube has a negative size (an "inverted" cube from Blockbench). */
    private static boolean isNegativeCube(JsonObject c) {
        for (double s : vec(c, "size")) {
            if (s < 0) return true;
        }
        return false;
    }

    /** Cube dimensions after normalization and inflate (the key for finding matching twins). */
    private static String inflatedBoxKey(JsonObject c) {
        double[] o = vec(c, "origin");
        double[] s = vec(c, "size");
        double inflate = c.has("inflate") ? c.get("inflate").getAsDouble() : 0;
        double[] lo = new double[3], hi = new double[3];
        for (int i = 0; i < 3; i++) {
            lo[i] = Math.min(o[i], o[i] + s[i]) - inflate;
            hi[i] = Math.max(o[i], o[i] + s[i]) + inflate;
        }
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f",
                lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
    }

    /**
     * A twin cube with a negative size (a double-sided shell from Blockbench: the captain's hat, scarf,
     * cuffs). Bedrock renders it mirrored inward, but in the Java model, after normalization, it would
     * coincide exactly with the regular cube -- coplanar faces flicker ("texture flickering / z-fighting").
     * We skip such twins: the regular original cube provides the look.
     */
    private static boolean isShadowTwin(JsonObject c, Set<String> positiveBoxes) {
        return isNegativeCube(c) && positiveBoxes.contains(inflatedBoxKey(c));
    }

    private static void addCube(CubeListBuilder pb, JsonObject c, double px, double py, double pz) {
        double[] o = vec(c, "origin");
        double[] s = vec(c, "size");
        // Blockbench exports "inverted" cubes with negative sizes (double-sided shells:
        // the captain's hat, the scarf on holiday, the arm cuffs). Bedrock renders such a cube as a regular box
        // by AABB (origin..origin+size), while the Java model with negative sizes flips the normals
        // (faces are visible from one side, then the other -- "flickering") and mirrors the UV mapping.
        // We normalize to a positive size, keeping the dimensions and the position in space.
        double[] lo = new double[3], sz = new double[3];
        for (int i = 0; i < 3; i++) {
            lo[i] = Math.min(o[i], o[i] + s[i]);
            sz[i] = Math.abs(s[i]);
        }
        o = lo;
        s = sz;
        double inflate = c.has("inflate") ? c.get("inflate").getAsDouble() : 0;
        JsonArray uv = c.getAsJsonArray("uv");
        boolean mirror = c.has("mirror") && c.get("mirror").getAsBoolean();
        pb.texOffs(uv.get(0).getAsInt(), uv.get(1).getAsInt())
                .mirror(mirror)
                .addBox((float) (o[0] - px), (float) (py - o[1] - s[1]), (float) (o[2] - pz),
                        (float) s[0], (float) s[1], (float) s[2],
                        // zero-thickness cubes (flat spikes/fins) flicker: the front and back faces lie
                        // in the same plane (z-fighting). We slightly "inflate" that axis so the faces separate.
                        new CubeDeformation((float) (inflate + (s[0] == 0 ? FLAT_CUBE_PUFF : 0)),
                                (float) (inflate + (s[1] == 0 ? FLAT_CUBE_PUFF : 0)),
                                (float) (inflate + (s[2] == 0 ? FLAT_CUBE_PUFF : 0))));
    }

    private static boolean hasRotation(JsonObject c) {
        if (!c.has("rotation")) return false;
        double[] r = vec(c, "rotation");
        return r[0] != 0 || r[1] != 0 || r[2] != 0;
    }

    private static double[] javaPivot(JsonObject bone) {
        double[] p = vec(bone, "pivot");
        return new double[]{p[0], 24.0 - p[1], p[2]};
    }

    private static float rad(double deg, float sign) {
        return (float) Math.toRadians(deg) * sign;
    }

    static double[] vec(JsonObject o, String key) {
        double[] r = {0, 0, 0};
        if (o.has(key) && o.get(key).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(key);
            for (int i = 0; i < 3 && i < a.size(); i++) r[i] = a.get(i).getAsDouble();
        }
        return r;
    }

    private static JsonObject readJson(ResourceManager rm, Identifier id) throws IOException {
        Resource res = rm.getResourceOrThrow(id);
        try (BufferedReader r = res.openAsReader()) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }
}
