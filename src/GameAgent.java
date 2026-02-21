import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.HashSet;

/**
 * Agent 入口与主循环：agentmain、onRunLoopEnter/Exit、tickFromMainLoop（委托 GameAgentFeatures.tick、GameAgentOverlay.tick）。
 * 保留：instrumentation、mainFrameForLoop、runLoopHookCallCount、lastRunLoopHookStatus；
 * 功能开关与 last*（供 UI/Overlay 读写）；菜单 overlay 构建与事件（OVERLAY_LISTENER、showOverlayAndUpdateDebug、syncOverlayButtonsFromFlags）；
 * 各 apply/collect/update 实现由 Features/Overlay 委托调用。绘制 overlay 与 Insert 热键已迁至 GameAgentOverlay/GameAgentUI。
 */
public class GameAgent implements ActionListener, MouseListener, MouseMotionListener {

    /** 单例：作为 overlay 所有按钮/拖拽的监听器，避免内部类导致 NoClassDefFoundError */
    private static final GameAgent OVERLAY_LISTENER = new GameAgent();

    private static final int[] overlayDragStart = new int[2];
    private static Component overlayTitleBarRef;
    private static JButton overlayCloseButtonRef;
    private static JToggleButton overlayVisionRef, overlayGoldXrayRef, overlayZeroCooldownRef, overlayMeleeRef, overlayInteractDistRef, overlayFrontPickRef, overlayNoRecoilRef, overlayPerfectPickRef, overlayAlwaysSprintRef, overlayPerfectBlockRef, overlayAimbotRef, overlaySkeletonRef, overlayWireframeRef, overlayDrawTestCircleRef;
    private static JComboBox<String> overlayAimbotBoneRef;
    private static JSlider overlayAimbotRangeSliderRef;
    private static JSlider overlayAimbotMaxDistRef;
    private static JSlider overlayAimbotSmoothRef;
    private static JToggleButton overlayAimbotVisibleOnlyRef;

    /** 快速开火开启状态，供 updatePlayerInfo 在窗口内显示 lastZeroCooldownStatus */
    private static volatile boolean zeroCooldownEnabled = false;
    /** 近战动作加速：定时对当前 ACTION 动画 setRate(MELEE_ANIM_SPEED_MULTIPLIER)，仅当玩家处于 MELEE 状态时生效 */
    /**
     * performRaycast Hook 可行性（MCP 逆向结论）：
     * Game.Abilities.Types.AbilityAttack.performRaycast(result, startPos, direction, projectFromCamera, testWeaponAngle)
     * 内先算 castStart、castDir，再 physics.rayCast(castStart, castDir, info, result)，result 经 IMPACT 发到服务端。
     * 服务端 validateImpact 用 clientResult.rayStart、clientResult.ray 重打射线，不重算 range。
     * 故 hook performRaycast 在调用 physics.rayCast 前改 castStart（或 castDir）即可实现：
     * - 近战无视范围：castDir 设为朝向目标的单位向量再 mul(足够大)，或 castStart 前移到目标附近；
     * - 魔术子弹：castStart 设为目标位置、castDir 设短向量，则射线必中该目标。需 Instrumentation retransform AbilityAttack。
     */
    private static volatile boolean meleeAnimSpeedEnabled = false;
    /** 近战动作播放速率倍数（1=原速，3=三倍速） */
    private static final float MELEE_ANIM_SPEED_MULTIPLIER = 3f;
    /** 无后座：定时将 FIREARM_RECOIL_MULTIPLIER 设为 0，关闭时恢复 1 */
    private static volatile boolean noRecoilEnabled = false;
    /** 红外透视（仅属性）：轮询设 INFRARED/PLAYER_VISION=1，不改移速与体力回复 */
    private static volatile boolean visionBypassEnabled = false;
    /** 金币透视：根据碰撞体绘制金币/物品位置并显示距离 */
    private static volatile boolean goldXrayEnabled = false;
    /** 金币透视最大距离(游戏单位)，1~1000。 */
    private static volatile int goldXrayMaxDistance = 300;
    /** 方框/骨骼透视最大距离(游戏单位)，1~1000；与边框、骨骼共用。 */
    private static volatile int wireframeSkeletonMaxDistance = 300;
    /** 放大交互距离：开启时轮询将所有 IO 的 interactDistance 设为 999 */
    private static volatile boolean interactDistanceEnabled = false;
    /** 完美偷窃：扒窃小游戏开启时自动成功并选第一件物品，只偷一次不重复触发 */
    private static volatile boolean perfectPickpocketEnabled = false;
    /** 偷窃优先级 1/2/3：KIItemType 枚举名（如 GOLD、RANGED、MELEE），程序按序优先拿对应类型物品。 */
    private static volatile String stealPriority1 = "GOLD";
    private static volatile String stealPriority2 = "RANGED";
    private static volatile String stealPriority3 = "MELEE";
    private static JComboBox<String> overlayStealPriority1Ref, overlayStealPriority2Ref, overlayStealPriority3Ref;
    /** 正面偷窃：关闭所有 IO 的 checkFacingAngle/checkPlayerAngle，允许正面触发扒窃 */
    private static volatile boolean frontPickpocketEnabled = false;
    /** 无限体力：轮询将 STAMINA 设为 STAMINA_MAX，体力始终满。 */
    private static volatile boolean alwaysSprintEnabled = false;
    /** 完美格挡：轮询检测到己方为防守方且攻击方动画时间在完美窗口内时，自动 setBlockingPaired(true)，由游戏按原逻辑判定 PERFECT，不篡改配置避免服务端校验。 */
    private static volatile boolean perfectBlockEnabled = false;
    /** 自瞄：轮询将视角对准最近且位于准星附近的敌方玩家，瞄准点使用 HumanRig 所选骨骼。 */
    private static volatile boolean aimbotEnabled = false;
    /** 边框：每帧收集玩家包围盒（AABB），在 overlay 上绘制线框盒。 */
    private static volatile boolean wireframeBoundsEnabled = false;
    /** 骨骼：每帧收集玩家完整骨架（Rig 父子骨骼线段），在 overlay 上绘制。 */
    private static volatile boolean skeletonBonesEnabled = false;
    /** 自瞄范围圈：在屏幕中央画金色圆，半径由游戏视口+水平 FOV+aimbotFovDegrees 计算，与自瞄 FOV 一致。 */
    private static volatile boolean drawTestCircleEnabled = false;
    /** 自瞄瞄准骨骼：HumanRig 上的关节字段名，初始化默认头部；用户在下拉框中选择后会保存于此。 */
    private static volatile String aimbotSelectedBoneField = "headJoint";
    /** 自瞄 FOV 半角(度)，与 GTFOHax aimFov/2 一致，仅当目标与视线夹角≤此值才锁，minDot=cos(°)。默认 20。 */
    private static volatile int aimbotFovDegrees = 20;
    /** 自瞄最大距离(游戏单位)，0=不限制，1~1000。 */
    private static volatile float aimbotMaxDistance = 0f;
    /** 自瞄平滑 0~100：0=瞬间对准，100=不移动(仅保留当前朝向)。 */
    private static volatile int aimbotSmoothingPercent = 0;
    /** 自瞄仅可见：仅瞄准不被掩体遮挡的玩家（getVisibilityInfo.realtimeVisible）。 */
    private static volatile boolean aimbotVisibleOnly = false;
    /** 穿墙全图：起点前移（游戏单位），castStart = 目标 - 单位向×此值。0=射线起点恰为自瞄设定部位位置。仅代码可改，无配置。 */
    private static final float RAY_TRACK_START_OFFSET = 0f;
    /** 穿墙全图：射线长度（游戏单位），castDir = 单位向×此值。短射线即可命中。仅代码可改，无配置。 */
    private static final float RAY_TRACK_LENGTH = 1.5f;
    /** HumanRig 上所有带 boneName 的关节字段名（运行时发现，用于骨骼下拉框）。 */
    private static final java.util.List<String> aimbotBoneFieldOptions = new java.util.ArrayList<>();
    /** KIItemType 枚举名 -> 中文显示，供偷窃优先级下拉框。 */
    private static final java.util.Map<String, String> KI_ITEM_TYPE_TO_CN = new java.util.LinkedHashMap<>();
    /** Overlay 切换状态及调试信息，updatePlayerInfo 显示 */
    private static volatile boolean overlayEnabled = false;
    private static volatile String lastOverlayDebugStatus = "";
    /** Insert 热键 hook 安装结果与触发记录，updatePlayerInfo 显示 */
    private static volatile String lastInsertHookStatus = "";
    /** Agent 注入时传入，用于 retransform 主循环 */
    static Instrumentation instrumentation;
    /** 主循环 hook 调用计数（每帧入口+出口各 +1），用于帧间隔节流 */
    private static volatile long runLoopHookCallCount = 0;
    private static volatile String lastRunLoopHookStatus = "";
    /** 主循环驱动用 mainFrame，agentmain 拿到 mainFrame 时设置 */
    private static volatile Object mainFrameForLoop = null;

    // ----- 供 GameAgentFeatures.tick 读写的开关 getter -----
    public static boolean getZeroCooldownEnabled() { return zeroCooldownEnabled; }
    public static boolean getAimbotEnabled() { return aimbotEnabled; }
    public static boolean getPerfectBlockEnabled() { return perfectBlockEnabled; }
    public static boolean getMeleeAnimSpeedEnabled() { return meleeAnimSpeedEnabled; }
    public static boolean getNoRecoilEnabled() { return noRecoilEnabled; }
    public static boolean getVisionBypassEnabled() { return visionBypassEnabled; }
    public static boolean getInteractDistanceEnabled() { return interactDistanceEnabled; }
    public static boolean getPerfectPickpocketEnabled() { return perfectPickpocketEnabled; }
    public static boolean getFrontPickpocketEnabled() { return frontPickpocketEnabled; }
    public static boolean getAlwaysSprintEnabled() { return alwaysSprintEnabled; }
    public static boolean getSkeletonBonesEnabled() { return skeletonBonesEnabled; }
    public static boolean getWireframeBoundsEnabled() { return wireframeBoundsEnabled; }
    public static boolean getGoldXrayEnabled() { return goldXrayEnabled; }
    public static boolean getDrawTestCircleEnabled() { return drawTestCircleEnabled; }
    public static boolean getOverlayEnabled() { return overlayEnabled; }
    public static void setOverlayEnabled(boolean v) { overlayEnabled = v; }
    public static String getLastZeroCooldownStatus() { return GameAgentFeatures.getLastZeroCooldownStatus(); }
    public static String getLastVisionDebugStatus() { return GameAgentFeatures.getLastVisionDebugStatus(); }
    public static String getLastMeleeDebugStatus() { return GameAgentFeatures.getLastMeleeDebugStatus(); }
    public static String getLastOverlayDebugStatus() { return lastOverlayDebugStatus; }
    public static String getLastInsertHookStatus() { return lastInsertHookStatus; }
    public static void setLastInsertHookStatus(String s) { lastInsertHookStatus = s; }
    public static String getLastRunLoopHookStatus() { return lastRunLoopHookStatus; }
    public static String getLastAimbotDebugStatus() { return GameAgentFeatures.getLastAimbotDebugStatus(); }
    public static String getLastDrawOverlayDebug() { return lastDrawOverlayDebug; }
    public static void setLastDrawOverlayDebug(String s) { lastDrawOverlayDebug = s; }
    public static Object getMainFrameForLoop() { return mainFrameForLoop; }
    public static int getWireframeSkeletonMaxDistance() { return wireframeSkeletonMaxDistance; }
    public static int getGoldXrayMaxDistance() { return goldXrayMaxDistance; }
    public static int getAimbotFovDegrees() { return aimbotFovDegrees; }
    public static String getAimbotSelectedBoneField() { return aimbotSelectedBoneField; }
    public static float getAimbotMaxDistance() { return aimbotMaxDistance; }
    public static int getAimbotSmoothingPercent() { return aimbotSmoothingPercent; }
    public static boolean getAimbotVisibleOnly() { return aimbotVisibleOnly; }
    public static String getStealPriority1() { return stealPriority1; }
    public static String getStealPriority2() { return stealPriority2; }
    public static String getStealPriority3() { return stealPriority3; }
    public static Object getAimbotLockedTargetRef() { return GameAgentFeatures.getAimbotLockedTargetRef(); }
    public static Object getAimbotLockedTargetPos() { return GameAgentFeatures.getAimbotLockedTargetPos(); }
    public static String getAimbotLockedTargetName() { return GameAgentFeatures.getAimbotLockedTargetName(); }
    public static String getAimbotLockedTargetPlayerName() { return GameAgentFeatures.getAimbotLockedTargetPlayerName(); }
    public static boolean getAimbotAimHeld() { return GameAgentFeatures.getAimbotAimHeld(); }

    /** 主窗口关闭时由 GameAgentUI 调用：清空 UI 窗口引用与主帧，所有功能开关与 last*，并 dispose overlay。 */
    public static void resetOnDebugWindowClosed() {
        GameAgentUI.clearDebugWindowState();
        GameAgentFeatures.clearFeatureState();
        mainFrameForLoop = null;
        zeroCooldownEnabled = false;
        meleeAnimSpeedEnabled = false;
        noRecoilEnabled = false;
        visionBypassEnabled = false;
        interactDistanceEnabled = false;
        perfectPickpocketEnabled = false;
        frontPickpocketEnabled = false;
        alwaysSprintEnabled = false;
        perfectBlockEnabled = false;
        aimbotEnabled = false;
        overlayEnabled = false;
        lastOverlayDebugStatus = "";
        disposeOverlay();
    }

    /** 主循环 runLoop() 入口：ASM 织入的调用点 */
    public static void onRunLoopEnter() {
        runLoopHookCallCount++;
    }
    /** 主循环 runLoop() 出口：ASM 织入的调用点，每帧驱动原轮询逻辑（无 Timer 浪费） */
    public static void onRunLoopExit() {
        tickFromMainLoop();
    }

    /** 主循环每帧驱动：原 zeroCooldownTimer 轮询逻辑，按帧间隔节流。mainFrame 来自 mainFrameForLoop。 */
    private static void tickFromMainLoop() {
        Object mf = mainFrameForLoop;
        if (mf == null) return;
        long n = runLoopHookCallCount;
        if (n <= 50 || n % 10 == 0) {
            try {
                ClassLoader agentCl = GameAgent.class.getClassLoader();
                Thread current = Thread.currentThread();
                current.setContextClassLoader(agentCl);
                ThreadGroup root = current.getThreadGroup();
                while (root.getParent() != null) root = root.getParent();
                Thread[] threads = new Thread[Math.min(root.activeCount() * 2 + 32, 512)];
                int cnt = root.enumerate(threads, true);
                for (int i = 0; i < cnt && i < 256; i++) {
                    if (threads[i] != null) threads[i].setContextClassLoader(agentCl);
                }
            } catch (Throwable ignored) { }
        }
        try {
            GameAgentFeatures.tick(mf, n);
        } catch (Throwable ignored) { }
        try {
            GameAgentOverlay.tick(mf, n);
        } catch (Throwable ignored) { }
        if (n <= 2 || n % 500 == 0) lastRunLoopHookStatus = "主循环驱动 #" + n;
        // 数据更新在主循环；绘制由 drawOverlayTimer 在 EDT 上定时执行，避免每帧 invokeLater 卡死菜单
    }

    static void collectBoneSegments(Object mf) { GameAgentOverlay.collectBoneSegments(mf); }
    static void collectWireframeBounds(Object mf) { GameAgentOverlay.collectWireframeBounds(mf); }
    static void collectGoldItems(Object mf) { GameAgentOverlay.collectGoldItems(mf); }

    static void updateGoldItemsScreen(Object mainFrame) { GameAgentOverlay.updateGoldItemsScreen(mainFrame); }
    static void updateBoneSegmentsScreen(Object mainFrame) { GameAgentOverlay.updateBoneSegmentsScreen(mainFrame); }
    static void updateWireframeBoundsScreen(Object mainFrame) { GameAgentOverlay.updateWireframeBoundsScreen(mainFrame); }
    static void updateAimbotRangeCircleRadius(Object mainFrame) { GameAgentOverlay.updateAimbotRangeCircleRadius(mainFrame); }

    public static void agentmain(String args, Instrumentation inst) {
        instrumentation = inst;
        try {
            GameAgentUI.startInsertHotkeyJna();
        } catch (Throwable t) {
            lastInsertHookStatus = "JNA 热键启动异常: " + t.getClass().getName() + " " + t.getMessage();
            t.printStackTrace();
        }
        // 在 agentmain 线程做 retransform，避免在 EDT 上做时上下文类加载器是游戏的，导致 NoClassDefFoundError: GameAgent$RunLoopHookTransformer
        Object mainFrameForRetransform = null;
        try {
            Class<?> appClass = Class.forName("Main.App");
            Method getMainFrame = appClass.getMethod("getMainFrame");
            mainFrameForRetransform = getMainFrame.invoke(null);
            mainFrameForLoop = mainFrameForRetransform;
        } catch (Throwable ignored) { }
        if (mainFrameForRetransform != null && inst.isRetransformClassesSupported() && inst.isModifiableClass(mainFrameForRetransform.getClass())) {
            ClassLoader agentLoader = GameAgent.class.getClassLoader();
            ThreadGroup root = Thread.currentThread().getThreadGroup();
            while (root.getParent() != null) root = root.getParent();
            int est = Math.min(root.activeCount() * 2, 512);
            Thread[] threads = new Thread[est];
            int n = root.enumerate(threads, true);
            ClassLoader[] savedCcl = n > 0 ? new ClassLoader[n] : null;
            for (int i = 0; i < n; i++) {
                if (threads[i] != null) {
                    try {
                        savedCcl[i] = threads[i].getContextClassLoader();
                        threads[i].setContextClassLoader(agentLoader);
                    } catch (Throwable ignored) { }
                }
            }
            try {
                Class<?> mainFrameClass = mainFrameForRetransform.getClass();
                String mainFrameInternal = mainFrameClass.getName().replace('.', '/');
                // 不再 hook 任何绘制（GPUCanvas.render），仅 hook 主循环 runLoop
                ClassFileTransformer transformer = AgentBootstrap.getRunLoopTransformer(mainFrameInternal, null);
                inst.addTransformer(transformer, true);
                inst.retransformClasses(mainFrameClass);
                inst.removeTransformer(transformer);
                lastRunLoopHookStatus = AgentBootstrap.runLoopHookStatus != null ? AgentBootstrap.runLoopHookStatus : "runLoop hook 已安装";
            } catch (Throwable t) {
                lastRunLoopHookStatus = "runLoop hook 安装失败:\n" + AgentBootstrap.formatThrowable(t);
            } finally {
                for (int i = 0; savedCcl != null && i < savedCcl.length && i < threads.length; i++) {
                    if (threads[i] != null && savedCcl[i] != null) {
                        try { threads[i].setContextClassLoader(savedCcl[i]); } catch (Throwable ignored) { }
                    }
                }
            }
        }

        SwingUtilities.invokeLater(() -> {
            try {
                StringBuilder header = new StringBuilder();
                header.append("=== 测试数据 ===\n\n");

                Object mainFrame = null;
                try {
                    Class<?> appClass = Class.forName("Main.App");
                    Method getMainFrame = appClass.getMethod("getMainFrame");
                    mainFrame = getMainFrame.invoke(null);
                } catch (Throwable ignored) { }

                if (mainFrame == null) {
                    showTestWindow(header.toString(), null);
                    return;
                }
                mainFrameForLoop = mainFrame;
                showTestWindow(header.toString(), mainFrame);
            } catch (Throwable t) {
                t.printStackTrace();
                showTestWindow("Agent 异常: " + t.getClass().getSimpleName() + "\n" + t.getMessage(), null);
            }
        });
    }

    private static void showTestWindow(String headerText, Object mainFrame) {
        GameAgentUI.showTestWindow(headerText, mainFrame);
    }

    /** Insert 热键或窗口内快捷键：切换菜单 overlay 显示；由 GameAgentUI 调用。 */
    public static void toggleOverlayFromInsert() {
        Object mf = GameAgentUI.getMainFrameForShortcut();
        if (mf == null) return;
        overlayEnabled = !overlayEnabled;
        if (overlayEnabled) showOverlayAndUpdateDebug(mf);
        else hideOverlayAndUpdateDebug();
        GameAgentUI.syncOverlayToggleButton(overlayEnabled);
    }

    private static JWindow overlayWindow;

    /** 绘制 overlay 调试信息，显示在 DEBUG 窗口「边框/骨骼」下。 */
    private static volatile String lastDrawOverlayDebug = "";
    /** 边框/骨骼收集与转屏调试：收集段数、转屏段数、异常等。 */
    private static volatile String lastWireframeBonesDebug = "";
    /** 主窗口的「打开菜单」按钮引用，overlay 被关闭时同步取消选中 */
    /** overlay 内监听器用 */
    private static volatile Object overlayMainFrameRef;

    @Override
    public void actionPerformed(ActionEvent e) {
        Object src = e.getSource();
        if (src == overlayCloseButtonRef) {
            overlayEnabled = false;
            hideOverlayAndUpdateDebug();
            GameAgentUI.syncOverlayToggleButton(false);
        } else if (src == overlayVisionRef) {
            visionBypassEnabled = overlayVisionRef.isSelected();
        } else if (src == overlayZeroCooldownRef) {
            zeroCooldownEnabled = overlayZeroCooldownRef.isSelected();
            if (zeroCooldownEnabled && overlayMainFrameRef != null) GameAgentFeatures.setLastZeroCooldownStatus("快速开火已开启");
        } else if (src == overlayMeleeRef) {
            meleeAnimSpeedEnabled = overlayMeleeRef.isSelected();
        } else if (src == overlayInteractDistRef) {
            interactDistanceEnabled = overlayInteractDistRef.isSelected();
        } else if (src == overlayFrontPickRef) {
            frontPickpocketEnabled = overlayFrontPickRef.isSelected();
        } else if (src == overlayNoRecoilRef) {
            noRecoilEnabled = overlayNoRecoilRef.isSelected();
        } else if (src == overlayPerfectPickRef) {
            perfectPickpocketEnabled = overlayPerfectPickRef.isSelected();
        } else if (src == overlayAlwaysSprintRef) {
            alwaysSprintEnabled = overlayAlwaysSprintRef.isSelected();
        } else if (src == overlayPerfectBlockRef) {
            perfectBlockEnabled = overlayPerfectBlockRef.isSelected();
        } else if (src == overlayAimbotRef) {
            aimbotEnabled = overlayAimbotRef.isSelected();
        } else if (src == overlayWireframeRef) {
            wireframeBoundsEnabled = overlayWireframeRef.isSelected();
            if (!wireframeBoundsEnabled) GameAgentOverlay.clearWireframeBoundsForDraw();
        } else if (src == overlayGoldXrayRef) {
            goldXrayEnabled = overlayGoldXrayRef.isSelected();
            if (!goldXrayEnabled) GameAgentOverlay.clearGoldItemsForDraw();
        } else if (src == overlaySkeletonRef) {
            skeletonBonesEnabled = overlaySkeletonRef.isSelected();
            if (!skeletonBonesEnabled) GameAgentOverlay.clearBoneSegmentsForDraw();
        } else if (src == overlayDrawTestCircleRef) {
            drawTestCircleEnabled = overlayDrawTestCircleRef.isSelected();
        }
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (e.getSource() == overlayTitleBarRef && overlayWindow != null) {
            Point loc = overlayWindow.getLocationOnScreen();
            overlayDragStart[0] = e.getLocationOnScreen().x - loc.x;
            overlayDragStart[1] = e.getLocationOnScreen().y - loc.y;
        }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (e.getSource() == overlayTitleBarRef && overlayWindow != null) {
            overlayWindow.setLocation(
                    e.getLocationOnScreen().x - overlayDragStart[0],
                    e.getLocationOnScreen().y - overlayDragStart[1]);
        }
    }

    @Override
    public void mouseMoved(MouseEvent e) {}

    @Override
    public void mouseClicked(MouseEvent e) {}

    @Override
    public void mouseReleased(MouseEvent e) {}

    @Override
    public void mouseEntered(MouseEvent e) {}

    @Override
    public void mouseExited(MouseEvent e) {}

    static void hideDrawOverlay() {
        GameAgentOverlay.hideDrawOverlay();
    }

    /** 隐藏 overlay（不 dispose），下次打开只 setVisible(true) 即可，保持开关状态 */
    static void hideOverlayAndUpdateDebug() {
        if (overlayWindow != null) {
            overlayWindow.setVisible(false);
        }
        lastOverlayDebugStatus = "[Overlay] 已隐藏";
    }

    /** 仅在主窗口关闭时调用：dispose 菜单 overlay 与绘制 overlay，下次注入会重新创建 */
    static void disposeOverlay() {
        if (overlayWindow != null) {
            overlayWindow.dispose();
            overlayWindow = null;
        }
        GameAgentOverlay.disposeDrawOverlay();
    }

    /**
     * 显示 overlay 并写入详细调试信息到 lastOverlayDebugStatus（主窗口定时刷新会显示）。
     */
    static void showOverlayAndUpdateDebug(Object mainFrame) {
        if (overlayWindow != null && !overlayWindow.isDisplayable()) overlayWindow = null;
        StringBuilder dbg = new StringBuilder();
        dbg.append("mainFrame=").append(mainFrame == null ? "null" : mainFrame.getClass().getName()).append("\n");
        Window gameWindow = null;
        if (mainFrame != null) {
            try {
                Field appField = mainFrame.getClass().getDeclaredField("app");
                appField.setAccessible(true);
                Object app = appField.get(mainFrame);
                dbg.append("mainFrame.app=").append(app == null ? "null" : app.getClass().getName()).append("\n");
                if (app instanceof Window) gameWindow = (Window) app;
            } catch (NoSuchFieldException e) {
                dbg.append("mainFrame.app 字段不存在: ").append(e.getMessage()).append("\n");
            } catch (Throwable t) {
                dbg.append("mainFrame.app 异常: ").append(t.getClass().getSimpleName()).append(" ").append(t.getMessage()).append("\n");
            }
        }
        dbg.append("gameWindow=").append(gameWindow == null ? "null" : gameWindow.getClass().getName()).append("\n");

        int w = 320;
        int h = 420;
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int x = (screen.width - w) / 2;
        int y = (screen.height - h) / 2;
        dbg.append("位置=屏幕正中央 ").append(x).append(",").append(y).append(" 屏幕=").append(screen.width).append("x").append(screen.height).append("\n");

        if (overlayWindow != null && overlayWindow.isDisplayable()) {
            overlayMainFrameRef = mainFrame;
            GameAgentOverlay.ensureDrawOverlayTimerStarted();
            overlayWindow.setVisible(true);
            overlayWindow.toFront();
            overlayWindow.requestFocus();
            syncOverlayButtonsFromFlags();
            dbg.append("Overlay 已存在，保持原位置显示\n");
            lastOverlayDebugStatus = dbg.toString();
            return;
        }
        try {
            overlayMainFrameRef = mainFrame;
            dbg.append("[1] new JWindow(null)\n");
            overlayWindow = new JWindow((Window) null);
            overlayWindow.setBounds(x, y, w, h);
            overlayWindow.setAlwaysOnTop(true);
            dbg.append("[2] setBounds+setAlwaysOnTop OK\n");

            dbg.append("[3] 创建 root+titleBar\n");
            JPanel root = new JPanel(new BorderLayout(0, 0));
            root.setOpaque(true);
            root.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(220, 220, 220), 2),
                    BorderFactory.createEmptyBorder(0, 0, 0, 0)));
            root.setBackground(new Color(50, 50, 50));

            // 可拖拽标题栏
            JPanel titleBar = new JPanel(new BorderLayout());
            titleBar.setOpaque(true);
            titleBar.setBackground(new Color(35, 35, 38));
            titleBar.setBorder(new EmptyBorder(6, 8, 6, 8));
            JLabel titleLabel = new JLabel("  欺杀旅社");
            titleLabel.setForeground(Color.WHITE);
            titleBar.add(titleLabel, BorderLayout.CENTER);
            JButton closeOverlay = new JButton("关闭");
            closeOverlay.setMargin(new Insets(2, 8, 2, 8));
            overlayCloseButtonRef = closeOverlay;
            closeOverlay.addActionListener(OVERLAY_LISTENER);
            titleBar.add(closeOverlay, BorderLayout.EAST);
            overlayTitleBarRef = titleBar;
            titleBar.addMouseListener(OVERLAY_LISTENER);
            titleBar.addMouseMotionListener(OVERLAY_LISTENER);
            root.add(titleBar, BorderLayout.NORTH);

            // 栏目：显示、自瞄、暴力、脚本、关于
            JTabbedPane tabs = new JTabbedPane();
            tabs.setOpaque(true);
            tabs.setBackground(new Color(50, 50, 50));
            tabs.setForeground(Color.WHITE);

            // 显示
            JPanel panelDisplay = new JPanel();
            panelDisplay.setLayout(new BoxLayout(panelDisplay, BoxLayout.Y_AXIS));
            panelDisplay.setOpaque(true);
            panelDisplay.setBackground(new Color(50, 50, 50));
            panelDisplay.setBorder(new EmptyBorder(4, 8, 4, 8));
            JPanel rowDisp1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowDisp1.setOpaque(false);
            JToggleButton btnVision = new JToggleButton("红外透视", false);
            btnVision.setToolTipText("INFRARED/PLAYER_VISION=1");
            overlayVisionRef = btnVision;
            btnVision.addActionListener(OVERLAY_LISTENER);
            rowDisp1.add(btnVision);
            panelDisplay.add(rowDisp1);
            JPanel rowDisp1b = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowDisp1b.setOpaque(false);
            JToggleButton btnGoldXray = new JToggleButton("金币透视", false);
            btnGoldXray.setToolTipText("根据碰撞体绘制金币/物品位置并显示距离");
            overlayGoldXrayRef = btnGoldXray;
            btnGoldXray.addActionListener(OVERLAY_LISTENER);
            rowDisp1b.add(btnGoldXray);
            panelDisplay.add(rowDisp1b);
            JPanel rowDisp2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowDisp2.setOpaque(false);
            JToggleButton btnWireframe = new JToggleButton("边框", false);
            btnWireframe.setToolTipText("绘制玩家包围盒（AABB）线框，依据 Rig 骨骼世界坐标取 min/max");
            overlayWireframeRef = btnWireframe;
            btnWireframe.addActionListener(OVERLAY_LISTENER);
            rowDisp2.add(btnWireframe);
            panelDisplay.add(rowDisp2);
            JPanel rowDisp3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowDisp3.setOpaque(false);
            JToggleButton btnSkeleton = new JToggleButton("骨骼", false);
            btnSkeleton.setToolTipText("绘制玩家完整骨架（Rig 父子骨骼线段）");
            overlaySkeletonRef = btnSkeleton;
            btnSkeleton.addActionListener(OVERLAY_LISTENER);
            rowDisp3.add(btnSkeleton);
            panelDisplay.add(rowDisp3);
            JPanel rowGoldDist = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowGoldDist.setOpaque(false);
            JLabel lblGoldDist = new JLabel("金币透视距离(米):");
            lblGoldDist.setForeground(Color.LIGHT_GRAY);
            rowGoldDist.add(lblGoldDist);
            JSlider sliderGoldDist = new JSlider(1, 1000, Math.max(1, Math.min(1000, goldXrayMaxDistance)));
            sliderGoldDist.setPreferredSize(new Dimension(120, 28));
            sliderGoldDist.setToolTipText("1~1000 游戏单位，超过不绘制金币");
            sliderGoldDist.addChangeListener(e -> goldXrayMaxDistance = Math.max(1, Math.min(1000, sliderGoldDist.getValue())));
            rowGoldDist.add(sliderGoldDist);
            JLabel lblGoldDistVal = new JLabel(String.valueOf(sliderGoldDist.getValue()));
            lblGoldDistVal.setForeground(Color.LIGHT_GRAY);
            lblGoldDistVal.setPreferredSize(new Dimension(28, 28));
            sliderGoldDist.addChangeListener(e -> lblGoldDistVal.setText(String.valueOf(sliderGoldDist.getValue())));
            rowGoldDist.add(lblGoldDistVal);
            panelDisplay.add(rowGoldDist);
            JPanel rowWireSkelDist = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowWireSkelDist.setOpaque(false);
            JLabel lblWireSkelDist = new JLabel("方框/骨骼透视距离(米):");
            lblWireSkelDist.setForeground(Color.LIGHT_GRAY);
            rowWireSkelDist.add(lblWireSkelDist);
            JSlider sliderWireSkelDist = new JSlider(1, 1000, Math.max(1, Math.min(1000, wireframeSkeletonMaxDistance)));
            sliderWireSkelDist.setPreferredSize(new Dimension(120, 28));
            sliderWireSkelDist.setToolTipText("1~1000 游戏单位，边框与骨骼共用");
            sliderWireSkelDist.addChangeListener(e -> wireframeSkeletonMaxDistance = Math.max(1, Math.min(1000, sliderWireSkelDist.getValue())));
            rowWireSkelDist.add(sliderWireSkelDist);
            JLabel lblWireSkelDistVal = new JLabel(String.valueOf(sliderWireSkelDist.getValue()));
            lblWireSkelDistVal.setForeground(Color.LIGHT_GRAY);
            lblWireSkelDistVal.setPreferredSize(new Dimension(28, 28));
            sliderWireSkelDist.addChangeListener(e -> lblWireSkelDistVal.setText(String.valueOf(sliderWireSkelDist.getValue())));
            rowWireSkelDist.add(lblWireSkelDistVal);
            panelDisplay.add(rowWireSkelDist);
            tabs.addTab("显示", panelDisplay);

            // 自瞄（瞄准点=HumanRig 所选骨骼）
            JPanel panelAimbot = new JPanel();
            panelAimbot.setLayout(new BoxLayout(panelAimbot, BoxLayout.Y_AXIS));
            panelAimbot.setOpaque(true);
            panelAimbot.setBackground(new Color(50, 50, 50));
            panelAimbot.setBorder(new EmptyBorder(4, 8, 4, 8));
            JPanel rowAimbot1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowAimbot1.setOpaque(false);
            JToggleButton btnAimbot = new JToggleButton("自瞄", false);
            btnAimbot.setToolTipText("按住右键时对准准星附近最近玩家的所选骨骼");
            overlayAimbotRef = btnAimbot;
            btnAimbot.addActionListener(OVERLAY_LISTENER);
            rowAimbot1.add(btnAimbot);
            panelAimbot.add(rowAimbot1);
            JPanel rowAimbot2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowAimbot2.setOpaque(false);
            JToggleButton btnTestCircle = new JToggleButton("自瞄范围", false);
            btnTestCircle.setToolTipText("屏幕中央画金色圆，半径=自瞄 FOV(°) 对应的视场，与自瞄范围一致");
            overlayDrawTestCircleRef = btnTestCircle;
            btnTestCircle.addActionListener(OVERLAY_LISTENER);
            rowAimbot2.add(btnTestCircle);
            panelAimbot.add(rowAimbot2);
            JPanel rowAimbot3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowAimbot3.setOpaque(false);
            JLabel lblBone = new JLabel("骨骼:");
            lblBone.setForeground(Color.LIGHT_GRAY);
            rowAimbot3.add(lblBone);
            JComboBox<String> comboBone = new JComboBox<>();
            for (String cn : GameAgentGameAccess.AIMBOT_BONE_CN_TO_FIELD.keySet()) comboBone.addItem(cn);
            comboBone.setToolTipText("固定：头/躯干/左眼/右眼；进游戏后有其他玩家时会发现全部骨骼");
            comboBone.setPreferredSize(new Dimension(100, 28));
            comboBone.addActionListener(e -> {
                Object sel = comboBone.getSelectedItem();
                if (sel != null) aimbotSelectedBoneField = GameAgentGameAccess.getBoneFieldFromDisplay(sel.toString());
            });
            comboBone.setSelectedItem(GameAgentGameAccess.getDisplayForBoneField(aimbotSelectedBoneField));
            overlayAimbotBoneRef = comboBone;
            rowAimbot3.add(comboBone);
            panelAimbot.add(rowAimbot3);
            JPanel rowAimbot4 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowAimbot4.setOpaque(false);
            JLabel lblFov = new JLabel("FOV(°):");
            lblFov.setForeground(Color.LIGHT_GRAY);
            rowAimbot4.add(lblFov);
            JSlider sliderFov = new JSlider(1, 20, aimbotFovDegrees);
            sliderFov.setPreferredSize(new Dimension(100, 28));
            sliderFov.setToolTipText("准星周围锥形半角(度)，范围 1~20");
            sliderFov.addChangeListener(e -> aimbotFovDegrees = sliderFov.getValue());
            overlayAimbotRangeSliderRef = sliderFov;
            rowAimbot4.add(sliderFov);
            JLabel lblFovVal = new JLabel(String.valueOf(aimbotFovDegrees));
            lblFovVal.setForeground(Color.LIGHT_GRAY);
            lblFovVal.setPreferredSize(new Dimension(22, 28));
            sliderFov.addChangeListener(e -> lblFovVal.setText(String.valueOf(sliderFov.getValue())));
            rowAimbot4.add(lblFovVal);
            panelAimbot.add(rowAimbot4);
            JPanel rowAimbot5 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowAimbot5.setOpaque(false);
            JLabel lblMaxDist = new JLabel("最大距离:");
            lblMaxDist.setForeground(Color.LIGHT_GRAY);
            rowAimbot5.add(lblMaxDist);
            int aimbotDistInit = aimbotMaxDistance <= 0 ? 0 : Math.max(1, Math.min(1000, (int) aimbotMaxDistance));
            JSlider sliderMaxDist = new JSlider(0, 1000, aimbotDistInit);
            sliderMaxDist.setPreferredSize(new Dimension(90, 28));
            sliderMaxDist.setToolTipText("0=不限制，1~1000 游戏单位");
            sliderMaxDist.addChangeListener(e -> aimbotMaxDistance = sliderMaxDist.getValue() <= 0 ? 0f : (float) sliderMaxDist.getValue());
            overlayAimbotMaxDistRef = sliderMaxDist;
            rowAimbot5.add(sliderMaxDist);
            JLabel lblMaxDistVal = new JLabel(String.valueOf(sliderMaxDist.getValue()));
            lblMaxDistVal.setForeground(Color.LIGHT_GRAY);
            lblMaxDistVal.setPreferredSize(new Dimension(28, 28));
            sliderMaxDist.addChangeListener(e -> lblMaxDistVal.setText(String.valueOf(sliderMaxDist.getValue())));
            rowAimbot5.add(lblMaxDistVal);
            panelAimbot.add(rowAimbot5);
            JPanel rowAimbot6 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowAimbot6.setOpaque(false);
            JLabel lblSmooth = new JLabel("平滑:");
            lblSmooth.setForeground(Color.LIGHT_GRAY);
            rowAimbot6.add(lblSmooth);
            JSlider sliderSmooth = new JSlider(0, 100, aimbotSmoothingPercent);
            sliderSmooth.setPreferredSize(new Dimension(80, 28));
            sliderSmooth.setToolTipText("0=瞬间对准，越大越平滑(GTFOHax 同款)");
            sliderSmooth.addChangeListener(e -> aimbotSmoothingPercent = sliderSmooth.getValue());
            overlayAimbotSmoothRef = sliderSmooth;
            rowAimbot6.add(sliderSmooth);
            JLabel lblSmoothVal = new JLabel(String.valueOf(aimbotSmoothingPercent));
            lblSmoothVal.setForeground(Color.LIGHT_GRAY);
            lblSmoothVal.setPreferredSize(new Dimension(28, 28));
            sliderSmooth.addChangeListener(e -> lblSmoothVal.setText(String.valueOf(sliderSmooth.getValue())));
            rowAimbot6.add(lblSmoothVal);
            panelAimbot.add(rowAimbot6);
            JPanel rowAimbot7 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowAimbot7.setOpaque(false);
            JToggleButton btnVisibleOnly = new JToggleButton("仅可见", aimbotVisibleOnly);
            btnVisibleOnly.setToolTipText("仅瞄准不被掩体遮挡的玩家（getVisibilityInfo.realtimeVisible）");
            btnVisibleOnly.addActionListener(e -> aimbotVisibleOnly = btnVisibleOnly.isSelected());
            overlayAimbotVisibleOnlyRef = btnVisibleOnly;
            rowAimbot7.add(btnVisibleOnly);
            panelAimbot.add(rowAimbot7);
            tabs.addTab("自瞄", panelAimbot);

            // 暴力：每行一个开关
            JPanel panelViolence = new JPanel();
            panelViolence.setLayout(new BoxLayout(panelViolence, BoxLayout.Y_AXIS));
            panelViolence.setOpaque(true);
            panelViolence.setBackground(new Color(50, 50, 50));
            panelViolence.setBorder(new EmptyBorder(4, 8, 4, 8));
            JPanel rowV1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowV1.setOpaque(false);
            JToggleButton btnZeroCooldown = new JToggleButton("快速开火", false);
            btnZeroCooldown.setToolTipText("仅对远程武器设 cooldown=0，近战不修改");
            overlayZeroCooldownRef = btnZeroCooldown;
            btnZeroCooldown.addActionListener(OVERLAY_LISTENER);
            rowV1.add(btnZeroCooldown);
            panelViolence.add(rowV1);
            JPanel rowV2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowV2.setOpaque(false);
            JToggleButton btnMelee = new JToggleButton("近战动作加速", false);
            overlayMeleeRef = btnMelee;
            btnMelee.addActionListener(OVERLAY_LISTENER);
            rowV2.add(btnMelee);
            panelViolence.add(rowV2);
            JPanel rowV3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowV3.setOpaque(false);
            JToggleButton btnInteractDist = new JToggleButton("放大交互距离", false);
            overlayInteractDistRef = btnInteractDist;
            btnInteractDist.addActionListener(OVERLAY_LISTENER);
            rowV3.add(btnInteractDist);
            panelViolence.add(rowV3);
            JPanel rowV4 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowV4.setOpaque(false);
            JToggleButton btnFrontPick = new JToggleButton("正面偷窃", false);
            overlayFrontPickRef = btnFrontPick;
            btnFrontPick.addActionListener(OVERLAY_LISTENER);
            rowV4.add(btnFrontPick);
            panelViolence.add(rowV4);
            JPanel rowV5 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowV5.setOpaque(false);
            JToggleButton btnNoRecoil = new JToggleButton("无后座", false);
            overlayNoRecoilRef = btnNoRecoil;
            btnNoRecoil.addActionListener(OVERLAY_LISTENER);
            rowV5.add(btnNoRecoil);
            panelViolence.add(rowV5);
            JPanel rowV6 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowV6.setOpaque(false);
            JToggleButton btnAlwaysSprint = new JToggleButton("无限体力", false);
            btnAlwaysSprint.setToolTipText("体力始终满（STAMINA=STAMINA_MAX）");
            overlayAlwaysSprintRef = btnAlwaysSprint;
            btnAlwaysSprint.addActionListener(OVERLAY_LISTENER);
            rowV6.add(btnAlwaysSprint);
            panelViolence.add(rowV6);
            tabs.addTab("暴力", panelViolence);

            // 脚本：每行一个控件
            JPanel panelScript = new JPanel();
            panelScript.setLayout(new BoxLayout(panelScript, BoxLayout.Y_AXIS));
            panelScript.setOpaque(true);
            panelScript.setBackground(new Color(50, 50, 50));
            panelScript.setBorder(new EmptyBorder(4, 8, 4, 8));
            JPanel rowS1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowS1.setOpaque(false);
            JToggleButton btnPerfectPick = new JToggleButton("完美偷窃", false);
            overlayPerfectPickRef = btnPerfectPick;
            btnPerfectPick.addActionListener(OVERLAY_LISTENER);
            rowS1.add(btnPerfectPick);
            panelScript.add(rowS1);
            JPanel rowS2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowS2.setOpaque(false);
            JToggleButton btnPerfectBlock = new JToggleButton("完美格挡", false);
            btnPerfectBlock.setToolTipText("格斗时有效格挡窗口内任意时机格挡都判为完美");
            overlayPerfectBlockRef = btnPerfectBlock;
            btnPerfectBlock.addActionListener(OVERLAY_LISTENER);
            rowS2.add(btnPerfectBlock);
            panelScript.add(rowS2);
            String[] itemTypeNames = getKIItemTypeNames();
            String[] itemTypeDisplays = new String[itemTypeNames.length];
            for (int i = 0; i < itemTypeNames.length; i++) itemTypeDisplays[i] = getDisplayForItemType(itemTypeNames[i]);
            JPanel rowS3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowS3.setOpaque(false);
            JLabel lblStealP1 = new JLabel("偷窃优先级 1:");
            lblStealP1.setForeground(Color.LIGHT_GRAY);
            rowS3.add(lblStealP1);
            JComboBox<String> comboP1 = new JComboBox<>(itemTypeDisplays);
            comboP1.setSelectedItem(getDisplayForItemType(stealPriority1));
            comboP1.addActionListener(e -> stealPriority1 = getItemTypeFromDisplay(comboP1.getSelectedItem() != null ? comboP1.getSelectedItem().toString() : ""));
            overlayStealPriority1Ref = comboP1;
            rowS3.add(comboP1);
            panelScript.add(rowS3);
            JPanel rowS4 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowS4.setOpaque(false);
            JLabel lblStealP2 = new JLabel("偷窃优先级 2:");
            lblStealP2.setForeground(Color.LIGHT_GRAY);
            rowS4.add(lblStealP2);
            JComboBox<String> comboP2 = new JComboBox<>(itemTypeDisplays);
            comboP2.setSelectedItem(getDisplayForItemType(stealPriority2));
            comboP2.addActionListener(e -> stealPriority2 = getItemTypeFromDisplay(comboP2.getSelectedItem() != null ? comboP2.getSelectedItem().toString() : ""));
            overlayStealPriority2Ref = comboP2;
            rowS4.add(comboP2);
            panelScript.add(rowS4);
            JPanel rowS5 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            rowS5.setOpaque(false);
            JLabel lblStealP3 = new JLabel("偷窃优先级 3:");
            lblStealP3.setForeground(Color.LIGHT_GRAY);
            rowS5.add(lblStealP3);
            JComboBox<String> comboP3 = new JComboBox<>(itemTypeDisplays);
            comboP3.setSelectedItem(getDisplayForItemType(stealPriority3));
            comboP3.addActionListener(e -> stealPriority3 = getItemTypeFromDisplay(comboP3.getSelectedItem() != null ? comboP3.getSelectedItem().toString() : ""));
            overlayStealPriority3Ref = comboP3;
            rowS5.add(comboP3);
            panelScript.add(rowS5);
            tabs.addTab("脚本", panelScript);

            // 同步 overlay 按钮状态与当前功能标志（避免 Insert 再打开时显示成“默认全关”）
            btnVision.setSelected(visionBypassEnabled);
            btnGoldXray.setSelected(goldXrayEnabled);
            btnZeroCooldown.setSelected(zeroCooldownEnabled);
            btnMelee.setSelected(meleeAnimSpeedEnabled);
            btnInteractDist.setSelected(interactDistanceEnabled);
            btnFrontPick.setSelected(frontPickpocketEnabled);
            btnNoRecoil.setSelected(noRecoilEnabled);
            btnPerfectPick.setSelected(perfectPickpocketEnabled);
            btnAlwaysSprint.setSelected(alwaysSprintEnabled);
            btnPerfectBlock.setSelected(perfectBlockEnabled);
            if (overlayStealPriority1Ref != null) overlayStealPriority1Ref.setSelectedItem(getDisplayForItemType(stealPriority1));
            if (overlayStealPriority2Ref != null) overlayStealPriority2Ref.setSelectedItem(getDisplayForItemType(stealPriority2));
            if (overlayStealPriority3Ref != null) overlayStealPriority3Ref.setSelectedItem(getDisplayForItemType(stealPriority3));
            if (btnAimbot != null) btnAimbot.setSelected(aimbotEnabled);

            // 关于
            JPanel panelAbout = new JPanel(new BorderLayout());
            panelAbout.setOpaque(true);
            panelAbout.setBackground(new Color(50, 50, 50));
            JTextArea aboutText = new JTextArea("由倾乐心工作室独家制作\n为您的游戏保驾护航");
            aboutText.setEditable(false);
            aboutText.setLineWrap(true);
            aboutText.setOpaque(true);
            aboutText.setBackground(new Color(50, 50, 50));
            aboutText.setForeground(Color.LIGHT_GRAY);
            aboutText.setBorder(new EmptyBorder(4, 8, 4, 8));
            panelAbout.add(aboutText, BorderLayout.CENTER);
            tabs.addTab("关于", panelAbout);

            // 用自定义 JLabel 做 tab 标题，显式设背景+字体色，未选中=浅底黑字、选中=深底白字
            Color tabSelBg = new Color(50, 50, 50);
            Color tabSelFg = Color.WHITE;
            Color tabUnselBg = new Color(220, 220, 220);
            Color tabUnselFg = Color.BLACK;
            String[] tabTitles = {"显示", "自瞄", "暴力", "脚本", "关于"};
            JLabel[] tabLabels = new JLabel[5];
            for (int i = 0; i < 5; i++) {
                JLabel lbl = new JLabel("  " + tabTitles[i] + "  ");
                lbl.setOpaque(true);
                tabLabels[i] = lbl;
                tabs.setTabComponentAt(i, lbl);
            }
            Runnable updateTabColors = () -> {
                int sel = tabs.getSelectedIndex();
                for (int i = 0; i < 5; i++) {
                    tabLabels[i].setBackground(i == sel ? tabSelBg : tabUnselBg);
                    tabLabels[i].setForeground(i == sel ? tabSelFg : tabUnselFg);
                }
            };
            tabs.addChangeListener(e -> updateTabColors.run());
            updateTabColors.run();

            root.add(tabs, BorderLayout.CENTER);
            tabs.setSelectedIndex(0);
            dbg.append("[4] tabs 已添加 5 个栏目\n");

            overlayWindow.getContentPane().setBackground(new Color(50, 50, 50));
            overlayWindow.getContentPane().add(root);
            dbg.append("[5] contentPane.add(root) OK\n");

            overlayWindow.validate();
            dbg.append("[6] validate() OK\n");

            GameAgentOverlay.ensureDrawOverlayTimerStarted();
            overlayWindow.setVisible(true);
            dbg.append("[7] setVisible(true) OK\n");
            overlayWindow.getRootPane().registerKeyboardAction(
                    e -> {
                        overlayEnabled = false;
                        hideOverlayAndUpdateDebug();
                        GameAgentUI.syncOverlayToggleButton(false);
                    },
                    KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, 0),
                    JComponent.WHEN_IN_FOCUSED_WINDOW);
            overlayWindow.toFront();
            dbg.append("[8] toFront() OK isVisible=").append(overlayWindow.isVisible()).append(" isDisplayable=").append(overlayWindow.isDisplayable()).append("\n");
            dbg.append("完成 尺寸=").append(w).append("x").append(h).append(" 位置=").append(overlayWindow.getLocationOnScreen()).append("\n");
        } catch (Throwable t) {
            dbg.append("异常: ").append(t.getClass().getName()).append(" ").append(t.getMessage()).append("\n");
            for (StackTraceElement ste : t.getStackTrace()) {
                if (ste.getClassName().startsWith("GameAgent")) {
                    dbg.append("  at ").append(ste.getClassName()).append(".").append(ste.getMethodName()).append(":").append(ste.getLineNumber()).append("\n");
                }
            }
            if (overlayWindow != null) {
                try {
                    overlayWindow.dispose();
                } catch (Throwable ignored) { }
                overlayWindow = null;
                overlayMainFrameRef = null;
            }
        }
        lastOverlayDebugStatus = dbg.toString();
    }

    /** 将当前功能标志同步到 overlay 内各开关（Overlay 已存在 toFront 时调用） */
    private static void syncOverlayButtonsFromFlags() {
        if (overlayVisionRef != null) overlayVisionRef.setSelected(visionBypassEnabled);
        if (overlayGoldXrayRef != null) overlayGoldXrayRef.setSelected(goldXrayEnabled);
        if (overlayZeroCooldownRef != null) overlayZeroCooldownRef.setSelected(zeroCooldownEnabled);
        if (overlayMeleeRef != null) overlayMeleeRef.setSelected(meleeAnimSpeedEnabled);
        if (overlayInteractDistRef != null) overlayInteractDistRef.setSelected(interactDistanceEnabled);
        if (overlayFrontPickRef != null) overlayFrontPickRef.setSelected(frontPickpocketEnabled);
        if (overlayNoRecoilRef != null) overlayNoRecoilRef.setSelected(noRecoilEnabled);
        if (overlayPerfectPickRef != null) overlayPerfectPickRef.setSelected(perfectPickpocketEnabled);
        if (overlayAlwaysSprintRef != null) overlayAlwaysSprintRef.setSelected(alwaysSprintEnabled);
        if (overlayPerfectBlockRef != null) overlayPerfectBlockRef.setSelected(perfectBlockEnabled);
        if (overlayAimbotRef != null) overlayAimbotRef.setSelected(aimbotEnabled);
        if (overlayAimbotRangeSliderRef != null) overlayAimbotRangeSliderRef.setValue(aimbotFovDegrees);
        if (overlayAimbotMaxDistRef != null) overlayAimbotMaxDistRef.setValue(aimbotMaxDistance <= 0 ? 0 : Math.max(1, Math.min(1000, (int) aimbotMaxDistance)));
        if (overlayAimbotSmoothRef != null) overlayAimbotSmoothRef.setValue(aimbotSmoothingPercent);
        if (overlayAimbotVisibleOnlyRef != null) overlayAimbotVisibleOnlyRef.setSelected(aimbotVisibleOnly);
        if (overlayWireframeRef != null) overlayWireframeRef.setSelected(wireframeBoundsEnabled);
        if (overlaySkeletonRef != null) overlaySkeletonRef.setSelected(skeletonBonesEnabled);
        if (overlayDrawTestCircleRef != null) overlayDrawTestCircleRef.setSelected(drawTestCircleEnabled);
        if (overlayStealPriority1Ref != null) overlayStealPriority1Ref.setSelectedItem(getDisplayForItemType(stealPriority1));
        if (overlayStealPriority2Ref != null) overlayStealPriority2Ref.setSelectedItem(getDisplayForItemType(stealPriority2));
        if (overlayStealPriority3Ref != null) overlayStealPriority3Ref.setSelectedItem(getDisplayForItemType(stealPriority3));
        if (overlayAimbotBoneRef != null) {
            JComboBox<String> c = overlayAimbotBoneRef;
            if (!aimbotBoneFieldOptions.isEmpty()) {
                java.util.Set<String> fixedFields = new java.util.HashSet<>(GameAgentGameAccess.AIMBOT_BONE_CN_TO_FIELD.values());
                c.removeAllItems();
                for (String cn : GameAgentGameAccess.AIMBOT_BONE_CN_TO_FIELD.keySet()) c.addItem(cn);
                for (String s : aimbotBoneFieldOptions) {
                    if (!fixedFields.contains(s)) c.addItem(s);
                }
            }
            String display = GameAgentGameAccess.getDisplayForBoneField(aimbotSelectedBoneField);
            if (display != null) {
                try { c.setSelectedItem(display); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * 自瞄：由 GameAgentFeatures.applyAimbot 实现；此处保留空壳仅作占位（tick 已直接调 Features）。
     */
    static void applyAimbotStub(Object mainFrame) {
    }

    /** KIItemType 枚举名 -> 中文显示，静态初始化。 */
    static {
        KI_ITEM_TYPE_TO_CN.put("NONE", "无");
        KI_ITEM_TYPE_TO_CN.put("GOLD", "金币");
        KI_ITEM_TYPE_TO_CN.put("RANGED", "远程");
        KI_ITEM_TYPE_TO_CN.put("MELEE", "近战");
        KI_ITEM_TYPE_TO_CN.put("CONSUMABLE", "消耗品");
        KI_ITEM_TYPE_TO_CN.put("MISC", "杂项");
        KI_ITEM_TYPE_TO_CN.put("UTILITY", "工具");
    }

    private static String getDisplayForItemType(String enumName) {
        return enumName == null ? "" : KI_ITEM_TYPE_TO_CN.getOrDefault(enumName, enumName);
    }

    private static String getItemTypeFromDisplay(String display) {
        if (display == null || display.isEmpty()) return "NONE";
        for (java.util.Map.Entry<String, String> e : KI_ITEM_TYPE_TO_CN.entrySet()) {
            if (e.getValue().equals(display)) return e.getKey();
        }
        return display;
    }

    /** 运行时取 KIItemData.KIItemType 枚举名列表，用于偷窃优先级下拉框；失败则返回常用几项。 */
    private static String[] getKIItemTypeNames() {
        try {
            Class<?> typeClass = Class.forName("KI.Game.General.Resource.KIItemData$KIItemType");
            Object[] constants = typeClass.getEnumConstants();
            if (constants != null && constants.length > 0) {
                String[] names = new String[constants.length];
                for (int i = 0; i < constants.length; i++) {
                    names[i] = ((Enum<?>) constants[i]).name();
                }
                return names;
            }
        } catch (Throwable ignored) { }
        return new String[]{"NONE", "GOLD", "RANGED", "MELEE", "CONSUMABLE", "MISC", "UTILITY"};
    }

    /** 发现 HumanRig 上所有带 boneName 的关节字段名，填入 aimbotBoneFieldOptions 并更新下拉框（先固定 头/躯干/左眼/右眼，再其他）。 */
    /** 供 GameAgentFeatures.applyAimbot 调用：发现 HumanRig 骨骼字段并更新 overlay 下拉框。 */
    static void discoverHumanRigBoneFields(Object humanRig) {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            for (java.lang.reflect.Field f : humanRig.getClass().getDeclaredFields()) {
                try {
                    if (f.getType().getDeclaredField("boneName") != null) {
                        names.add(f.getName());
                    }
                } catch (NoSuchFieldException ignored) { }
            }
            java.util.Collections.sort(names);
        } catch (Throwable ignored) { }
        if (names.isEmpty()) return;
        java.util.Set<String> fixedFields = new java.util.HashSet<>(GameAgentGameAccess.AIMBOT_BONE_CN_TO_FIELD.values());
        synchronized (aimbotBoneFieldOptions) {
            aimbotBoneFieldOptions.clear();
            aimbotBoneFieldOptions.addAll(names);
        }
        JComboBox<String> combo = overlayAimbotBoneRef;
        if (combo != null) {
            SwingUtilities.invokeLater(() -> {
                combo.removeAllItems();
                for (String cn : GameAgentGameAccess.AIMBOT_BONE_CN_TO_FIELD.keySet()) combo.addItem(cn);
                for (String s : aimbotBoneFieldOptions) {
                    if (!fixedFields.contains(s)) combo.addItem(s);
                }
                String display = GameAgentGameAccess.getDisplayForBoneField(aimbotSelectedBoneField);
                if (display != null) {
                    try { combo.setSelectedItem(display); } catch (Exception ignored) { }
                }
                if (combo.getSelectedItem() == null && combo.getItemCount() > 0) {
                    combo.setSelectedIndex(0);
                    // 仅修正下拉框显示，不覆盖用户已选部位（aimbotSelectedBoneField 仅由用户选择时更新）
                }
            });
        }
    }

    private static void updatePlayerInfo(Object mainFrame, JTextArea area, String headerText) {
        GameAgentUI.updatePlayerInfo(mainFrame, area, headerText);
    }

}
