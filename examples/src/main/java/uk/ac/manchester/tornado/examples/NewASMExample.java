package uk.ac.manchester.tornado.examples;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.LocalVariablesSorter;
import org.objectweb.asm.util.TraceClassVisitor;
import uk.ac.manchester.tornado.api.TaskSchedule;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.StringTokenizer;

public class NewASMExample {

    // we should be able to get this by Flink
    public static String mapUserClassName = "uk/ac/manchester/tornado/examples/FlinkMapUDF$Flink";
    // we should be able to get this by Flink
    public static String redUserClassName = "uk/ac/manchester/tornado/examples/FlinkReduceUDF$FlinkReduce";
    // Type of function that user class contains, i.e. map, reduce etc...
    public static String functionType;
    // description of udf
    public static String descFunc;
    // (I | D | L | F | A)LOAD
    public static int GLOAD;
    // (I | D | L | F | A)RETURN
    public static int GRETURN;
    // (I | D | L | F)CONST or ACONST_NULL
    public static int GCONST;
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

    // true if object class extends a class
    public static boolean extendsClass = false;
    // true if object class implements an interface
    public static boolean implementsInterface = false;
    // true if object class contains annotations
    public static boolean containsAnnotations = false;
    // stores <ObjectClass,objectInfo> pairs
    public static HashMap<String, ObjectHelper> objectInfo = new HashMap();

    // if map return type, if this type is an Object
    static String returnObjectClass;
    // if map argument type, if this type is an Object
    static String argObjectClass;
    // true if map return type is an Object
    static boolean returnObject = false;
    // true if map argument type is an Object
    static boolean argObject = false;

    static String typeClass;

    // Map Adaptors/Readers/Writers
    private static class MapClassAdapter extends ClassVisitor {
        public MapClassAdapter(ClassVisitor cv) {
            super(Opcodes.ASM5, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
            if (name.contains("map") && desc.contains(skeletonMapDesc)) {
                mv = new MapMethodAdapter(access, desc, mv);
                return new MapRemoveReturn(access, desc, mv);
            } else if (name.contains("init")) {
                return new MapConstructorAdapter(access, desc, mv);
            } else {
                return mv;
            }
        }
    }

    /**
     * Removes the return 0 from the MapASMSkeleton
     */

    private static class MapRemoveReturn extends LocalVariablesSorter {

        public MapRemoveReturn(int access, String desc, MethodVisitor mv) {
            super(Opcodes.ASM5, access, desc, mv);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == GCONST) {
                super.visitInsn(GRETURN);
            }

        }

    }

    private static class MapMethodAdapter extends LocalVariablesSorter {

        public MapMethodAdapter(int access, String desc, MethodVisitor mv) {
            super(Opcodes.ASM5, access, desc, mv);
        }

        @Override
        public void visitCode() {
            // if argument and return types are objects
            // TODO: Support cases where only one of the conditions is true
            if (argObject && returnObject) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, "uk/ac/manchester/tornado/examples/MapASMSkeleton", "udf", "L" + mapUserClassName + ";");
                mv.visitVarInsn(GLOAD, 1);
                mv.visitTypeInsn(Opcodes.CHECKCAST, typeClass);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, mapUserClassName, "map", descFunc, false);
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, "uk/ac/manchester/tornado/examples/MapASMSkeleton", "udf", "L" + mapUserClassName + ";");
                mv.visitVarInsn(GLOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, inOwner, "valueOf", inDesc, false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, mapUserClassName, "map", descFunc, false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, outOwner, outName, outDesc, false);
            }
            super.visitCode();
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {

            super.visitMaxs(maxStack, maxLocals + 1);
        }

    }

    private static class MapConstructorAdapter extends LocalVariablesSorter {

        public MapConstructorAdapter(int access, String desc, MethodVisitor mv) {
            super(Opcodes.ASM5, access, desc, mv);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            super.visitMethodInsn(opcode, owner, name, desc, itf);
            if (Opcodes.INVOKESPECIAL == opcode) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitTypeInsn(Opcodes.NEW, mapUserClassName);
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, mapUserClassName, "<init>", "()V", false);
                mv.visitFieldInsn(Opcodes.PUTFIELD, "uk/ac/manchester/tornado/examples/MapASMSkeleton", "udf", "L" + mapUserClassName + ";");
            }

        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(maxStack, maxLocals + 1);
        }

    }

    private static class FlinkClassVisitor extends ClassVisitor {
        public FlinkClassVisitor() {
            super(Opcodes.ASM5);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            if (exceptions == null && !(name.contains("<init>"))) {
                functionType = name;
                descFunc = desc;
            }
            return super.visitMethod(access, name, desc, signature, exceptions);
        }
    }

    private static class ObjectClassVisitor extends ClassVisitor {

        public static String objectName;
        public static ArrayList objectFields = new ArrayList();
        public static ArrayList objectMethods = new ArrayList();

        public ObjectClassVisitor() {
            super(Opcodes.ASM5);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            // if super class is not object then the class extends another class
            if (!superName.equals("java/lang/Object")) {
                extendsClass = true;
            }
            if (interfaces.length > 0) {
                implementsInterface = true;
            }
            if (!extendsClass && !implementsInterface) {
                objectName = name;
            }
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            // if object is not POJO
            if (objectName != null) {
                if (!name.contains("<init>")) {
                    objectMethods.add(name);
                }
            }
            return new objectMethods();
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
            // if object is not POJO
            if (objectName != null) {
                objectFields.add(desc);
            }
            return new FieldAnnotationScanner();
        }

        @Override
        public void visitEnd() {
            // if object is not POJO
            if (objectName != null) {
                ObjectHelper oh = new ObjectHelper(objectFields, objectMethods);
                objectInfo.put(objectName, oh);
            }
        }
    }

    static class FieldAnnotationScanner extends FieldVisitor {
        public FieldAnnotationScanner() {
            super(Opcodes.ASM5);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            containsAnnotations = true;
            return super.visitAnnotation(desc, visible);
        }
    }

    private static class objectMethods extends MethodVisitor {

        public objectMethods() {
            super(Opcodes.ASM5);
        }

        public AnnotationVisitor visitAnnotationDefault() {
            containsAnnotations = true;
            return super.visitAnnotationDefault();
        }

        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            System.out.println("annotation: " + desc + " isvisible: " + visible);
            containsAnnotations = true;
            return super.visitAnnotation(desc, visible);
        }

        public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
            System.out.println("parameter: " + parameter + " annotation: " + desc + " isvisible: " + visible);
            containsAnnotations = true;
            return visitParameterAnnotation(parameter, desc, visible);
        }

    }

    public static void setTypeVariablesMap() {
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
            GLOAD = Opcodes.ILOAD;
            inOwner = "java/lang/Integer";
            inDesc = "(I)Ljava/lang/Integer;";
            skeletonMapDesc = "(I)";
            tornadoMapMethod = "int[],";
        } else if (argType.contains("Double")) {
            GLOAD = Opcodes.DLOAD;
            inOwner = "java/lang/Double";
            inDesc = "(D)Ljava/lang/Double;";
            skeletonMapDesc = "(D)";
            tornadoMapMethod = "double[],";
        } else if (argType.contains("Long")) {
            GLOAD = Opcodes.LLOAD;
            inOwner = "java/lang/Long";
            inDesc = "(J)Ljava/lang/Long;";
            skeletonMapDesc = "(J)";
            tornadoMapMethod = "long[],";
        } else if (argType.contains("Float")) {
            GLOAD = Opcodes.FLOAD;
            inOwner = "java/lang/Float";
            inDesc = "(F)Ljava/lang/Float;";
            skeletonMapDesc = "(F)";
            tornadoMapMethod = "float[],";
        } else {
            GLOAD = Opcodes.ALOAD;
            argObjectClass = argType.replace("/", ".").replace("L", "").replace(";", "");
            argObject = true;
            typeClass = argType.replace(";", "").replace("L", "");
            inDesc = argType;
            descFunc = "(" + argType + ")";
            skeletonMapDesc = "(Ljava/lang/Object;)";
            // throw new Exception("Argument type " + argType + " not implemented yet");
        }
        // set everything related to the return type
        if (returnType.contains("Integer")) {
            GRETURN = Opcodes.IRETURN;
            GCONST = Opcodes.ICONST_0;
            outOwner = "java/lang/Integer";
            outName = "intValue";
            outDesc = "()I";
            skeletonMapDesc = skeletonMapDesc + "I";
            tornadoMapMethod = tornadoMapMethod + "int[]";
        } else if (returnType.contains("Double")) {
            GRETURN = Opcodes.DRETURN;
            GCONST = Opcodes.DCONST_0;
            outOwner = "java/lang/Double";
            outName = "doubleValue";
            outDesc = "()D";
            skeletonMapDesc = skeletonMapDesc + "D";
            tornadoMapMethod = tornadoMapMethod + "double[]";
        } else if (returnType.contains("Long")) {
            GRETURN = Opcodes.LRETURN;
            GCONST = Opcodes.LCONST_0;
            outOwner = "java/lang/Long";
            outName = "longValue";
            outDesc = "()J";
            skeletonMapDesc = skeletonMapDesc + "J";
            tornadoMapMethod = tornadoMapMethod + "long[]";
        } else if (returnType.contains("Float")) {
            GRETURN = Opcodes.FRETURN;
            GCONST = Opcodes.FCONST_0;
            outOwner = "java/lang/Float";
            outName = "floatValue";
            outDesc = "()F";
            skeletonMapDesc = skeletonMapDesc + "F";
            tornadoMapMethod = tornadoMapMethod + "float[]";
        } else {
            returnObjectClass = returnType.replace("/", ".").replace("L", "").replace(";", "");
            returnObject = true;
            GRETURN = Opcodes.ARETURN;
            GCONST = Opcodes.ACONST_NULL;
            descFunc = descFunc + returnType;
            skeletonMapDesc = skeletonMapDesc + "Ljava/lang/Object;";
        }
    }

    public static boolean isPOJO = false;

    public static void main(String[] args) throws IOException {
        // Examine user class to determine which skeleton to use
        FlinkClassVisitor flinkVisit = new FlinkClassVisitor();
        ClassReader flinkClassReader = new ClassReader("uk.ac.manchester.tornado.examples.FlinkMapUDF$Flink");
        flinkClassReader.accept(flinkVisit, 0);
        // try {
        setTypeVariablesMap();
        // } catch (Exception e) {
        if (argObject) {
            ObjectClassVisitor objectVisit = new ObjectClassVisitor();
            ClassReader objectClassReader = new ClassReader(argObjectClass);
            objectClassReader.accept(objectVisit, 0);
            if (implementsInterface || extendsClass || containsAnnotations) {
                System.out.println("ERROR: Tornado can only support Plain Java Objects");
                return;
            }
            isPOJO = true;
        }
        if (returnObject) {
            ObjectClassVisitor objectVisit = new ObjectClassVisitor();
            ClassReader objectClassReader = new ClassReader(returnObjectClass);
            objectClassReader.accept(objectVisit, 0);
            if (implementsInterface || extendsClass || containsAnnotations) {
                System.out.println("ERROR: Tornado can only support Plain Java Objects");
                return;
            }
            isPOJO = true;
        }

        // ASM work for map
        ClassReader readerMap = new ClassReader("uk.ac.manchester.tornado.examples.MapASMSkeleton");
        ClassWriter writerMap = new ClassWriter(readerMap, ClassWriter.COMPUTE_MAXS);
        writerMap.visitField(Opcodes.ACC_PUBLIC, "udf", "Luk/ac/manchester/tornado/examples/FlinkMapUDF$Flink;", null, null).visitEnd();
        TraceClassVisitor printerMap = new TraceClassVisitor(writerMap, new PrintWriter(System.out));
        // to remove debugging info, just replace the printer in class adapter call with
        // the writer
        MapClassAdapter adapterMap = new MapClassAdapter(printerMap);
        readerMap.accept(adapterMap, ClassReader.EXPAND_FRAMES);
        // at the moment, we execute POJOs without Tornado
        if (isPOJO) {
            Point[] pin = new Point[10];
            Centroid[] cout = new Centroid[10];
            for (int i = 0; i < pin.length; i++) {
                pin[i] = new Point(i, i);
            }
            byte[] b = writerMap.toByteArray();
            classLoader loader = new classLoader();
            Class<?> clazzMap = loader.defineClass("uk.ac.manchester.tornado.examples.MapASMSkeleton", b);
            try {
                MiddleMap mdl = (MiddleMap) clazzMap.newInstance();
                TornadoMap tmap = new TornadoMap(mdl);
                tmap.map(pin, cout);

                for (int i = 0; i < cout.length; i++) {
                    System.out.println("cout[" + i + "].id = " + cout[i].id + " cout[" + i + "].x = " + cout[i].x + " cout[" + i + "].y = " + cout[i].y);
                }
            } catch (InstantiationException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
            return;
        }

        byte[] b1 = writerMap.toByteArray();

        int[] in = new int[32];
        int[] out = new int[32];

        for (int i = 0; i < in.length; i++) {
            in[i] = i;
        }

        classLoader loader = new classLoader();
        Class<?> clazzMap = loader.defineClass("uk.ac.manchester.tornado.examples.MapASMSkeleton", b1);

        try {

            MiddleMap md = (MiddleMap) clazzMap.newInstance();
            TornadoMap msk = new TornadoMap(md);
            // at the moment, if argument/return types are objects execute it on CPU
            if (argObject || returnObject) {
                System.out.println("We will try to call it with reflection at the moment");
            } else {
                TaskSchedule tt1 = new TaskSchedule("st").task("t0", msk::map, in, out).streamOut(out);

                tt1.execute();

                for (int i = 0; i < out.length; i++) {
                    System.out.print(out[i] + "+");
                }
            }
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }

    }

}
