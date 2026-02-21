import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

/**
 * DEBUG 窗口、菜单 overlay 入口与 Insert 热键（JNA）。由 GameAgent 调用 showTestWindow、startInsertHotkeyJna。
 */
public final class GameAgentUI {

    private static final int POLL_MS = 1500;
    private static final int VK_INSERT = 0x2D;
    private static final int WM_HOTKEY = 0x0312;
    private static final int WM_DESTROY = 0x0002;
    private static final int ID_HOTKEY_INSERT = 1;
    private static final int WS_EX_LAYERED = 0x00080000;
    private static final int WS_EX_TRANSPARENT = 0x00000020;
    private static final int LWA_COLORKEY = 1;

    private static Thread insertHotkeyThread;
    private static WinUser.WindowProc insertWndProcRef;

    /** DEBUG 窗口与主帧、overlay 开关按钮引用，由 showTestWindow 设置，供 GameAgent 通过 getter/sync 使用。 */
    private static JFrame singleWindow;
    private static Object mainFrameForShortcut;
    private static JToggleButton overlayToggleButtonRef;

    private GameAgentUI() { }

    public static JFrame getSingleWindow() { return singleWindow; }
    public static Object getMainFrameForShortcut() { return mainFrameForShortcut; }
    /** 主窗口关闭时由 GameAgent.resetOnDebugWindowClosed 调用，清空窗口与主帧引用。 */
    public static void clearDebugWindowState() {
        singleWindow = null;
        mainFrameForShortcut = null;
    }
    /** Insert 热键或菜单关闭时由 GameAgent 调用，同步「打开菜单」按钮的选中与文案。 */
    public static void syncOverlayToggleButton(boolean selected) {
        if (overlayToggleButtonRef != null) {
            overlayToggleButtonRef.setSelected(selected);
            overlayToggleButtonRef.setText(selected ? "菜单: 开" : "打开菜单");
        }
    }

    /** 使用 JNA 注册 Windows 全局热键 Insert。由 GameAgent.agentmain 调用。 */
    public static void startInsertHotkeyJna() {
        if (insertHotkeyThread != null && insertHotkeyThread.isAlive()) {
            GameAgent.setLastInsertHookStatus("Insert 热键 (JNA) 已在运行");
            return;
        }
        insertHotkeyThread = new Thread(() -> {
            try {
                User32 user32 = User32.INSTANCE;
                Kernel32 kernel32 = Kernel32.INSTANCE;
                WinDef.HINSTANCE hInst = kernel32.GetModuleHandle(null);
                String className = "GameAgentInsertHotkey";

                insertWndProcRef = (hwnd, uMsg, wParam, lParam) -> {
                    int wp = (wParam instanceof Number) ? ((Number) wParam).intValue() : 0;
                    if (uMsg == WM_HOTKEY && wp == ID_HOTKEY_INSERT) {
                        onInsertPressedFromHook();
                        return new WinDef.LRESULT(0);
                    }
                    if (uMsg == WM_DESTROY) {
                        user32.PostQuitMessage(0);
                        return new WinDef.LRESULT(0);
                    }
                    return user32.DefWindowProc(hwnd, uMsg, wParam, lParam);
                };

                WinUser.WNDCLASSEX wcex = new WinUser.WNDCLASSEX();
                wcex.cbSize = wcex.size();
                wcex.hInstance = hInst;
                wcex.lpfnWndProc = insertWndProcRef;
                wcex.lpszClassName = className;
                WinDef.ATOM atom = user32.RegisterClassEx(wcex);
                if (atom == null || atom.intValue() == 0) {
                    GameAgent.setLastInsertHookStatus("Insert 热键 (JNA): RegisterClassEx 失败");
                    return;
                }

                WinDef.HWND hwnd = user32.CreateWindowEx(
                        WS_EX_LAYERED | WS_EX_TRANSPARENT, className, "", 0, 0, 0, 0, 0,
                        null, null, hInst, null);
                if (hwnd == null) {
                    GameAgent.setLastInsertHookStatus("Insert 热键 (JNA): CreateWindowEx 失败");
                    return;
                }
                user32.SetLayeredWindowAttributes(hwnd, 0, (byte) 0, LWA_COLORKEY);
                user32.ShowWindow(hwnd, 0);

                if (!user32.RegisterHotKey(hwnd, ID_HOTKEY_INSERT, 0, VK_INSERT)) {
                    GameAgent.setLastInsertHookStatus("Insert 热键 (JNA): RegisterHotKey 失败，可能已被占用");
                    user32.DestroyWindow(hwnd);
                    return;
                }

                GameAgent.setLastInsertHookStatus("Insert 热键 (JNA) 已启动，按 Insert 切换菜单");
                WinUser.MSG msg = new WinUser.MSG();
                while (user32.GetMessage(msg, null, 0, 0) != 0) {
                    user32.TranslateMessage(msg);
                    user32.DispatchMessage(msg);
                }
                user32.UnregisterHotKey(hwnd.getPointer(), ID_HOTKEY_INSERT);
                user32.DestroyWindow(hwnd);
            } catch (Throwable t) {
                GameAgent.setLastInsertHookStatus("Insert 热键 (JNA) 启动失败: " + t.getClass().getSimpleName() + " " + t.getMessage()
                        + "\n请将 jna.jar、jna-platform.jar 放入 lib，Main 会打入 Agent JAR。");
            }
        }, "InsertHotkey-JNA");
        insertHotkeyThread.setDaemon(true);
        insertHotkeyThread.start();
    }

    /** Insert 按下时由 JNA 热键线程调用，切到 EDT 切换菜单。 */
    public static void onInsertPressedFromHook() {
        GameAgent.setLastInsertHookStatus("Insert 已触发 " + System.currentTimeMillis() + " (JNA 热键)");
        SwingUtilities.invokeLater(GameAgent::toggleOverlayFromInsert);
    }

    /** 显示或前置 DEBUG 主窗口；overlay/快捷键/关闭重置通过 GameAgent 与本类 sync 方法。 */
    public static void showTestWindow(String headerText, Object mainFrame) {
        if (singleWindow != null && singleWindow.isDisplayable()) {
            singleWindow.toFront();
            singleWindow.requestFocus();
            return;
        }
        mainFrameForShortcut = mainFrame;
        JFrame f = new JFrame("DEBUG");
        singleWindow = f;
        f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.setPreferredSize(new Dimension(580, 400));

        JTextArea area = new JTextArea(headerText + "\n");
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setLineWrap(true);
        panel.add(new JScrollPane(area), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        JToggleButton btnOverlay = new JToggleButton("打开菜单", false);
        btnOverlay.setToolTipText("打开功能面板（可拖拽），所有功能在菜单内分栏显示");
        btnOverlay.addActionListener(e -> {
            GameAgent.setOverlayEnabled(btnOverlay.isSelected());
            if (GameAgent.getOverlayEnabled()) {
                GameAgent.showOverlayAndUpdateDebug(mainFrame);
            } else {
                GameAgent.hideOverlayAndUpdateDebug();
            }
            btnOverlay.setText(GameAgent.getOverlayEnabled() ? "菜单: 开" : "打开菜单");
        });
        if (mainFrame == null) btnOverlay.setEnabled(false);
        buttons.add(btnOverlay);
        overlayToggleButtonRef = btnOverlay;
        JButton close = new JButton("关闭");
        close.addActionListener(e -> f.dispose());
        buttons.add(close);
        panel.add(buttons, BorderLayout.SOUTH);

        f.add(panel);
        f.getRootPane().registerKeyboardAction(
                e -> {
                    if (mainFrameForShortcut == null) return;
                    GameAgent.toggleOverlayFromInsert();
                },
                KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        f.pack();
        f.setLocationRelativeTo(null);
        f.setVisible(true);

        Timer timer = null;
        if (mainFrame != null) {
            timer = new Timer(POLL_MS, e -> updatePlayerInfo(mainFrame, area, headerText));
            timer.start();
        }
        Timer finalTimer = timer;
        f.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent ev) {
                GameAgent.resetOnDebugWindowClosed();
                if (finalTimer != null) finalTimer.stop();
            }
        });
    }

    /** 刷新主窗口内容区文案（从 GameAgent 读取 last* 与开关状态）；无透视时隐藏绘制 overlay。 */
    public static void updatePlayerInfo(Object mainFrame, JTextArea area, String headerText) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(headerText);
            if (GameAgent.getZeroCooldownEnabled()) {
                String s = GameAgent.getLastZeroCooldownStatus();
                if (s != null && !s.isEmpty()) sb.append("\n\n").append(s);
            }
            sb.append("\n\n--- 红外透视 ---\n");
            if (GameAgent.getVisionBypassEnabled()) {
                String s = GameAgent.getLastVisionDebugStatus();
                if (s != null && !s.isEmpty()) sb.append(s).append("\n");
            } else {
                sb.append("红外透视: ").append(GameAgent.getVisionBypassEnabled() ? "开启" : "未开启").append("\n");
            }
            if (GameAgent.getMeleeAnimSpeedEnabled()) {
                String s = GameAgent.getLastMeleeDebugStatus();
                if (s != null && !s.isEmpty()) sb.append("\n\n--- 调试 ---\n").append(s);
            }
            String lastOverlay = GameAgent.getLastOverlayDebugStatus();
            if (lastOverlay != null && !lastOverlay.isEmpty()) {
                sb.append("\n\n--- Overlay 调试 ---\n").append(lastOverlay);
            }
            String lastInsert = GameAgent.getLastInsertHookStatus();
            if (lastInsert != null && !lastInsert.isEmpty()) {
                sb.append("\n\n--- Insert 热键调试 ---\n").append(lastInsert);
            }
            String lastRun = GameAgent.getLastRunLoopHookStatus();
            if (lastRun != null && !lastRun.isEmpty()) {
                sb.append("\n\n--- 主循环 Hook ---\n").append(lastRun);
            }
            if (GameAgent.getAimbotEnabled()) {
                String s = GameAgent.getLastAimbotDebugStatus();
                if (s != null && !s.isEmpty()) sb.append("\n\n--- 自瞄调试 ---\n").append(s);
            }
            if (!GameAgent.getDrawTestCircleEnabled() && !GameAgent.getSkeletonBonesEnabled()
                    && !GameAgent.getWireframeBoundsEnabled() && !GameAgent.getGoldXrayEnabled()) {
                GameAgent.hideDrawOverlay();
            }
            if (GameAgent.getSkeletonBonesEnabled() || GameAgent.getDrawTestCircleEnabled()
                    || GameAgent.getWireframeBoundsEnabled() || GameAgent.getGoldXrayEnabled()) {
                sb.append("\n\n--- 边框/骨骼 ---\n");
                sb.append(GameAgent.getWireframeBoundsEnabled() ? "边框: 已开启\n" : "边框: 未开启\n");
                sb.append(GameAgent.getSkeletonBonesEnabled() ? "骨骼: 已开启\n" : "骨骼: 未开启\n");
                sb.append(GameAgent.getGoldXrayEnabled() ? "金币透视: 已开启\n" : "金币透视: 未开启\n");
                sb.append(GameAgent.getDrawTestCircleEnabled() ? "自瞄范围: 已开启\n" : "自瞄范围: 未开启\n");
                String drawDbg = GameAgent.getLastDrawOverlayDebug();
                if (drawDbg != null && !drawDbg.isEmpty()) sb.append("绘制调试: ").append(drawDbg).append("\n");
            }
            String newContent = sb.toString();
            if (!newContent.equals(area.getText())) {
                java.awt.Point scrollPos = null;
                java.awt.Component parent = area.getParent();
                if (parent instanceof javax.swing.JViewport) {
                    scrollPos = ((javax.swing.JViewport) parent).getViewPosition();
                }
                area.setText(newContent);
                if (scrollPos != null && parent instanceof javax.swing.JViewport) {
                    ((javax.swing.JViewport) parent).setViewPosition(scrollPos);
                }
            }
        } catch (Throwable t) {
            area.setText(headerText + "\n\n刷新异常: " + t.getMessage());
        }
    }
}
