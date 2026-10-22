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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Модель Лукарио, собранная из Bedrock .geo.json в дерево ModelPart.
 *
 * Преобразование координат Bedrock -> Java-модель:
 *   x, z без изменений; y_java = 24 - y_bedrock (ось Y у Java-моделей направлена вниз);
 *   углы поворота из файла Bedrock используются как есть (в Bedrock они уже записаны в "Java-совместимом" знаке).
 * Если какая-то конечность вывернута в другую сторону — см. комментарий в README про ROT_SIGN_*.
 */
@Environment(EnvType.CLIENT)
public final class LucarioModel {
    /** Высота игрока в блоках, под которую масштабируется Лукарио (ушки включены). */
    public static final float TARGET_HEIGHT_BLOCKS = 1.8f;
    /** Высота базовой модели в единицах Bedrock (от подошвы до кончиков ушей). */
    private static final float BASE_MODEL_HEIGHT_UNITS = 49.1f;
    /** Масштаб одинаковый для всех костюмов, чтобы шляпы не уменьшали Лукарио. */
    public static final float RENDER_SCALE = TARGET_HEIGHT_BLOCKS * 16.0f / BASE_MODEL_HEIGHT_UNITS;

    // Знаки поворота (если конечности "выворачиваются" — поменяйте на -1 нужной оси).
    public static final float ROT_SIGN_X = 1f, ROT_SIGN_Y = 1f, ROT_SIGN_Z = 1f;

    // Приседание: на сколько градусов сгибаются бёдра и наклоняется корпус, и поправка "ступни на земле" (ед. Bedrock).
    private static final double CROUCH_HIP_DEG = 40.0;
    private static final double CROUCH_TORSO_LEAN_DEG = 25.0;
    private static final double CROUCH_GROUND_FIX = 1.4;
    /** На сколько (ед. Bedrock) раздувается плоская ось куба нулевой толщины. */
    private static final double FLAT_CUBE_PUFF = 0.03;
    // Поза сидения: бёдра чуть приподняты, колени слегка разведены — ноги получаются прямыми, а не согнутыми.
    private static final double SIT_HIP_LIFT_DEG = 4.0;
    private static final double SIT_KNEE_OPEN_DEG = 16.0;

    public final ModelPart root;
    /** Все кости по имени (включая корневые). */
    public final Map<String, ModelPart> parts = new LinkedHashMap<>();
    /** x, y, z, xRot, yRot, zRot — исходная поза кости. */
    public final Map<String, float[]> base = new HashMap<>();
    public final Map<String, BedrockAnimation> animations = new HashMap<>();
    /** Родитель каждой кости (для корневых костей записи нет). */
    public final Map<String, String> parentOf = new HashMap<>();
    /** Добавочный наклон (pitch, радианы) костей при приседании: бёдра, колени, ступни, корпус. */
    public final Map<String, Float> crouchPitch = new HashMap<>();
    /** Добавочный наклон (pitch, радианы) бёдер и коленей в позе сидения (в транспорте). */
    public final Map<String, Float> sitPitch = new HashMap<>();
    /** На сколько единиц Bedrock опускается тело при приседании, чтобы ступни остались на земле. */
    public float crouchDrop;
    public final Identifier texture;
    public final Identifier shinyTexture;
    /** Смещение, поднимающее подошвы на землю (в единицах Bedrock, y вверх). */
    public final float feetY;
    /** Обёртка корня в Model для нового submit-пайплайна рендера (26.x). */
    public final Model<net.minecraft.client.renderer.entity.state.LivingEntityRenderState> entityModel;

    private LucarioModel(ModelPart root, Identifier tex, Identifier shiny, float feetY) {
        this.root = root;
        this.texture = tex;
        this.shinyTexture = shiny;
        this.feetY = feetY;
        this.entityModel = new Model<>(root, RenderTypes::entityCutout) {
            @Override
            public void setupAnim(net.minecraft.client.renderer.entity.state.LivingEntityRenderState state) {
            }
        };
    }

    public BedrockAnimation animation(String shortName) {
        return animations.get(shortName);
    }

    /** Цепочка костей от корня до bone включительно (нужна, чтобы найти положение кисти для предмета). */
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

    /** Кости выражений лица и служебные кости, которые анимации показывают только в нужный момент. */
    public static boolean hiddenByDefault(String n) {
        return n.contains("anger") || n.contains("squint") || n.contains("emote")
                || n.contains("happy") || n.contains("calm") || n.contains("wide")
                || n.startsWith("mouth_open") || n.contains("teeth_clench") || n.equals("smile")
                || n.startsWith("guiding_plate") || n.endsWith("_aura")
                || n.contains("Pivot");
    }

    // ------------------------------------------------------------------ загрузка

    public static LucarioModel load(ResourceManager rm, String variant) throws IOException {
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
        for (String name : defs.keySet()) buildBone(name, defs, built, data.getRoot(), 0);

        ModelPart root = LayerDefinition.create(data, texW, texH).bakeRoot();

        // подошвы: самый низ кубов в костях foot*
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

        // Родители из geo (нужны и для поиска костей, и для цепочек)
        LucarioModel m = new LucarioModel(root,
                Identifier.fromNamespaceAndPath(LucarioMod.MOD_ID, "textures/entity/" + variant + ".png"),
                Identifier.fromNamespaceAndPath(LucarioMod.MOD_ID, "textures/entity/" + variant + "_shiny.png"),
                (float) feet);
        for (Map.Entry<String, JsonObject> e : defs.entrySet()) {
            JsonObject b = e.getValue();
            if (b.has("parent") && defs.containsKey(b.get("parent").getAsString())) {
                m.parentOf.put(e.getKey(), b.get("parent").getAsString());
            }
        }

        // Поиск костей по имени: спускаемся от корня по цепочке родителей
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

        for (String name : defs.keySet()) {
            ModelPart p = getter.apply(name);
            if (p == null) continue;
            m.parts.put(name, p);
            m.base.put(name, new float[]{p.x, p.y, p.z, p.xRot, p.yRot, p.zRot});
            if (hiddenByDefault(name)) p.visible = false;
        }

        m.computeCrouch(defs, feet);
        m.computeSit(defs);

        // анимации
        Identifier animId = Identifier.fromNamespaceAndPath(LucarioMod.MOD_ID, "animations/" + variant + ".animation.json");
        JsonObject anims = readJson(rm, animId).getAsJsonObject("animations");
        for (Map.Entry<String, JsonElement> e : anims.entrySet()) {
            String full = e.getKey();
            String shortName = full.substring(full.lastIndexOf('.') + 1);
            m.animations.put(shortName, new BedrockAnimation(shortName, e.getValue().getAsJsonObject()));
        }
        return m;
    }

    /**
     * Приседание по длинам ног самой модели: бедро вперёд на alpha, колено (leg*3) назад на beta, ступня выравнивается;
     * тело опускается на crouchDrop, корпус наклоняется вперёд. Подходит и Лукарио, и Риолу (у них одни имена костей).
     */
    private void computeCrouch(Map<String, JsonObject> defs, double feetY) {
        if (!defs.containsKey("leg_right") || !defs.containsKey("leg_right3")) return;
        double hipY = vec(defs.get("leg_right"), "pivot")[1];
        double kneeY = vec(defs.get("leg_right3"), "pivot")[1];
        double l1 = hipY - kneeY;
        double l2 = kneeY - feetY;
        if (l1 < 0.5 || l2 < 0.5) return;

        double alpha = Math.toRadians(CROUCH_HIP_DEG);
        double gamma = Math.asin(Math.min(1.0, l1 / l2 * Math.sin(alpha)));
        double beta = gamma + alpha;
        crouchDrop = (float) ((l1 + l2) - (l1 * Math.cos(alpha) + l2 * Math.cos(gamma)) + CROUCH_GROUND_FIX);

        for (String side : new String[]{"right", "left"}) {
            crouchPitch.put("leg_" + side, (float) -alpha);
            crouchPitch.put("leg_" + side + "3", (float) beta);
            crouchPitch.put("foot_" + side, (float) -gamma);
        }
        if (defs.containsKey("torso")) crouchPitch.put("torso", (float) Math.toRadians(CROUCH_TORSO_LEAN_DEG));
    }

    /**
     * Поза сидения по геометрии ног: бёдра поднимаются вперёд почти на 90° (с поправкой, чтобы корпус
     * не заваливался), колени — обратно, так что ноги выходят прямыми. Подходит всем трём персонажам.
     */
    private void computeSit(Map<String, JsonObject> defs) {
        for (String side : new String[]{"right", "left"}) {
            String hip = "leg_" + side;
            String mid = hip + "2";
            String knee = hip + "3";
            if (!defs.containsKey(hip) || !defs.containsKey(knee)) continue;
            float hipBase = (float) Math.toRadians(vec(defs.get(hip), "rotation")[0]);
            float midBase = defs.containsKey(mid) ? (float) Math.toRadians(vec(defs.get(mid), "rotation")[0]) : 0f;
            float kneeBase = (float) Math.toRadians(vec(defs.get(knee), "rotation")[0]);
            sitPitch.put(hip, (float) (-Math.PI / 2.0 - hipBase - Math.toRadians(SIT_HIP_LIFT_DEG)));
            sitPitch.put(knee, (float) (Math.PI / 2.0 - kneeBase - midBase - Math.toRadians(SIT_KNEE_OPEN_DEG)));
        }
    }

    private static PartDefinition buildBone(String name, Map<String, JsonObject> defs,
                                            Map<String, PartDefinition> built, PartDefinition root, int depth) {
        PartDefinition done = built.get(name);
        if (done != null) return done;
        JsonObject b = defs.get(name);

        String parentName = b.has("parent") ? b.get("parent").getAsString() : null;
        PartDefinition parentData = root;
        double[] parentJavaPivot = {0, 0, 0};
        if (parentName != null && defs.containsKey(parentName) && depth < 64) {
            parentData = buildBone(parentName, defs, built, root, depth + 1);
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
                else addCube(pb, c, bp[0], bp[1], bp[2]);
            }
        }

        PartDefinition self = parentData.addOrReplaceChild(name, pb, PartPose.offsetAndRotation(
                (float) (jp[0] - parentJavaPivot[0]),
                (float) (jp[1] - parentJavaPivot[1]),
                (float) (jp[2] - parentJavaPivot[2]),
                rad(rot[0], ROT_SIGN_X), rad(rot[1], ROT_SIGN_Y), rad(rot[2], ROT_SIGN_Z)));
        built.put(name, self);

        // кубы со своим поворотом/пивотом -> отдельная дочерняя часть
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

    private static void addCube(CubeListBuilder pb, JsonObject c, double px, double py, double pz) {
        double[] o = vec(c, "origin");
        double[] s = vec(c, "size");
        double inflate = c.has("inflate") ? c.get("inflate").getAsDouble() : 0;
        JsonArray uv = c.getAsJsonArray("uv");
        boolean mirror = c.has("mirror") && c.get("mirror").getAsBoolean();
        pb.texOffs(uv.get(0).getAsInt(), uv.get(1).getAsInt())
                .mirror(mirror)
                .addBox((float) (o[0] - px), (float) (py - o[1] - s[1]), (float) (o[2] - pz),
                        (float) s[0], (float) s[1], (float) s[2],
                        // кубы нулевой толщины (плоские шипы/плавники) дают мерцание: лицевая и задняя грани лежат
                        // в одной плоскости (z-fighting). Слегка "надуваем" такую ось, чтобы грани разошлись.
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
