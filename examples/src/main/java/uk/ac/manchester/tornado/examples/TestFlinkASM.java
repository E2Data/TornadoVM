package uk.ac.manchester.tornado.examples;

import uk.ac.manchester.tornado.api.TaskSchedule;

import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;

public class TestFlinkASM {

    private static class ClassAdapter extends ClassVisitor {
        public ClassAdapter(ClassVisitor cv) {
            super(Opcodes.ASM5, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
            if (name.contains("map")) {
                return new VariableAdapter(access, desc, mv);
            } else {
                return mv;
            }
        }
    }

    private static class VariableAdapter extends LocalVariablesSorter {
        int udf;
        Label startLabel = new Label();
        Label endLabel = new Label();

        public VariableAdapter(int access, String desc, MethodVisitor mv) {
            super(Opcodes.ASM5, access, desc, mv);
        }

        /**
         * The method visitCode is called to add the opcodes for the fudf declaration.
         * The local variable fudf will store an instance of the Flink class. Since
         * Flink map is an instance method, the creation of this variable is necessary.
         */
        @Override
        public void visitCode() {
            // get the index for the new variable fudf
            udf = newLocal(Type.getType("Luk/ac/manchester/tornado/examples/FlinkMapUDF$Flink;"));
            // bytecodes to create -> FlinkMapUDF.Flink fudf = new FlinkMapUDF.Flink();
            mv.visitTypeInsn(Opcodes.NEW, "uk/ac/manchester/tornado/examples/FlinkMapUDF$Flink");
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "uk/ac/manchester/tornado/examples/FlinkMapUDF$Flink", "<init>", "()V", false);
            mv.visitVarInsn(Opcodes.ASTORE, udf);
            // this label is to mark the first instruction corresponding to the scope of
            // variable fudf
            mv.visitLabel(startLabel);
        }

        /**
         * The method visitIincInsn is called to add new instructions right before the
         * index incrementation of the for loop.
         */
        @Override
        public void visitIincInsn(int var, int increment) {
            // load array out
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            // load index i for the out array
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            // load fudf
            mv.visitVarInsn(Opcodes.ALOAD, udf);
            // load array in
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            // load index i for the in array
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            // load int stored in in[i]
            mv.visitInsn(Opcodes.IALOAD);
            // valueOf gets the int in in[i] and returns the corresponding Integer, which
            // will be
            // passed as input in the Flink map function
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
            // call Flink map function
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "uk/ac/manchester/tornado/examples/FlinkMapUDF$Flink", "map", "(Ljava/lang/Integer;)Ljava/lang/Integer;");
            // intValue transforms the output of the Flink function, which is an Integer, to
            // int
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
            // store output in out[i]
            mv.visitInsn(Opcodes.IASTORE);
            // this label is to mark the last instruction corresponding to the scope of
            // variable fudf
            mv.visitLabel(endLabel);
            // this is the call that actually creates fudf
            mv.visitLocalVariable("fudf", "Luk/ac/manchester/tornado/examples/FlinkMapUDF$Flink;", null, startLabel, endLabel, udf);
            super.visitIincInsn(var, increment);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // maxStack is computed automatically due to the COMPUTE_MAXS argument in the
            // ClassWriter
            // maxLocals is incremented by one because we have created on new local
            // variable, fudf
            super.visitMaxs(maxStack, maxLocals + 1);
        }

    }

    // This variable will store the altered method and will be passed directly to
    // the addTask of the TornadoTaskSchedule class
    public static Method meth;

    public static void main(String[] args) throws IOException {

        // ASM work
        ClassReader reader = new ClassReader("uk.ac.manchester.tornado.examples.MapSkeleton");
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        TraceClassVisitor printer = new TraceClassVisitor(writer, new PrintWriter(System.out));
        ClassAdapter adapter = new ClassAdapter(printer);
        reader.accept(adapter, ClassReader.EXPAND_FRAMES);
        byte[] b = writer.toByteArray();
        classLoader cl = new classLoader();
        String classname = "uk.ac.manchester.tornado.examples.MapSkeleton";
        Class clazz = cl.defineClass(classname, b);
        Method[] marr = clazz.getDeclaredMethods();
        // this works at the moment because we know that the class only contains one
        // method - the one we want to execute
        meth = marr[0];
        // -------------

        int[] in = new int[5];
        int[] out = new int[5];

        for (int i = 0; i < in.length; i++) {
            in[i] = i;
        }

        TaskSchedule tt = new TaskSchedule("st").task("t0", MapSkeleton::map, in, out).streamOut(out);
        tt.execute();

        for (int i = 0; i < out.length; i++) {
            System.out.println("out[" + i + "] = " + out[i]);
        }
    }
}

class classLoader extends ClassLoader {
    public Class defineClass(String name, byte[] b) {
        return defineClass(name, b, 0, b.length);
    }
}
