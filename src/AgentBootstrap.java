import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Agent 入口（Agent-Class），由 JVM 最先加载。实现 ClassFileTransformer 以便 retransform 时 JVM 无需再加载内部类。
 * 使用具名静态内部类并提前加载，避免 JVM 在游戏 ClassLoader 上下文中加载匿名类导致 NoClassDefFoundError。
 */
public class AgentBootstrap implements ClassFileTransformer {

    private static final String ERROR_FILE = ".gameagent-init-error.txt";

    static volatile String runLoopTargetInternalName;
    static volatile String runLoopHookStatus;

    static {
        try {
            ClassLoader cl = AgentBootstrap.class.getClassLoader();
            Class.forName("AgentBootstrap$RunLoopClassVisitor", true, cl);
            Class.forName("AgentBootstrap$RunLoopMethodVisitor", true, cl);
        } catch (Throwable ignored) { }
    }

    public static void agentmain(String args, Instrumentation inst) {
        try {
            Class<?> agentClass = Class.forName("GameAgent");
            agentClass.getMethod("agentmain", String.class, Instrumentation.class)
                    .invoke(null, args, inst);
        } catch (Throwable t) {
            String msg = formatThrowable(t);
            try {
                Path out = Paths.get(System.getProperty("user.home", ""), ERROR_FILE);
                Files.write(out, msg.getBytes(StandardCharsets.UTF_8));
                System.err.println("Agent 初始化异常已写入: " + out.toAbsolutePath());
            } catch (Throwable ignored) { }
            throw new RuntimeException("Agent 初始化失败", t);
        }
    }

    /** mainFrameInternalName 用于 runLoop hook。 */
    public static ClassFileTransformer getRunLoopTransformer(String mainFrameInternalName, String gpuCanvasInternalName) {
        runLoopTargetInternalName = mainFrameInternalName;
        runLoopHookStatus = null;
        return new AgentBootstrap();
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className,
                            Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (classBeingRedefined == null) return null;
        if (className.startsWith("GameAgent") || className.startsWith("org/objectweb/asm/")) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            if (runLoopTargetInternalName != null && className.equals(runLoopTargetInternalName)) {
                cr.accept(new RunLoopClassVisitor(cw), ClassReader.EXPAND_FRAMES);
                runLoopHookStatus = "runLoop hook 已安装";
                return cw.toByteArray();
            }
            return null;
        } catch (Throwable t) {
            runLoopHookStatus = "retransform 失败:\n" + formatThrowable(t);
            return null;
        }
    }

    /** 具名静态内部类，在 static 块中提前加载，避免 transform() 在游戏线程上下文中加载匿名类。 */
    private static final class RunLoopClassVisitor extends ClassVisitor {
        RunLoopClassVisitor(ClassWriter cw) {
            super(Opcodes.ASM9, cw);
        }
        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ("runLoop".equals(name) && "()V".equals(descriptor)) return new RunLoopMethodVisitor(mv);
            return mv;
        }
    }

    private static final class RunLoopMethodVisitor extends MethodVisitor {
        RunLoopMethodVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }
        private void emitReflectionCall(String methodName) {
            Label tryStart = new Label(), tryEnd = new Label(), catchStart = new Label(), afterCatch = new Label();
            visitTryCatchBlock(tryStart, tryEnd, catchStart, "java/lang/Throwable");
            visitLabel(tryStart);
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader", "()Ljava/lang/ClassLoader;", false);
            visitLdcInsn("GameAgent");
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ClassLoader", "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;", false);
            visitLdcInsn(methodName);
            visitInsn(Opcodes.ICONST_0);
            visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
            visitInsn(Opcodes.ACONST_NULL);
            visitInsn(Opcodes.ICONST_0);
            visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
            visitInsn(Opcodes.POP);
            visitLabel(tryEnd);
            visitJumpInsn(Opcodes.GOTO, afterCatch);
            visitLabel(catchStart);
            visitInsn(Opcodes.POP);
            visitLabel(afterCatch);
        }
        @Override
        public void visitCode() {
            super.visitCode();
            emitReflectionCall("onRunLoopEnter");
        }
        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.RETURN || opcode == Opcodes.ATHROW) {
                emitReflectionCall("onRunLoopExit");
            }
            super.visitInsn(opcode);
        }
    }

    static String formatThrowable(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        for (Throwable x = t; x != null; x = x.getCause()) {
            pw.println(x.getClass().getName() + ": " + x.getMessage());
            x.printStackTrace(pw);
            if (x.getCause() != null) pw.println("--- cause ---");
        }
        return sw.toString();
    }
}
