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
    /** Все кости по имени (включая корневые). Свои у каждой выпеченной модели — см. LucarioModels. */
    public final Map<String, ModelPart> parts = new LinkedHashMap<>();
    /** x, y, z, xRot, yRot, zRot — исходная поза кости. */
    public final Map<String, float[]> base = new HashMap<>();
    /** Анимации шарятся между выпечками: BedrockAnimation после разбора неизменяемый. */
    public final Map<String, BedrockAnimation> animations;
    /** Родитель каждой кости (для корневых костей записи нет). */
    public final Map<String, String> parentOf;
    /** Добавочный наклон (pitch, радианы) костей при приседании: бёдра, колени, ступни, корпус. */
    public final Map<String, Float> crouchPitch;
    /** Добавочный наклон (pitch, радианы) бёдер и коленей в позе сидения (в транспорте). */
    public final Map<String, Float> sitPitch;
    /** На сколько единиц Bedrock опускается тело при приседании, чтобы ступни остались на земле. */
    public final float crouchDrop;
    public final Identifier texture;
    public final Identifier shinyTexture;
    /** Смещение, поднимающее подошвы на землю (в единицах Bedrock, y вверх). */
    public final float feetY;
    /** Крылья элитр — своя пара костей у каждой модели (раньше были общими — та же ошибка "общей позы"). */
    public final ModelPart elytraLeft;
    public final ModelPart elytraRight;
    /** Обёртка крыльев элитр в Model для сабмита (кости — личные, setupAnim пустой: поза ставится перед сабмитом). */
    public final Model<LivingEntityRenderState> elytraModel;
    /** Обёртка корня в Model для нового submit-пайплайна рендера (26.x).
     *  setupAnim вызывается пайплайном при отрисовке сабмита — здесь поза переприменяется
     *  из состояния этого конкретного игрока, поэтому одинаковые образы не "копируют" друг друга. */
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

    /**
     * Всё, что дорого разбирать из JSON и что не зависит от игрока: слой геометрии,
     * анимации, кости-родители, поправки приседа/сидения. Кэшируется на базовый вариант
     * и шарится; из него выпекается личное дерево ModelPart на каждого игрока (см. bake).
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

    /** Разбор Bedrock-геометрии и анимаций варианта (тяжёлая часть, делается один раз). */
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
        // Габариты (после нормализации и inflate) всех обычных кубов — для поиска кубов-близнецов
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

        Definition d = new Definition(layer, (float) feet, new ArrayList<>(defs.keySet()),
                Identifier.fromNamespaceAndPath(LucarioMod.MOD_ID, "textures/entity/" + variant + ".png"),
                Identifier.fromNamespaceAndPath(LucarioMod.MOD_ID, "textures/entity/" + variant + "_shiny.png"));

        // Родители из geo (нужны и для поиска костей, и для цепочек)
        for (Map.Entry<String, JsonObject> e : defs.entrySet()) {
            JsonObject b = e.getValue();
            if (b.has("parent") && defs.containsKey(b.get("parent").getAsString())) {
                d.parentOf.put(e.getKey(), b.get("parent").getAsString());
            }
        }

        computeCrouch(d, defs, feet);
        computeSit(d, defs);
        // анимации (1.7.6): файл варианта опционален. Есть свой файл — вариант играет
        // только его (мега так и живёт: её файл не тронут); нет своего — играет стандарт
        // вида (lucario/riolu). Все обычные варианты Лукарио и оба доп. Риолу делят
        // один стандартный набор — минус 13 файлов из jar, а у cafe/holiday «сами»
        // появились specialist и танец, которых в их старых файлах не было. Свой файл
        // остаётся точкой кастомизации: положили — вариант играет его целиком.
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

    /** Выпечка личного экземпляра модели из определения: свежее дерево ModelPart на игрока. */
    public static LucarioModel bake(Definition d) {
        ModelPart root = d.layer.bakeRoot();
        LucarioModel m = new LucarioModel(root, d);

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

        for (String name : d.boneOrder) {
            ModelPart p = getter.apply(name);
            if (p == null) continue;
            m.parts.put(name, p);
            m.base.put(name, new float[]{p.x, p.y, p.z, p.xRot, p.yRot, p.zRot});
            if (hiddenByDefault(name)) p.visible = false;
        }
        return m;
    }

    /** Геометрия ванильных крыльев элитр (общий слой, но кости выпекаются на каждую модель). */
    private static LayerDefinition elytraLayer() {
        MeshDefinition data = new MeshDefinition();
        PartDefinition root = data.getRoot();
        CubeDeformation d = new CubeDeformation(1.0F);
        // Кубы направлены ВНУТРЬ от осей (как в ваниле): сложенные крылья сходятся у позвоночника,
        // а в полёте разворачиваются наружу. Раньше кубы шли наружу — половины висели по сторонам с зазором.
        // Зеркалирование (mirror) у половин перепутано: наша модель рисуется в перевёрнутом по X фрейме
        // (scale(-1,-1,1)), из-за чего текстурные половинки элитры оказываются на противоположных крыльях.
        // У ванилы зеркалится левое крыло — здесь, с учётом нашего фрейма, зеркалим правое.
        root.addOrReplaceChild("left_wing", CubeListBuilder.create().texOffs(22, 0)
                .addBox(-10.0F, 0.0F, 0.0F, 10.0F, 20.0F, 2.0F, d), PartPose.ZERO);
        root.addOrReplaceChild("right_wing", CubeListBuilder.create().texOffs(22, 0).mirror(true)
                .addBox(0.0F, 0.0F, 0.0F, 10.0F, 20.0F, 2.0F, d), PartPose.ZERO);
        return LayerDefinition.create(data, 64, 32);
    }

    /**
     * Приседание по длинам ног самой модели: бедро вперёд на alpha, колено (leg*3) назад на beta, ступня выравнивается;
     * тело опускается на crouchDrop, корпус наклоняется вперёд. Подходит и Лукарио, и Риолу (у них одни имена костей).
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
     * Поза сидения по геометрии ног: бёдра поднимаются вперёд почти на 90° (с поправкой, чтобы корпус
     * не заваливался), колени — обратно, так что ноги выходят прямыми. Подходит всем трём персонажам.
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

    /** Есть ли у куба отрицательный размер ("вывернутый" куб из Blockbench). */
    private static boolean isNegativeCube(JsonObject c) {
        for (double s : vec(c, "size")) {
            if (s < 0) return true;
        }
        return false;
    }

    /** Габариты куба после нормализации и inflate (ключ для поиска совпадающих близнецов). */
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
     * Куб-близнец с отрицательным размером (двусторонняя оболочка Blockbench: шляпа капитана, шарф,
     * манжеты). Bedrock рисует его зеркально внутрь, а в Java-модели после нормализации он точно
     * совпал бы с обычным кубом — копланарные грани мерцают ("переливание текстур"). Такой близнец
     * пропускаем: внешний вид даёт обычный куб-оригинал.
     */
    private static boolean isShadowTwin(JsonObject c, Set<String> positiveBoxes) {
        return isNegativeCube(c) && positiveBoxes.contains(inflatedBoxKey(c));
    }

    private static void addCube(CubeListBuilder pb, JsonObject c, double px, double py, double pz) {
        double[] o = vec(c, "origin");
        double[] s = vec(c, "size");
        // Blockbench экспортирует "вывернутые" кубы с отрицательным размером (двусторонние оболочки:
        // шляпа капитана, шарф на holiday, манжеты рук). Bedrock рисует такой куб как обычный ящик
        // по AABB (origin..origin+size), а Java-модель с отрицательными размерами выворачивает нормали
        // (грани видны то с одной, то с другой стороны — "переливание") и зеркалит UV-развёртку.
        // Нормализуем к положительному размеру, сохранив габариты и точку в пространстве.
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
