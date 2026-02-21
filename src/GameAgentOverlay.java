import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

/**
 * 绘制 overlay：collect* / update* 逻辑在本类，使用 GameAgentGameAccess 反射与坐标转换；绘制窗口从本类读取。
 */
public final class GameAgentOverlay {

    private static final int MIN_GAME_VIEW_SIZE = 500;
    private static final int GWL_EXSTYLE = -20;
    private static final int WS_EX_TRANSPARENT = 0x00000020;

    private static JWindow drawOverlayWindow;
    private static javax.swing.Timer drawOverlayTimer;

    /** 本帧待绘制的骨骼/边框/金币世界坐标，由 collect* 写入，update* 读取后转屏。 */
    private static volatile java.util.List<float[]> boneSegmentsForDraw = null;
    private static volatile java.util.List<float[]> wireframeBoundsForDraw = null;
    private static volatile java.util.List<float[]> goldItemsForDraw = null;
    /** 屏幕坐标数据，由 update* 写入，paint 读取。 */
    private static volatile java.util.List<float[]> boneSegmentsScreen = null;
    private static volatile java.util.List<float[]> wireframeBoundsScreen = null;
    private static volatile java.util.List<float[]> goldItemsScreen = null;
    private static volatile float aimbotRangeCircleRadiusPx = 80f;

    private GameAgentOverlay() { }

    static void setBoneSegmentsScreen(java.util.List<float[]> v) { boneSegmentsScreen = v; }
    static void setWireframeBoundsScreen(java.util.List<float[]> v) { wireframeBoundsScreen = v; }
    static void setGoldItemsScreen(java.util.List<float[]> v) { goldItemsScreen = v; }
    static void setAimbotRangeCircleRadiusPx(float v) { aimbotRangeCircleRadiusPx = v; }
    /** 菜单关闭对应开关时由 GameAgent 调用，清空待绘制数据。 */
    public static void clearBoneSegmentsForDraw() { boneSegmentsForDraw = null; }
    public static void clearWireframeBoundsForDraw() { wireframeBoundsForDraw = null; }
    public static void clearGoldItemsForDraw() { goldItemsForDraw = null; goldItemsScreen = null; }

    /** 主循环每帧调用：若对应开关开启则执行本类 collect* 与 update*。 */
    public static void tick(Object mf, long n) {
        if (mf == null) return;
        try {
            if (GameAgent.getSkeletonBonesEnabled() && (n % 2 == 0)) collectBoneSegments(mf);
            if (GameAgent.getWireframeBoundsEnabled() && (n % 2 == 0)) collectWireframeBounds(mf);
            if (GameAgent.getGoldXrayEnabled() && (n % 2 == 0)) collectGoldItems(mf);
        } catch (Throwable ignored) { }
        if (GameAgent.getDrawTestCircleEnabled() || GameAgent.getSkeletonBonesEnabled()
                || GameAgent.getWireframeBoundsEnabled() || GameAgent.getGoldXrayEnabled()) {
            try {
                updateBoneSegmentsScreen(mf);
                updateWireframeBoundsScreen(mf);
                if (GameAgent.getGoldXrayEnabled()) updateGoldItemsScreen(mf);
                updateAimbotRangeCircleRadius(mf);
            } catch (Throwable ignored) { }
        }
    }

    // ---------- collect* / update* 实现（使用 GameAgentGameAccess 与 GameAgent getter） ----------

    static void collectBoneSegments(Object mf) {
        if (mf == null) return;
        try {
            Class<?> mfClass = mf.getClass();
            Field kf = (mfClass == GameAgentGameAccess.cachedMainFrameClass) ? GameAgentGameAccess.cachedKiField : null;
            if (kf == null) {
                kf = mfClass.getDeclaredField("ki");
                kf.setAccessible(true);
                GameAgentGameAccess.cachedMainFrameClass = mfClass;
                GameAgentGameAccess.cachedKiField = kf;
            }
            Object ki = kf.get(mf);
            if (ki == null) return;
            Class<?> kiClass = ki.getClass();
            Field kcf = (kiClass == GameAgentGameAccess.cachedKiClass) ? GameAgentGameAccess.cachedKiGameContextField : null;
            if (kcf == null) {
                kcf = kiClass.getDeclaredField("kiGameContext");
                kcf.setAccessible(true);
                GameAgentGameAccess.cachedKiClass = kiClass;
                GameAgentGameAccess.cachedKiGameContextField = kcf;
            }
            Object kiGameContext = kcf.get(ki);
            if (kiGameContext == null) return;
            Class<?> kgcClass = kiGameContext.getClass();
            Method getPlayers = (kgcClass == GameAgentGameAccess.cachedKiGameContextClass) ? GameAgentGameAccess.cachedGetPlayersMethod : null;
            if (getPlayers == null) {
                Class<?> playerRoleClass = Class.forName("KI.Game.General.TPPlayer$PlayerRole");
                Class<?> triplexClass = Class.forName("Lib.Util.Util$Triplex");
                getPlayers = kgcClass.getMethod("getPlayers", playerRoleClass, triplexClass, triplexClass);
                GameAgentGameAccess.cachedKiGameContextClass = kgcClass;
                GameAgentGameAccess.cachedGetPlayersMethod = getPlayers;
            }
            Class<?> playerRoleClass = Class.forName("KI.Game.General.TPPlayer$PlayerRole");
            Object rolePlayer = Enum.valueOf((Class<Enum>) playerRoleClass, "PLAYER");
            Class<?> triplexClass = Class.forName("Lib.Util.Util$Triplex");
            Object triplexOn = Enum.valueOf((Class<Enum>) triplexClass, "ON");
            Object triplexOff = Enum.valueOf((Class<Enum>) triplexClass, "OFF");
            @SuppressWarnings("unchecked")
            java.util.ArrayList<Object> players = (java.util.ArrayList<Object>) getPlayers.invoke(kiGameContext, rolePlayer, triplexOn, triplexOff);
            if (players == null || players.isEmpty()) {
                boneSegmentsForDraw = java.util.Collections.emptyList();
                return;
            }
            Object activePlayer = null;
            try {
                Method getActivePlayer = ki.getClass().getMethod("getActivePlayer");
                activePlayer = getActivePlayer.invoke(ki);
            } catch (Throwable ignored) { }
            Object first = null;
            for (Object p : players) { if (p != null && p != activePlayer) { first = p; break; } }
            if (first == null) {
                boneSegmentsForDraw = java.util.Collections.emptyList();
                return;
            }
            Class<?> firstClass = first.getClass();
            Method getAnimController, getHumanRig, getBonePositionWorld, getRig;
            if (firstClass == GameAgentGameAccess.cachedFirstPlayerClass) {
                getAnimController = GameAgentGameAccess.cachedGetAnimControllerMethod;
                getHumanRig = GameAgentGameAccess.cachedGetHumanRigMethod;
                getBonePositionWorld = GameAgentGameAccess.cachedGetBonePositionWorldMethod;
                getRig = GameAgentGameAccess.cachedGetRigMethod;
            } else {
                getAnimController = firstClass.getMethod("getAnimController");
                Class<?> animCtrlClass = getAnimController.getReturnType();
                getHumanRig = animCtrlClass.getMethod("getHumanRig");
                getBonePositionWorld = animCtrlClass.getMethod("getBonePositionWorld", String.class);
                getRig = animCtrlClass.getMethod("getRig");
                GameAgentGameAccess.cachedFirstPlayerClass = firstClass;
                GameAgentGameAccess.cachedGetAnimControllerMethod = getAnimController;
                GameAgentGameAccess.cachedGetHumanRigMethod = getHumanRig;
                GameAgentGameAccess.cachedGetBonePositionWorldMethod = getBonePositionWorld;
                GameAgentGameAccess.cachedGetRigMethod = getRig;
            }
            float[] observerPos = null;
            float maxDist = (float) Math.max(1, Math.min(1000, GameAgent.getWireframeSkeletonMaxDistance()));
            if (activePlayer != null) {
                try {
                    Method getCollisionCenter = firstClass.getMethod("getCollisionCenter");
                    Object oc = getCollisionCenter.invoke(activePlayer);
                    observerPos = GameAgentGameAccess.vec3ToFloats(oc);
                } catch (Throwable ignored) { }
            }
            java.util.List<float[]> segments = new java.util.ArrayList<>();
            Class<?> rigClass = getRig.getReturnType();
            Method getNumBones = rigClass.getMethod("getNumBones");
            Method getBoneByIndex = rigClass.getMethod("getBoneByIndex", int.class);
            java.lang.reflect.Field boneNameField = null;
            java.lang.reflect.Field boneParentField = null;
            Method getCollisionCenterPlayer = firstClass.getMethod("getCollisionCenter");
            for (Object player : players) {
                if (player == null || player == activePlayer) continue;
                if (observerPos != null && observerPos.length >= 3) {
                    try {
                        Object pc = getCollisionCenterPlayer.invoke(player);
                        float[] pp = GameAgentGameAccess.vec3ToFloats(pc);
                        if (pp != null && pp.length >= 3) {
                            float dx = pp[0] - observerPos[0], dy = pp[1] - observerPos[1], dz = pp[2] - observerPos[2];
                            if (Math.sqrt(dx * dx + dy * dy + dz * dz) > maxDist) continue;
                        }
                    } catch (Throwable ignored) { }
                }
                Object animCtrl = getAnimController.invoke(player);
                if (animCtrl == null) continue;
                Object rig = getRig.invoke(animCtrl);
                if (rig == null) continue;
                int numBones = ((Number) getNumBones.invoke(rig)).intValue();
                for (int i = 0; i < numBones; i++) {
                    Object bone = getBoneByIndex.invoke(rig, i);
                    if (bone == null) continue;
                    if (boneNameField == null) {
                        boneNameField = bone.getClass().getField("name");
                        boneParentField = bone.getClass().getField("parent");
                    }
                    String name = (String) boneNameField.get(bone);
                    String parent = (String) boneParentField.get(bone);
                    if (parent == null || parent.isEmpty()) continue;
                    Object posA = getBonePositionWorld.invoke(animCtrl, name);
                    Object posB = getBonePositionWorld.invoke(animCtrl, parent);
                    float[] fa = GameAgentGameAccess.vec3ToFloats(posA);
                    float[] fb = GameAgentGameAccess.vec3ToFloats(posB);
                    if (fa != null && fb != null)
                        segments.add(new float[]{fa[0], fa[1], fa[2], fb[0], fb[1], fb[2]});
                }
            }
            boneSegmentsForDraw = segments;
        } catch (Throwable t) {
            boneSegmentsForDraw = null;
        }
    }

    static void collectWireframeBounds(Object mf) {
        if (mf == null) return;
        try {
            Field kf = (GameAgentGameAccess.cachedMainFrameClass != null) ? GameAgentGameAccess.cachedKiField : null;
            if (kf == null) {
                Class<?> mfClass = mf.getClass();
                kf = mfClass.getDeclaredField("ki");
                kf.setAccessible(true);
                GameAgentGameAccess.cachedMainFrameClass = mfClass;
                GameAgentGameAccess.cachedKiField = kf;
            }
            Object ki = kf.get(mf);
            if (ki == null) { wireframeBoundsForDraw = java.util.Collections.emptyList(); return; }
            Field kcf = (GameAgentGameAccess.cachedKiClass != null) ? GameAgentGameAccess.cachedKiGameContextField : null;
            if (kcf == null) {
                Class<?> kiClass = ki.getClass();
                kcf = kiClass.getDeclaredField("kiGameContext");
                kcf.setAccessible(true);
                GameAgentGameAccess.cachedKiClass = kiClass;
                GameAgentGameAccess.cachedKiGameContextField = kcf;
            }
            Object kiGameContext = kcf.get(ki);
            if (kiGameContext == null) { wireframeBoundsForDraw = java.util.Collections.emptyList(); return; }
            Method getPlayers = GameAgentGameAccess.cachedGetPlayersMethod;
            if (getPlayers == null) {
                Class<?> kgcClass = kiGameContext.getClass();
                Class<?> playerRoleClass = Class.forName("KI.Game.General.TPPlayer$PlayerRole");
                Class<?> triplexClass = Class.forName("Lib.Util.Util$Triplex");
                getPlayers = kgcClass.getMethod("getPlayers", playerRoleClass, triplexClass, triplexClass);
                GameAgentGameAccess.cachedKiGameContextClass = kgcClass;
                GameAgentGameAccess.cachedGetPlayersMethod = getPlayers;
            }
            Object rolePlayer = Enum.valueOf((Class<Enum>) Class.forName("KI.Game.General.TPPlayer$PlayerRole"), "PLAYER");
            Object triplexOn = Enum.valueOf((Class<Enum>) Class.forName("Lib.Util.Util$Triplex"), "ON");
            Object triplexOff = Enum.valueOf((Class<Enum>) Class.forName("Lib.Util.Util$Triplex"), "OFF");
            @SuppressWarnings("unchecked")
            java.util.ArrayList<Object> players = (java.util.ArrayList<Object>) getPlayers.invoke(kiGameContext, rolePlayer, triplexOn, triplexOff);
            if (players == null || players.isEmpty()) {
                wireframeBoundsForDraw = java.util.Collections.emptyList();
                return;
            }
            Object activePlayer = null;
            try {
                Method getActivePlayer = ki.getClass().getMethod("getActivePlayer");
                activePlayer = getActivePlayer.invoke(ki);
            } catch (Throwable ignored) { }
            Object first = null;
            for (Object p : players) { if (p != null && p != activePlayer) { first = p; break; } }
            if (first == null) { wireframeBoundsForDraw = java.util.Collections.emptyList(); return; }
            Method getAnimController = GameAgentGameAccess.cachedGetAnimControllerMethod;
            Method getRig = GameAgentGameAccess.cachedGetRigMethod;
            Method getBonePositionWorld = GameAgentGameAccess.cachedGetBonePositionWorldMethod;
            if (getAnimController == null || getRig == null || getBonePositionWorld == null) {
                Class<?> fc = first.getClass();
                getAnimController = fc.getMethod("getAnimController");
                getRig = getAnimController.getReturnType().getMethod("getRig");
                getBonePositionWorld = getAnimController.getReturnType().getMethod("getBonePositionWorld", String.class);
                GameAgentGameAccess.cachedGetAnimControllerMethod = getAnimController;
                GameAgentGameAccess.cachedGetRigMethod = getRig;
                GameAgentGameAccess.cachedGetBonePositionWorldMethod = getBonePositionWorld;
            }
            Class<?> rigClass = getRig.getReturnType();
            Method getNumBones = rigClass.getMethod("getNumBones");
            Method getBoneByIndex = rigClass.getMethod("getBoneByIndex", int.class);
            float maxDist = (float) Math.max(1, Math.min(1000, GameAgent.getWireframeSkeletonMaxDistance()));
            float[] observerPos = null;
            if (activePlayer != null) {
                try {
                    Method getCollisionCenter = first.getClass().getMethod("getCollisionCenter");
                    Object oc = getCollisionCenter.invoke(activePlayer);
                    observerPos = GameAgentGameAccess.vec3ToFloats(oc);
                } catch (Throwable ignored) { }
            }
            Method getCollisionCenterPlayer = first.getClass().getMethod("getCollisionCenter");
            java.util.List<float[]> boxes = new java.util.ArrayList<>();
            java.lang.reflect.Field boneNameField = null;
            for (Object player : players) {
                if (player == null || player == activePlayer) continue;
                if (observerPos != null && observerPos.length >= 3) {
                    try {
                        Object pc = getCollisionCenterPlayer.invoke(player);
                        float[] pp = GameAgentGameAccess.vec3ToFloats(pc);
                        if (pp != null && pp.length >= 3) {
                            float dx = pp[0] - observerPos[0], dy = pp[1] - observerPos[1], dz = pp[2] - observerPos[2];
                            if (Math.sqrt(dx * dx + dy * dy + dz * dz) > maxDist) continue;
                        }
                    } catch (Throwable ignored) { }
                }
                Object animCtrl = getAnimController.invoke(player);
                if (animCtrl == null) continue;
                Object rig = getRig.invoke(animCtrl);
                if (rig == null) continue;
                float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
                float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
                int numBones = ((Number) getNumBones.invoke(rig)).intValue();
                for (int i = 0; i < numBones; i++) {
                    Object bone = getBoneByIndex.invoke(rig, i);
                    if (bone == null) continue;
                    if (boneNameField == null) boneNameField = bone.getClass().getField("name");
                    String name = (String) boneNameField.get(bone);
                    Object pos = getBonePositionWorld.invoke(animCtrl, name);
                    float[] f = GameAgentGameAccess.vec3ToFloats(pos);
                    if (f != null && f.length >= 3) {
                        minX = Math.min(minX, f[0]); minY = Math.min(minY, f[1]); minZ = Math.min(minZ, f[2]);
                        maxX = Math.max(maxX, f[0]); maxY = Math.max(maxY, f[1]); maxZ = Math.max(maxZ, f[2]);
                    }
                }
                if (minX > maxX) continue;
                boxes.add(new float[]{minX, minY, minZ, maxX, maxY, maxZ});
            }
            wireframeBoundsForDraw = boxes;
        } catch (Throwable t) {
            wireframeBoundsForDraw = null;
        }
    }

    static void collectGoldItems(Object mf) {
        if (mf == null) return;
        try {
            Field gf = mf.getClass().getDeclaredField("gameContext");
            gf.setAccessible(true);
            Object gameContext = gf.get(mf);
            if (gameContext == null) { goldItemsForDraw = java.util.Collections.emptyList(); return; }
            Field kf = mf.getClass().getDeclaredField("ki");
            kf.setAccessible(true);
            Object ki = kf.get(mf);
            if (ki == null) { goldItemsForDraw = java.util.Collections.emptyList(); return; }
            Method getActivePlayer = ki.getClass().getMethod("getActivePlayer");
            Object player = getActivePlayer.invoke(ki);
            if (player == null) { goldItemsForDraw = java.util.Collections.emptyList(); return; }
            Method getCollisionCenter = player.getClass().getMethod("getCollisionCenter");
            Object playerCenter = getCollisionCenter.invoke(player);
            float[] pc = GameAgentGameAccess.vec3ToFloats(playerCenter);
            if (pc == null || pc.length < 3) { goldItemsForDraw = java.util.Collections.emptyList(); return; }
            Class<?> entityMapClass = Class.forName("Base.General.Container.EntityMap");
            Method getEntityMap = gameContext.getClass().getMethod("getEntityMap", entityMapClass);
            Object itemPlacementsEnum = Enum.valueOf((Class<Enum>) entityMapClass, "ItemPlacements");
            Object randomPlacementsEnum = Enum.valueOf((Class<Enum>) entityMapClass, "RandomItemPlacements");
            @SuppressWarnings("unchecked")
            java.util.Collection<Object> itemPlacements = (java.util.Collection<Object>) getEntityMap.invoke(gameContext, itemPlacementsEnum);
            @SuppressWarnings("unchecked")
            java.util.Collection<Object> randomPlacements = (java.util.Collection<Object>) getEntityMap.invoke(gameContext, randomPlacementsEnum);
            Class<?> itemPlacementClass = Class.forName("KI.Game.General.ItemPlacement");
            Class<?> kiItemClass = Class.forName("KI.Game.General.KIItem");
            Class<?> kiItemDataClass = Class.forName("KI.Game.General.Resource.KIItemData");
            Class<?> kiItemTypeClass = Class.forName("KI.Game.General.Resource.KIItemData$KIItemType");
            Object goldType = Enum.valueOf((Class<Enum>) kiItemTypeClass, "GOLD");
            Method placementGetItem = itemPlacementClass.getMethod("getItem");
            Method hasItem = itemPlacementClass.getMethod("hasItem");
            Method getData = kiItemClass.getMethod("getData");
            Method hasType = kiItemDataClass.getMethod("hasType", kiItemTypeClass);
            java.lang.reflect.Field valueField = kiItemDataClass.getField("value");
            java.util.List<float[]> list = new java.util.ArrayList<>();
            int maxDist = Math.max(1, Math.min(1000, GameAgent.getGoldXrayMaxDistance()));
            for (Object entity : itemPlacements) {
                if (entity == null || !itemPlacementClass.isInstance(entity)) continue;
                if (!Boolean.TRUE.equals(hasItem.invoke(entity))) continue;
                try {
                    Object item = placementGetItem.invoke(entity);
                    if (item == null || !kiItemClass.isInstance(item)) continue;
                    Object data = getData.invoke(item);
                    if (data == null || !kiItemDataClass.isInstance(data)) continue;
                    int valueG = valueField.getInt(data);
                    if (!Boolean.TRUE.equals(hasType.invoke(data, goldType))) continue;
                    Method getPos = null;
                    try { getPos = entity.getClass().getMethod("getCollisionCenter"); } catch (NoSuchMethodException ignored) { }
                    if (getPos == null) getPos = entity.getClass().getMethod("getPosition");
                    Object pos = getPos.invoke(entity);
                    float[] xyz = GameAgentGameAccess.vec3ToFloats(pos);
                    if (xyz == null || xyz.length < 3) continue;
                    float dx = xyz[0] - pc[0], dy = xyz[1] - pc[1], dz = xyz[2] - pc[2];
                    float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist > maxDist) continue;
                    list.add(new float[]{xyz[0], xyz[1], xyz[2], dist, valueG});
                } catch (Throwable ignored) { }
            }
            for (Object entity : randomPlacements != null ? randomPlacements : java.util.Collections.emptyList()) {
                if (entity == null || !itemPlacementClass.isInstance(entity)) continue;
                if (!Boolean.TRUE.equals(hasItem.invoke(entity))) continue;
                try {
                    Object item = placementGetItem.invoke(entity);
                    if (item == null || !kiItemClass.isInstance(item)) continue;
                    Object data = getData.invoke(item);
                    if (data == null || !kiItemDataClass.isInstance(data)) continue;
                    int valueG = valueField.getInt(data);
                    if (!Boolean.TRUE.equals(hasType.invoke(data, goldType))) continue;
                    Method getPos = null;
                    try { getPos = entity.getClass().getMethod("getCollisionCenter"); } catch (NoSuchMethodException ignored) { }
                    if (getPos == null) getPos = entity.getClass().getMethod("getPosition");
                    Object pos = getPos.invoke(entity);
                    float[] xyz = GameAgentGameAccess.vec3ToFloats(pos);
                    if (xyz == null || xyz.length < 3) continue;
                    float dx = xyz[0] - pc[0], dy = xyz[1] - pc[1], dz = xyz[2] - pc[2];
                    float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist > maxDist) continue;
                    list.add(new float[]{xyz[0], xyz[1], xyz[2], dist, valueG});
                } catch (Throwable ignored) { }
            }
            goldItemsForDraw = list;
        } catch (Throwable t) {
            goldItemsForDraw = null;
        }
    }

    static void updateBoneSegmentsScreen(Object mainFrame) {
        java.util.List<float[]> world = boneSegmentsForDraw;
        if (world == null || world.isEmpty()) {
            boneSegmentsScreen = null;
            return;
        }
        String[] reason = new String[1];
        Object[] state = GameAgentGameAccess.tryGetProjViewState(mainFrame, reason);
        if (state == null) {
            boneSegmentsScreen = null;
            return;
        }
        java.util.List<float[]> screen = new java.util.ArrayList<>(world.size());
        for (float[] seg : world) {
            if (seg == null || seg.length < 6) continue;
            int[] p1 = GameAgentGameAccess.worldToScreenWithState(mainFrame, state, seg[0], seg[1], seg[2]);
            int[] p2 = GameAgentGameAccess.worldToScreenWithState(mainFrame, state, seg[3], seg[4], seg[5]);
            if (p1 != null && p2 != null)
                screen.add(new float[]{p1[0], p1[1], p2[0], p2[1]});
        }
        boneSegmentsScreen = screen;
    }

    static void updateWireframeBoundsScreen(Object mainFrame) {
        java.util.List<float[]> boxes = wireframeBoundsForDraw;
        if (boxes == null || boxes.isEmpty()) {
            wireframeBoundsScreen = null;
            return;
        }
        String[] reason = new String[1];
        Object[] state = GameAgentGameAccess.tryGetProjViewState(mainFrame, reason);
        if (state == null) {
            wireframeBoundsScreen = null;
            return;
        }
        java.util.List<float[]> screen = new java.util.ArrayList<>(boxes.size() * 4);
        for (float[] box : boxes) {
            if (box == null || box.length < 6) continue;
            float x0 = box[0], y0 = box[1], z0 = box[2], x1 = box[3], y1 = box[4], z1 = box[5];
            float[][] corners = {
                {x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0},
                {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}
            };
            int minSx = Integer.MAX_VALUE, minSy = Integer.MAX_VALUE, maxSx = Integer.MIN_VALUE, maxSy = Integer.MIN_VALUE;
            int valid = 0;
            for (float[] c : corners) {
                int[] p = GameAgentGameAccess.worldToScreenWithState(mainFrame, state, c[0], c[1], c[2]);
                if (p != null) {
                    if (p[0] < minSx) minSx = p[0]; if (p[0] > maxSx) maxSx = p[0];
                    if (p[1] < minSy) minSy = p[1]; if (p[1] > maxSy) maxSy = p[1];
                    valid++;
                }
            }
            if (valid == 0) continue;
            screen.add(new float[]{minSx, minSy, maxSx, minSy});
            screen.add(new float[]{maxSx, minSy, maxSx, maxSy});
            screen.add(new float[]{maxSx, maxSy, minSx, maxSy});
            screen.add(new float[]{minSx, maxSy, minSx, minSy});
        }
        wireframeBoundsScreen = screen;
    }

    static void updateGoldItemsScreen(Object mainFrame) {
        java.util.List<float[]> world = goldItemsForDraw;
        if (world == null || world.isEmpty()) {
            goldItemsScreen = null;
            return;
        }
        String[] reason = new String[1];
        Object[] state = GameAgentGameAccess.tryGetProjViewState(mainFrame, reason);
        if (state == null) {
            goldItemsScreen = null;
            return;
        }
        java.util.List<float[]> screen = new java.util.ArrayList<>(world.size());
        for (float[] p : world) {
            if (p == null || p.length < 5) continue;
            int[] sx = GameAgentGameAccess.worldToScreenWithState(mainFrame, state, p[0], p[1], p[2]);
            if (sx != null)
                screen.add(new float[]{sx[0], sx[1], p[3], p[4]});
        }
        goldItemsScreen = screen;
    }

    static void updateAimbotRangeCircleRadius(Object mainFrame) {
        if (mainFrame == null) return;
        try {
            Method getContext = mainFrame.getClass().getMethod("getContext");
            Object context = getContext.invoke(mainFrame);
            if (context == null) return;
            Method getCamera = context.getClass().getMethod("getCamera");
            Object camera = getCamera.invoke(context);
            if (camera == null) return;
            Method getViewport = camera.getClass().getMethod("getViewport");
            Object viewport = getViewport.invoke(camera);
            if (viewport == null) return;
            Method getW = viewport.getClass().getMethod("getWidth");
            int vw = ((Number) getW.invoke(viewport)).intValue();
            if (vw <= 0) return;
            Method getCameraFov = camera.getClass().getMethod("getCameraFOV");
            float horizontalFovDeg = ((Number) getCameraFov.invoke(camera)).floatValue();
            if (horizontalFovDeg <= 0f) return;
            double halfFovRad = Math.toRadians(horizontalFovDeg / 2d);
            double aimbotRad = Math.toRadians((double) GameAgent.getAimbotFovDegrees());
            double viewportRadiusPx = (vw / 2d) * Math.tan(aimbotRad) / Math.tan(halfFovRad);
            if (viewportRadiusPx > 0d) aimbotRangeCircleRadiusPx = (float) viewportRadiusPx;
        } catch (Throwable ignored) { }
    }

    /** 启动绘制 overlay 的 EDT Timer；由 GameAgent 打开菜单 overlay 时调用。 */
    public static void ensureDrawOverlayTimerStarted() {
        if (drawOverlayTimer != null) return;
        drawOverlayTimer = new javax.swing.Timer(16, e -> {
            if (GameAgent.getDrawTestCircleEnabled() || GameAgent.getSkeletonBonesEnabled()
                    || GameAgent.getWireframeBoundsEnabled() || GameAgent.getGoldXrayEnabled()) {
                ensureDrawOverlayAndUpdate(GameAgentGameAccess.getGameWindow(GameAgent.getMainFrameForLoop()));
            } else if (drawOverlayWindow != null && drawOverlayWindow.isDisplayable() && drawOverlayWindow.isVisible()) {
                hideDrawOverlay();
            }
        });
        drawOverlayTimer.setRepeats(true);
        drawOverlayTimer.start();
    }

    /** 显示/更新绘制 overlay 窗口；数据从 GameAgent 读取。 */
    public static void ensureDrawOverlayAndUpdate(Window gameWindow) {
        int x, y, w, h;
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int sw = screen.width, sh = screen.height;
        if (gameWindow != null && gameWindow.isDisplayable()) {
            Point loc = gameWindow.getLocationOnScreen();
            Rectangle bounds = gameWindow.getBounds();
            int gw = bounds.width, gh = bounds.height;
            if (gw >= MIN_GAME_VIEW_SIZE && gh >= MIN_GAME_VIEW_SIZE) {
                x = loc.x; y = loc.y; w = gw; h = gh;
            } else {
                x = 0; y = 0; w = sw; h = sh;
            }
        } else {
            x = 0; y = 0; w = sw; h = sh;
        }
        if (w <= 0 || h <= 0) {
            hideDrawOverlay();
            return;
        }
        try {
            boolean isNew = (drawOverlayWindow == null || !drawOverlayWindow.isDisplayable());
            if (isNew) {
                drawOverlayWindow = new JWindow((Window) null);
                drawOverlayWindow.setBackground(new Color(0, 0, 0, 0));
                JPanel panel = new JPanel() {
                    @Override
                    protected void paintComponent(Graphics g) {
                        int pw = getWidth(), ph = getHeight();
                        g.clearRect(0, 0, pw, ph);
                        if (!GameAgent.getDrawTestCircleEnabled() && !GameAgent.getSkeletonBonesEnabled()
                                && !GameAgent.getWireframeBoundsEnabled() && !GameAgent.getGoldXrayEnabled()) return;
                        Graphics2D g2 = (Graphics2D) g;
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        int cx = pw / 2, cy = ph / 2;
                        if (GameAgent.getDrawTestCircleEnabled()) {
                            int r = (int) aimbotRangeCircleRadiusPx;
                            if (r <= 0) r = 80;
                            int maxR = Math.min(pw, ph) / 2 - 1;
                            if (r > maxR) r = Math.max(1, maxR);
                            g2.setColor(new Color(255, 215, 0));
                            g2.setStroke(new BasicStroke(3f));
                            g2.drawOval(cx - r, cy - r, r * 2, r * 2);
                        }
                        if (GameAgent.getWireframeBoundsEnabled()) {
                            java.util.List<float[]> segs = wireframeBoundsScreen;
                            if (segs != null && !segs.isEmpty()) {
                                g2.setColor(new Color(0, 200, 255));
                                g2.setStroke(new BasicStroke(1.5f));
                                for (float[] s : segs) {
                                    if (s != null && s.length >= 4)
                                        g2.drawLine((int) s[0], (int) s[1], (int) s[2], (int) s[3]);
                                }
                            }
                        }
                        if (GameAgent.getSkeletonBonesEnabled()) {
                            java.util.List<float[]> segs = boneSegmentsScreen;
                            if (segs != null && !segs.isEmpty()) {
                                g2.setColor(new Color(0, 255, 100));
                                g2.setStroke(new BasicStroke(2f));
                                for (float[] s : segs) {
                                    if (s != null && s.length >= 4)
                                        g2.drawLine((int) s[0], (int) s[1], (int) s[2], (int) s[3]);
                                }
                            }
                        }
                        if (GameAgent.getGoldXrayEnabled()) {
                            java.util.List<float[]> gold = goldItemsScreen;
                            if (gold != null && !gold.isEmpty()) {
                                g2.setColor(new Color(255, 215, 0));
                                g2.setStroke(new BasicStroke(1.5f));
                                for (float[] p : gold) {
                                    if (p != null && p.length >= 4) {
                                        int sx = (int) p[0], sy = (int) p[1];
                                        float dist = p[2], valueG = p[3];
                                        int r = 6;
                                        g2.drawRect(sx - r, sy - r, r * 2, r * 2);
                                        g2.setColor(Color.WHITE);
                                        g2.drawString(String.format("%.0fG %.0fm", valueG, dist), sx + r + 2, sy + 4);
                                        g2.setColor(new Color(255, 215, 0));
                                    }
                                }
                            }
                        }
                    }
                };
                panel.setOpaque(false);
                drawOverlayWindow.setContentPane(panel);
                drawOverlayWindow.setFocusableWindowState(false);
                drawOverlayWindow.setAlwaysOnTop(true);
            }
            drawOverlayWindow.setBounds(x, y, w, h);
            drawOverlayWindow.setVisible(true);
            drawOverlayWindow.toFront();
            drawOverlayWindow.getContentPane().repaint();
            setDrawOverlayMouseTransparent();
            boolean vis = drawOverlayWindow.isVisible(), disp = drawOverlayWindow.isDisplayable();
            GameAgent.setLastDrawOverlayDebug(String.format("drawOverlay: %s x=%d y=%d %dx%d visible=%s displayable=%s", isNew ? "新建" : "复用", x, y, w, h, vis, disp));
        } catch (Throwable t) {
            GameAgent.setLastDrawOverlayDebug("drawOverlay 异常: " + t.getClass().getSimpleName() + " " + (t.getMessage() != null ? t.getMessage() : ""));
        }
    }

    private static void setDrawOverlayMouseTransparent() {
        if (drawOverlayWindow == null || !drawOverlayWindow.isDisplayable()) return;
        try {
            long hwndLong = Native.getComponentID(drawOverlayWindow);
            if (hwndLong == 0) return;
            WinDef.HWND hwnd = new WinDef.HWND(Pointer.createConstant(hwndLong));
            int exStyle = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE);
            User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE, exStyle | WS_EX_TRANSPARENT);
        } catch (Throwable t) { }
    }

    /** 隐藏绘制 overlay；由 GameAgent 或 GameAgentUI 调用。 */
    public static void hideDrawOverlay() {
        if (drawOverlayWindow != null && drawOverlayWindow.isDisplayable()) {
            drawOverlayWindow.setVisible(false);
            GameAgent.setLastDrawOverlayDebug("drawOverlay: 已隐藏");
        }
    }

    /** 仅 dispose 绘制 overlay 窗口与 Timer；菜单 overlay 由 GameAgent.disposeOverlay 处理。 */
    public static void disposeDrawOverlay() {
        if (drawOverlayTimer != null) {
            drawOverlayTimer.stop();
            drawOverlayTimer = null;
        }
        if (drawOverlayWindow != null) {
            drawOverlayWindow.dispose();
            drawOverlayWindow = null;
        }
    }
}
