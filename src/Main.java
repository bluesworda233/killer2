import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.List;
import java.util.Scanner;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * 挂载器：云卡密验证通过后自动查找 Main.App 并注入内嵌 GameAgent，无二次输入。
 * JDK8 需将 tools.jar 加入 classpath。
 */
public class Main {

    private static final String TARGET_DISPLAY_NAME = "Main.App";

    /** 云卡密 API（与文档一致） */
    private static final String CARD_API_BASE = "http://8.152.222.8";
    private static final String CARD_API_VERIFY = CARD_API_BASE + "/api/verify.php";
    private static final String CARD_API_KEY = "dd65dc0f291bed86c7ab588f1fbeb6f3";
    private static final String CARD_SAVE_FILE = ".killer2_card_key";
    private static final String DEVICE_ID_FILE = ".killer2_device_id";

    /** 简单设备码：优先读已保存的，否则用 用户名+主机名+user.home 哈希 生成并保存。 */
    private static String getDeviceId() {
        Path file = Paths.get(System.getProperty("user.home"), DEVICE_ID_FILE);
        try {
            if (Files.isRegularFile(file)) {
                String saved = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
                if (!saved.isEmpty()) return saved;
            }
            String host = "localhost";
            try {
                host = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception ignored) { }
            String user = System.getProperty("user.name", "");
            String home = System.getProperty("user.home", "");
            long hash = (host + user + home).hashCode() & 0xFFFFFFFFL;
            String deviceId = host + "-" + user + "-" + Long.toHexString(hash);
            Files.write(file, deviceId.getBytes(StandardCharsets.UTF_8));
            return deviceId;
        } catch (Exception e) {
            return "device-" + (System.getProperty("user.name", "unknown").hashCode() & 0xFFFFFFFFL);
        }
    }

    private static String getSavedCardKey() {
        Path file = Paths.get(System.getProperty("user.home"), CARD_SAVE_FILE);
        try {
            if (Files.isRegularFile(file)) {
                String s = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) return s;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static void saveCardKey(String cardKey) {
        try {
            Path file = Paths.get(System.getProperty("user.home"), CARD_SAVE_FILE);
            Files.write(file, cardKey.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    /** 调用云卡密 API 验证，成功返回 true。使用 GET + X-API-KEY 头。 */
    private static boolean verifyCard(String cardKey, String deviceId) {
        if (cardKey == null || cardKey.trim().isEmpty()) return false;
        try {
            String q = "api_key=" + URLEncoder.encode(CARD_API_KEY, "UTF-8")
                    + "&card_key=" + URLEncoder.encode(cardKey.trim(), "UTF-8")
                    + "&device_id=" + URLEncoder.encode(deviceId, "UTF-8");
            URL url = new URL(CARD_API_VERIFY + "?" + q);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-API-KEY", CARD_API_KEY);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            String body = readResponse(conn);
            conn.disconnect();
            // 成功：code 为 0；HTTP 200
            if (code != 200) return false;
            String trimmed = body.replaceAll("\\s+", "");
            return trimmed.contains("\"code\":0") || trimmed.contains("\"code\":0,");
        } catch (Exception e) {
            return false;
        }
    }

    private static String readResponse(HttpURLConnection conn) {
        try {
            InputStream in = conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return "";
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean ensureCardVerified(Scanner sc) {
        String deviceId = getDeviceId();
        String cardKey = getSavedCardKey();
        if (cardKey != null && verifyCard(cardKey, deviceId)) return true;
        for (;;) {
            System.out.print("请输入卡密: ");
            String input = sc.nextLine();
            if (input == null) return false;
            cardKey = input.trim();
            if (cardKey.isEmpty()) continue;
            if (verifyCard(cardKey, deviceId)) {
                saveCardKey(cardKey);
                return true;
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("欢迎使用倾乐心工作室独立研发的欺杀旅社科技，独家暴力功能，拒绝一切拉闸。倾心乐，为您的游戏保驾护航。");
        try (Scanner sc = new Scanner(System.in)) {
            if (!ensureCardVerified(sc)) {
                System.out.println("验证未通过，退出。");
                return;
            }
            List<VirtualMachineDescriptor> list = VirtualMachine.list();
            VirtualMachineDescriptor mainApp = findMainApp(list);
            if (mainApp == null) {
                System.out.println("未检测到目标进程，退出。");
                return;
            }
            attachWithGeneratedAgent(mainApp.id(), "GameAgent");
            System.out.println("完成。");
        } catch (Exception e) {
            System.err.println("失败。");
        }
    }

    private static VirtualMachineDescriptor findMainApp(List<VirtualMachineDescriptor> list) {
        for (VirtualMachineDescriptor d : list) {
            if (TARGET_DISPLAY_NAME.equals(d.displayName())) {
                return d;
            }
        }
        return null;
    }

    /** 运行时生成 agent JAR 并挂载。使用 AgentBootstrap 作为入口以便在目标 JVM 内捕获异常并写入文件。 */
    public static void attachWithGeneratedAgent(String pid, String agentClassName) throws Exception {
        String bootstrapName = "AgentBootstrap";
        String bootstrapResource = bootstrapName + ".class";
        byte[] bootstrapBytes;
        try (InputStream in = Main.class.getClassLoader().getResourceAsStream(bootstrapResource)) {
            if (in == null) throw new RuntimeException("找不到 " + bootstrapResource + "，请先编译项目（含 AgentBootstrap.java）");
            bootstrapBytes = in.readAllBytes();
        }
        String agentBasePath = agentClassName.replace('.', '/');
        String agentResource = agentBasePath + ".class";
        byte[] agentBytes;
        try (InputStream in = Main.class.getClassLoader().getResourceAsStream(agentResource)) {
            if (in == null) throw new RuntimeException("找不到 agent 类: " + agentResource);
            agentBytes = in.readAllBytes();
        }

        Path tempJar = Files.createTempFile("agent", ".jar");
        try {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(new Attributes.Name("Agent-Class"), bootstrapName);
            manifest.getMainAttributes().put(new Attributes.Name("Can-Retransform-Classes"), "true");

            Path libDir = resolveLibDir();
            Path jna = libDir.resolve("jna-5.18.1.jar");
            Path jnaPlatform = libDir.resolve("jna-platform-5.18.1.jar");
            Path asm = libDir.resolve("asm-9.9.1.jar");
            try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(tempJar), manifest)) {
                putClassEntry(jos, bootstrapResource, bootstrapBytes);
                putBootstrapInnerClasses(jos, Main.class.getClassLoader());
                putClassEntry(jos, agentResource, agentBytes);
                putAgentInnerClasses(jos, agentBasePath, Main.class.getClassLoader());
                putAgentSupportClasses(jos, Main.class.getClassLoader());
                mergeJarInto(jos, jna);
                mergeJarInto(jos, jnaPlatform);
                mergeJarInto(jos, asm);
            }
            String jarPath = tempJar.toAbsolutePath().toString();
            VirtualMachine vm = VirtualMachine.attach(pid);
            vm.loadAgent(jarPath);
            vm.detach();
        } finally {
            try {
                Files.deleteIfExists(tempJar);
            } catch (Exception ignored) {
                // Windows 上目标 JVM 会占用该 JAR，无法立即删除，忽略即可
            }
        }
    }

    /** 先 user.dir/lib，再沿 Main 所在目录向上找 lib（IDEA 运行时工作目录可能不是项目根）。 */
    private static Path resolveLibDir() {
        Path fromUserDir = Paths.get(System.getProperty("user.dir"), "lib");
        if (Files.isDirectory(fromUserDir)) return fromUserDir;
        try {
            java.net.URI codeSource = Main.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path base = Paths.get(codeSource).getParent();
            for (int i = 0; i < 5 && base != null; i++) {
                Path lib = base.resolve("lib");
                if (Files.isDirectory(lib)) return lib;
                base = base.getParent();
            }
        } catch (Exception ignored) { }
        return fromUserDir;
    }

    private static void putClassEntry(JarOutputStream jos, String entryName, byte[] bytes) throws Exception {
        JarEntry entry = new JarEntry(entryName);
        entry.setSize(bytes.length);
        jos.putNextEntry(entry);
        jos.write(bytes);
        jos.closeEntry();
    }

    /** 将 AgentBootstrap 的静态内部类打入 jar，避免 retransform 时 NoClassDefFoundError: AgentBootstrap$RunLoopClassVisitor 等 */
    private static void putBootstrapInnerClasses(JarOutputStream jos, ClassLoader loader) {
        for (String inner : new String[]{"AgentBootstrap$RunLoopClassVisitor", "AgentBootstrap$RunLoopMethodVisitor"}) {
            String resource = inner + ".class";
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in != null) putClassEntry(jos, resource, in.readAllBytes());
            } catch (Exception ignored) { }
        }
    }

    /** 将 Agent 主类及其内部类 GameAgent$1, GameAgent$2, ... 打入 jar。 */
    private static void putAgentInnerClasses(JarOutputStream jos, String agentBasePath, ClassLoader loader) {
        for (int i = 1; i <= 99; i++) {
            String innerResource = agentBasePath + "$" + i + ".class";
            try (InputStream in = loader.getResourceAsStream(innerResource)) {
                if (in == null) continue;
                byte[] bytes = in.readAllBytes();
                putClassEntry(jos, innerResource, bytes);
            } catch (Exception ignored) { }
        }
    }

    /** 将拆分出的 Agent 支撑类及其内部类（lambda 等）打入 jar。 */
    private static void putAgentSupportClasses(JarOutputStream jos, ClassLoader loader) {
        for (String name : new String[]{"GameAgentGameAccess", "GameAgentFeatures", "GameAgentOverlay", "GameAgentUI"}) {
            String resource = name + ".class";
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in != null) putClassEntry(jos, resource, in.readAllBytes());
            } catch (Exception ignored) { }
            for (int i = 1; i <= 99; i++) {
                String innerResource = name + "$" + i + ".class";
                try (InputStream in = loader.getResourceAsStream(innerResource)) {
                    if (in != null) putClassEntry(jos, innerResource, in.readAllBytes());
                } catch (Exception ignored) { }
            }
        }
    }

    /** 合并指定 JAR 到当前 JAR（用 JarFile 逐条复制，避免 JarInputStream 漏读）。 */
    private static void mergeJarInto(JarOutputStream jos, Path jarPath) {
        if (!Files.isRegularFile(jarPath)) return;
        try (JarFile jf = new JarFile(jarPath.toFile())) {
            byte[] buf = new byte[8192];
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry je = entries.nextElement();
                if (je.isDirectory() || je.getName().startsWith("META-INF/")) continue;
                try (InputStream in = jf.getInputStream(je)) {
                    JarEntry entry = new JarEntry(je.getName());
                    jos.putNextEntry(entry);
                    int n;
                    while ((n = in.read(buf)) > 0) jos.write(buf, 0, n);
                    jos.closeEntry();
                }
            }
        } catch (Exception ignored) { }
    }

    private static boolean agentJarContains(String jarPath, String entryName) {
        try (JarFile jf = new JarFile(jarPath)) {
            return jf.getEntry(entryName) != null;
        } catch (Exception e) {
            return false;
        }
    }
}
