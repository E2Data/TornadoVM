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
import java.util.StringTokenizer;

public class TestFlinkASM {

    private static class ClassAdapter extends ClassVisitor {
        public ClassAdapter(ClassVisitor cv) {
            super(Opcodes.ASM5, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
            if (name.contains("map") && desc.contains(skeletonMapDesc)) {
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
            udf = newLocal(Type.getType("L" + userClassName + ";"));
            // bytecodes to create -> FlinkMapUDF.Flink fudf = new FlinkMapUDF.Flink();
            mv.visitTypeInsn(Opcodes.NEW, userClassName);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, userClassName, "<init>", "()V", false);
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
            // load value stored in in[i]
            mv.visitInsn(GALOAD);
            // valueOf gets the primitive value in in[i] and returns the corresponding
            // object
            // (Integer, Double..), which will be passed as input in the Flink map function
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, inOwner, "valueOf", inDesc, false);
            // call Flink map function
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, userClassName, "map", descFunc);
            // (int|double|..)Value function transforms the output of the Flink function,
            // which is an object type, to the corresponding primitive
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, outOwner, outName, outDesc, false);
            // store output in out[i]
            mv.visitInsn(GASTORE);
            // this label is to mark the last instruction corresponding to the scope of
            // variable fudf
            mv.visitLabel(endLabel);
            // this is the call that actually creates fudf
            mv.visitLocalVariable("fudf", "L" + userClassName + ";", null, startLabel, endLabel, udf);
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

    // This variable will store the altered method and will be passed directly to
    // the addTask of the TornadoTaskSchedule class
    public static Method meth;
    // we should be able to get this by Flink
    public static String userClassName = "uk/ac/manchester/tornado/examples/FlinkMapUDF$Flink";
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

    public static void main(String[] args) throws IOException {

        // ASM work
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
        ClassAdapter adapter = new ClassAdapter(printer);
        reader.accept(adapter, ClassReader.EXPAND_FRAMES);
        byte[] b = writer.toByteArray();
        classLoader cl = new classLoader();
        String classname = "uk.ac.manchester.tornado.examples.MapSkeleton";
        Class clazz = cl.defineClass(classname, b);
        Method[] marr = clazz.getDeclaredMethods();
        // this works at the moment because we know that the class only contains one
        // method - the one we want to execute
        for (int i = 0; i < marr.length; i++) {
            if (marr[i].toString().contains(tornadoMapMethod)) {
                meth = marr[i];
            }
        }

        // -------------

        int[] in = new int[5];
        double[] out = new double[5];

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
