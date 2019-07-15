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
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Random;
import java.util.StringTokenizer;
import java.util.stream.IntStream;

public class TestFlinkASM {

    // This variable will store the altered method and will be passed directly to
    // the addTask of the TornadoTaskSchedule class
    public static Method meth = null;

    // we should be able to get this by Flink
    public static String userClassName = "uk/ac/manchester/tornado/examples/FlinkMapUDF$Flink";
    // we should be able to get this by Flink
    public static String redUserClassName = "uk/ac/manchester/tornado/examples/FlinkReduceUDF$FlinkReduce";
    // Type of function that user class contains, i.e. map, reduce etc...
    public static String functionType;
    // description of udf
    public static String descFunc;
    // (I | D | L | F)ALOAD
    public static int GALOAD;
    // (I | D | L | F)ASTORE
    public static int GASTORE;
    // descriptor that helps us call the correct function from the skeleton
    public static String skeletonMapDesc;
    // for valueOf
    public static String inOwner;
    public static String inDesc;
    // for (long | double | int | float)value
    public static String outOwner;
    public static String outName;
    public static String outDesc;
    // variable to help us call the appropriate tornado map method
    public static String tornadoMapMethod;

    // public static boolean flag = false;

    private static class MapClassAdapter extends ClassVisitor {
        public MapClassAdapter(ClassVisitor cv) {
            super(Opcodes.ASM5, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
            if (name.contains("map") && desc.contains(skeletonMapDesc)) {
                return new MapMethodAdapter(access, desc, mv);
            } else {
                return mv;
            }
        }
    }

    private static class MapMethodAdapter extends LocalVariablesSorter {
        int udf;
        Label startLabelMap = new Label();
        Label endLabelMap = new Label();

        public MapMethodAdapter(int access, String desc, MethodVisitor mv) {
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
            udf = newLocal(Type.getType("L" + userClassName + ";"));
            // bytecodes to create -> FlinkReduceUDF.Flink fudf = new
            // FlinkReduceUDF.Flink();
            mv.visitTypeInsn(Opcodes.NEW, userClassName);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, userClassName, "<init>", "()V", false);
            mv.visitVarInsn(Opcodes.ASTORE, udf);
            // this label is to mark the first instruction corresponding to the scope of
            // variable fudf
            mv.visitLabel(startLabelMap);
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
            mv.visitInsn(GALOAD);
            // valueOf gets the int in in[i] and returns the corresponding Integer, which
            // will be
            // passed as input in the Flink map function
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, inOwner, "valueOf", inDesc, false);
            // call Flink map function
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, userClassName, "map", descFunc);
            // intValue transforms the output of the Flink function, which is an Integer, to
            // int
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, outOwner, outName, outDesc, false);
            // store output in out[i]
            mv.visitInsn(GASTORE);
            // this label is to mark the last instruction corresponding to the scope of
            // variable fudf
            mv.visitLabel(endLabelMap);
            // this is the call that actually creates fudf
            mv.visitLocalVariable("fudf", "L" + userClassName + ";", null, startLabelMap, endLabelMap, udf);
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

    // Reduce Adaptors/Readers/Writers
    private static class ReduceClassAdapter extends ClassVisitor {
        public ReduceClassAdapter(ClassVisitor cv) {
            super(Opcodes.ASM5, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
            if (name.contains("reduce") && desc.contains("[D[D")) {
                return new ReduceMethodAdapter(access, desc, mv);
            } else {
                return mv;
            }
        }
    }

    private static class ReduceMethodAdapter extends LocalVariablesSorter {
        int udf;
        Label startLabelRed = new Label();
        Label endLabelRed = new Label();

        public ReduceMethodAdapter(int access, String desc, MethodVisitor mv) {
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
            udf = newLocal(Type.getType("L" + redUserClassName + ";"));
            // bytecodes to create -> FlinkMapUDF.Flink fudf = new FlinkMapUDF.Flink();
            mv.visitTypeInsn(Opcodes.NEW, redUserClassName);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, redUserClassName, "<init>", "()V", false);
            mv.visitVarInsn(Opcodes.ASTORE, udf);
            // this label is to mark the first instruction corresponding to the scope of
            // variable fudf
            mv.visitLabel(startLabelRed);
        }

        /**
         * The method visitIincInsn is called to add new instructions right before the
         * index incrementation of the for loop.
         */
        @Override
        public void visitIincInsn(int var, int increment) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.DUP2);
            mv.visitInsn(Opcodes.DALOAD);
            mv.visitVarInsn(Opcodes.ALOAD, udf);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitInsn(Opcodes.DALOAD);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.DALOAD);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "uk/ac/manchester/tornado/examples/FlinkReduceUDF$FlinkReduce", "reduce", "(Ljava/lang/Double;Ljava/lang/Double;)Ljava/lang/Double;");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false);
            mv.visitInsn(Opcodes.DADD);
            mv.visitInsn(Opcodes.DASTORE);
            super.visitIincInsn(var, increment);
        }

        @Override
        public void visitEnd() {
            mv.visitLabel(endLabelRed);
            mv.visitLocalVariable("fudf", "L" + redUserClassName + ";", null, startLabelRed, endLabelRed, udf);
            mv.visitEnd();
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
    // ===============================

    private static class FlinkClassVisitor extends ClassVisitor {
        public FlinkClassVisitor() {
            super(Opcodes.ASM5);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            if (exceptions == null) {
                functionType = name;
                descFunc = desc;
            }
            return super.visitMethod(access, name, desc, signature, exceptions);
        }
    }

    public static void setTypeVariablesMap() throws Exception {
        String delims = "()";
        StringTokenizer tok = new StringTokenizer(descFunc, delims);
        String argType;
        String returnType;
        String[] types = new String[2];
        int i = 0;
        while (tok.hasMoreElements()) {
            types[i] = (String) tok.nextElement();
            i++;
        }
        argType = types[0];
        returnType = types[1];
        // set everything related to the argument type
        if (argType.contains("Integer")) {
            GALOAD = Opcodes.IALOAD;
            inOwner = "java/lang/Integer";
            inDesc = "(I)Ljava/lang/Integer;";
            skeletonMapDesc = "[I";
            tornadoMapMethod = "int[],";
        } else if (argType.contains("Double")) {
            GALOAD = Opcodes.DALOAD;
            inOwner = "java/lang/Double";
            inDesc = "(D)Ljava/lang/Double;";
            skeletonMapDesc = "[D";
            tornadoMapMethod = "double[],";
        } else if (argType.contains("Long")) {
            GALOAD = Opcodes.LALOAD;
            inOwner = "java/lang/Long";
            inDesc = "(J)Ljava/lang/Long;";
            skeletonMapDesc = "[J";
            tornadoMapMethod = "long[],";
        } else if (argType.contains("Float")) {
            GALOAD = Opcodes.FALOAD;
            inOwner = "java/lang/Float";
            inDesc = "(F)Ljava/lang/Float;";
            skeletonMapDesc = "[F";
            tornadoMapMethod = "float[],";
        } else {
            throw new Exception("Argument type " + argType + " not implemented yet");
        }
        // set everything related to the return type
        if (returnType.contains("Integer")) {
            GASTORE = Opcodes.IASTORE;
            outOwner = "java/lang/Integer";
            outName = "intValue";
            outDesc = "()I";
            skeletonMapDesc = skeletonMapDesc + "[I";
            tornadoMapMethod = tornadoMapMethod + "int[]";
        } else if (returnType.contains("Double")) {
            GASTORE = Opcodes.DASTORE;
            outOwner = "java/lang/Double";
            outName = "doubleValue";
            outDesc = "()D";
            skeletonMapDesc = skeletonMapDesc + "[D";
            tornadoMapMethod = tornadoMapMethod + "double[]";
        } else if (returnType.contains("Long")) {
            GASTORE = Opcodes.LASTORE;
            outOwner = "java/lang/Long";
            outName = "longValue";
            outDesc = "()J";
            skeletonMapDesc = skeletonMapDesc + "[J";
            tornadoMapMethod = tornadoMapMethod + "long[]";
        } else if (returnType.contains("Float")) {
            GASTORE = Opcodes.FASTORE;
            outOwner = "java/lang/Float";
            outName = "floatValue";
            outDesc = "()F";
            skeletonMapDesc = skeletonMapDesc + "[F";
            tornadoMapMethod = tornadoMapMethod + "float[]";
        } else {
            throw new Exception("Return type " + returnType + " not implemented yet");
        }
    }

    public static byte b2[];

    public static void main(String[] args) throws IOException {

        // ASM work for map
        FlinkClassVisitor flinkVisit = new FlinkClassVisitor();
        ClassReader flinkClassReader = new ClassReader("uk.ac.manchester.tornado.examples.FlinkMapUDF$Flink");
        flinkClassReader.accept(flinkVisit, 0);
        try {
            setTypeVariablesMap();
        } catch (Exception e) {
            e.printStackTrace();
        }
        ClassReader reader = new ClassReader("uk.ac.manchester.tornado.examples.MapSkeleton");
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        TraceClassVisitor printer = new TraceClassVisitor(writer, new PrintWriter(System.out));
        MapClassAdapter adapter = new MapClassAdapter(writer);
        reader.accept(adapter, ClassReader.EXPAND_FRAMES);
        byte[] b = writer.toByteArray();
        classLoader cl = new classLoader();
        String classname = "uk.ac.manchester.tornado.examples.MapSkeleton";
        Class clazz = cl.defineClass(classname, b);

        Method[] marr = clazz.getDeclaredMethods();

        for (int i = 0; i < marr.length; i++) {
            if (marr[i].toString().contains(tornadoMapMethod)) {
                meth = marr[i];
            }
        }

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
        meth = null;

        // ASM work for reduce
        ClassReader readerRed = new ClassReader("uk.ac.manchester.tornado.examples.ReduceSkeleton");
        ClassWriter writerRed = new ClassWriter(readerRed, ClassWriter.COMPUTE_MAXS);
        TraceClassVisitor printerRed = new TraceClassVisitor(writerRed, new PrintWriter(System.out));
        // to remove debugging info, just replace the printer in class adapter call with
        // the writer
        ReduceClassAdapter adapterRed = new ReduceClassAdapter(writerRed);
        readerRed.accept(adapterRed, ClassReader.EXPAND_FRAMES);

        byte[] b2 = writerRed.toByteArray();
        Class clazz2 = cl.defineClass("uk.ac.manchester.tornado.examples.ReduceSkeleton", b2);
        Method[] rarr = clazz2.getDeclaredMethods();
        meth = rarr[0];

        double[] input = new double[32];
        double[] result = new double[1];

        Random rand = new Random();
        IntStream.range(0, 32).parallel().forEach(i -> {
            input[i] = rand.nextDouble();
        });

        for (int i = 0; i < input.length; i++) {
            System.out.print(input[i] + "+");
        }

        TaskSchedule task = new TaskSchedule("s0").task("t0", ReduceSkeleton::reduce, input, result).streamOut(result);
        task.execute();
        System.out.println("\nresult= " + result[0]);

    }

}

class classLoader<T> extends ClassLoader {

    public classLoader() {
        super();
    }

    public Class<T> defineClass(String name, byte[] b) {
        return (Class<T>) defineClass(name, b, 0, b.length);
    }
}
