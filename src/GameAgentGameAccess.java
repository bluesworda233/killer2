import java.awt.Window;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 反射与游戏对象访问：getGameWindow、世界/屏幕坐标转换、玩家名、骨骼位置、反射缓存等。
 * 供 GameAgentFeatures、GameAgentOverlay、GameAgentUI 使用。
 */
public final class GameAgentGameAccess {

    /** 反射缓存：MainFrame.ki、KIGameContext.getPlayers、AnimController.getHumanRig 等 */
    static volatile Class<?> cachedMainFrameClass;
    static volatile Field cachedKiField;
    static volatile Class<?> cachedKiClass;
    static volatile Field cachedKiGameContextField;
    static volatile Class<?> cachedKiGameContextClass;
    static volatile Method cachedGetPlayersMethod;
    static volatile Class<?> cachedFirstPlayerClass;
    static volatile Method cachedGetAnimControllerMethod, cachedGetHumanRigMethod, cachedGetBonePositionWorldMethod, cachedGetRigMethod;

    /** 固定部位：中文 -> HumanRig 骨骼字段名 */
    public static final java.util.Map<String, String> AIMBOT_BONE_CN_TO_FIELD = new java.util.LinkedHashMap<>();
    static {
        AIMBOT_BONE_CN_TO_FIELD.put("头", "headJoint");
        AIMBOT_BONE_CN_TO_FIELD.put("躯干", "spine02Joint");
        AIMBOT_BONE_CN_TO_FIELD.put("左眼", "eyeLJoint");
        AIMBOT_BONE_CN_TO_FIELD.put("右眼", "eyeRJoint");
    }

    private GameAgentGameAccess() { }

    /**
     * 获取游戏主窗口（JFrame）。Main.MainFrame 有字段 app 即 JFrame。
     */
    public static Window getGameWindow(Object mainFrame) {
        if (mainFrame == null) return null;
        if (mainFrame instanceof Window) return (Window) mainFrame;
        try {
            Field appField = mainFrame.getClass().getDeclaredField("app");
            appField.setAccessible(true);
            Object app = appField.get(mainFrame);
            if (app instanceof Window) return (Window) app;
        } catch (Throwable ignored) { }
        return null;
    }

    public static float[] vec3ToFloats(Object vec3) {
        if (vec3 == null) return null;
        try {
            Class<?> c = vec3.getClass();
            Field fx = c.getField("x"), fy = c.getField("y"), fz = c.getField("z");
            float x = ((Number) fx.get(vec3)).floatValue();
            float y = ((Number) fy.get(vec3)).floatValue();
            float z = ((Number) fz.get(vec3)).floatValue();
            return new float[]{x, y, z};
        } catch (Throwable t) {
            return null;
        }
    }

    public static String formatVec3(Object vec3) {
        if (vec3 == null) return null;
        try {
            float[] f = vec3ToFloats(vec3);
            if (f != null) return String.format("(%.2f, %.2f, %.2f)", f[0], f[1], f[2]);
        } catch (Throwable t) { }
        return vec3 != null ? vec3.toString() : "null";
    }

    /** 仅校验渲染环境并缓存：mainFrame→context→camera→viewport→matrix、vw/vh。返回 Object[] { matProjView, Integer(vw), Integer(vh) } 或 null。 */
    public static Object[] tryGetProjViewState(Object mainFrame, String[] outReason) {
        if (mainFrame == null) {
            if (outReason != null) outReason[0] = "mainFrame=null";
            return null;
        }
        try {
            Method getContext = mainFrame.getClass().getMethod("getContext");
            Object context = getContext.invoke(mainFrame);
            if (context == null) {
                if (outReason != null) outReason[0] = "context=null";
                return null;
            }
            Method getCamera = context.getClass().getMethod("getCamera");
            Object camera = getCamera.invoke(context);
            if (camera == null) {
                if (outReason != null) outReason[0] = "camera=null";
                return null;
            }
            Method getViewport = camera.getClass().getMethod("getViewport");
            Object viewport = getViewport.invoke(camera);
            if (viewport == null) {
                if (outReason != null) outReason[0] = "viewport=null";
                return null;
            }
            Method getProjView = viewport.getClass().getMethod("getProjViewMatrix");
            float[] matProjView = (float[]) getProjView.invoke(viewport);
            if (matProjView == null || matProjView.length < 16) {
                if (outReason != null) outReason[0] = "matrix=null";
                return null;
            }
            Method getW = viewport.getClass().getMethod("getWidth");
            Method getH = viewport.getClass().getMethod("getHeight");
            int vw = ((Number) getW.invoke(viewport)).intValue();
            int vh = ((Number) getH.invoke(viewport)).intValue();
            if (vw <= 0 || vh <= 0) {
                if (outReason != null) outReason[0] = "vw=" + vw + " vh=" + vh;
                return null;
            }
            return new Object[]{matProjView, Integer.valueOf(vw), Integer.valueOf(vh)};
        } catch (Throwable t) {
            if (outReason != null) outReason[0] = (t.getClass().getSimpleName() + (t.getMessage() != null ? " " + t.getMessage() : ""));
            return null;
        }
    }

    /** 使用已缓存的 state 做单点投影。w<=0 时返回 null。 */
    public static int[] worldToScreenWithState(Object mainFrame, Object[] state, float wx, float wy, float wz) {
        if (mainFrame == null || state == null || state.length < 3) return null;
        float[] matProjView = (float[]) state[0];
        int vw = ((Number) state[1]).intValue();
        int vh = ((Number) state[2]).intValue();
        if (matProjView == null || matProjView.length < 16 || vw <= 0 || vh <= 0) return null;
        try {
            ClassLoader cl = mainFrame.getClass().getClassLoader();
            Class<?> vec3Class = cl.loadClass("Lib.Math.Vec3");
            java.lang.reflect.Constructor<?> vec3Ctor = vec3Class.getConstructor(float.class, float.class, float.class, float.class);
            Object vec = vec3Ctor.newInstance(wx, wy, wz, 1f);
            Method matrix16Mul = vec3Class.getMethod("matrix16Mul", float[].class, boolean.class);
            matrix16Mul.invoke(vec, matProjView, false);
            Field fx = vec3Class.getField("x"), fy = vec3Class.getField("y"), fw = vec3Class.getField("w");
            float x = ((Number) fx.get(vec)).floatValue();
            float y = ((Number) fy.get(vec)).floatValue();
            float w = ((Number) fw.get(vec)).floatValue();
            if (w <= 0f) return null;
            float ndcX = x / w, ndcY = y / w;
            int sx = (int) ((ndcX + 1f) * 0.5f * vw);
            int sy = (int) ((1f - ndcY) * 0.5f * vh);
            return new int[]{sx, sy};
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 世界坐标转屏幕坐标，失败时 outReason[0] 写入原因。 */
    public static int[] tryWorldToScreenWithReason(Object mainFrame, float wx, float wy, float wz, String[] outReason) {
        if (mainFrame == null) {
            if (outReason != null) outReason[0] = "mainFrame=null";
            return null;
        }
        try {
            Method getContext = mainFrame.getClass().getMethod("getContext");
            Object context = getContext.invoke(mainFrame);
            if (context == null) {
                if (outReason != null) outReason[0] = "context=null";
                return null;
            }
            Method getCamera = context.getClass().getMethod("getCamera");
            Object camera = getCamera.invoke(context);
            if (camera == null) {
                if (outReason != null) outReason[0] = "camera=null";
                return null;
            }
            Method getViewport = camera.getClass().getMethod("getViewport");
            Object viewport = getViewport.invoke(camera);
            if (viewport == null) {
                if (outReason != null) outReason[0] = "viewport=null";
                return null;
            }
            Method getProjView = viewport.getClass().getMethod("getProjViewMatrix");
            float[] matProjView = (float[]) getProjView.invoke(viewport);
            if (matProjView == null || matProjView.length < 16) {
                if (outReason != null) outReason[0] = "matrix=null";
                return null;
            }
            Method getW = viewport.getClass().getMethod("getWidth");
            Method getH = viewport.getClass().getMethod("getHeight");
            int vw = ((Number) getW.invoke(viewport)).intValue();
            int vh = ((Number) getH.invoke(viewport)).intValue();
            if (vw <= 0 || vh <= 0) {
                if (outReason != null) outReason[0] = "vw=" + vw + " vh=" + vh;
                return null;
            }
            ClassLoader cl = mainFrame.getClass().getClassLoader();
            Class<?> vec3Class = cl.loadClass("Lib.Math.Vec3");
            java.lang.reflect.Constructor<?> vec3Ctor = vec3Class.getConstructor(float.class, float.class, float.class, float.class);
            Object vec = vec3Ctor.newInstance(wx, wy, wz, 1f);
            Method matrix16Mul = vec3Class.getMethod("matrix16Mul", float[].class, boolean.class);
            matrix16Mul.invoke(vec, matProjView, false);
            Field fx = vec3Class.getField("x"), fy = vec3Class.getField("y"), fw = vec3Class.getField("w");
            float x = ((Number) fx.get(vec)).floatValue();
            float y = ((Number) fy.get(vec)).floatValue();
            float w = ((Number) fw.get(vec)).floatValue();
            if (w <= 0f) {
                if (outReason != null) outReason[0] = "w<=0";
                return null;
            }
            float ndcX = x / w, ndcY = y / w;
            int sx = (int) ((ndcX + 1f) * 0.5f * vw);
            int sy = (int) ((1f - ndcY) * 0.5f * vh);
            return new int[]{sx, sy};
        } catch (Throwable t) {
            if (outReason != null) outReason[0] = (t.getClass().getSimpleName() + (t.getMessage() != null ? " " + t.getMessage() : ""));
            return null;
        }
    }

    public static String getPlayerDisplayName(Object player) {
        if (player == null) return "";
        for (String methodName : new String[]{"getDisplayName", "getName", "getAccountID", "getPlayerName"}) {
            try {
                Method m = player.getClass().getMethod(methodName);
                Object v = m.invoke(player);
                if (v != null && !String.valueOf(v).isEmpty()) return String.valueOf(v).trim();
            } catch (Throwable ignored) { }
        }
        return player.getClass().getSimpleName();
    }

    public static String getPlayerName(Object player) {
        if (player == null) return "";
        try {
            Method m = player.getClass().getMethod("getPlayerName");
            Object v = m.invoke(player);
            if (v != null && !String.valueOf(v).isEmpty()) return String.valueOf(v).trim();
        } catch (Throwable ignored) { }
        try {
            Field f = player.getClass().getDeclaredField("playerName");
            f.setAccessible(true);
            Object v = f.get(player);
            if (v != null && !String.valueOf(v).isEmpty()) return String.valueOf(v).trim();
        } catch (Throwable ignored) { }
        for (String methodName : new String[]{"getAccountID", "getName", "getDisplayName"}) {
            try {
                Method m = player.getClass().getMethod(methodName);
                Object v = m.invoke(player);
                if (v != null && !String.valueOf(v).isEmpty()) return String.valueOf(v).trim();
            } catch (Throwable ignored) { }
        }
        return player.getClass().getSimpleName();
    }

    /** 取目标指定骨骼的世界坐标：AnimController.getBonePositionWorld(boneFieldName)，取不到返回 null。 */
    public static Object getBonePositionWorld(Object animController, String boneFieldName) {
        if (animController == null || boneFieldName == null || boneFieldName.isEmpty()) return null;
        try {
            Method m = animController.getClass().getMethod("getBonePositionWorld", String.class);
            return m.invoke(animController, boneFieldName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String getBoneFieldFromDisplay(String display) {
        return AIMBOT_BONE_CN_TO_FIELD.containsKey(display) ? AIMBOT_BONE_CN_TO_FIELD.get(display) : display;
    }

    public static String getDisplayForBoneField(String field) {
        for (java.util.Map.Entry<String, String> e : AIMBOT_BONE_CN_TO_FIELD.entrySet()) {
            if (e.getValue().equals(field)) return e.getKey();
        }
        return field;
    }
}
