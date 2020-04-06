package uk.ac.manchester.tornado.examples;

import uk.ac.manchester.tornado.api.TaskSchedule;
import uk.ac.manchester.tornado.api.annotations.Parallel;

public class TestBytecodes {

    public class FirstFunction {

        MiddleClass md;

        FirstFunction(MiddleClass md) {
            this.md = md;
        }

        public void foo(int[] in, int[] out) {
            for (@Parallel int i = 0; i < in.length; i++) {
                out[i] = md.returnInput(in[i]);
            }
        }
    }

    public class SecondFunction {

        MiddleClass md;

        SecondFunction(MiddleClass md) {
            this.md = md;
        }

        public void bar(int[] in, int[] in2, int[] out) {
            for (@Parallel int i = 0; i < in.length; i++) {
                out[i] = md.sumTwoNumbers(in[i], in2[i]);
            }
        }
    }

    abstract class MiddleClass {
        public abstract int sumTwoNumbers(int in, int in2);

        public abstract int returnInput(int in);

    }

    public class MiddleImpl extends MiddleClass {

        public int sumTwoNumbers(int in, int in2) {
            return in + in2;
        }

        public int returnInput(int in) {
            return in;
        }

    }

    public void test() {
        int[] in = new int[32];
        int[] out = new int[32];

        int[] in2 = new int[32];
        int[] out2 = new int[32];

        MiddleClass md = new MiddleImpl();
        MiddleClass md1 = new MiddleImpl();
        FirstFunction f = new FirstFunction(md);
        SecondFunction sc = new SecondFunction(md1);
        for (int i = 0; i < in.length; i++) {
            in[i] = i;
            in2[i] = 1;
        }

        System.out.println("== First Task Schedule");
        new TaskSchedule("s0").task("t0", f::foo, in, out).streamOut(out).execute();

        for (int i = 0; i < out2.length; i++) {
            System.out.println("out[" + i + "] = " + out[i]);
        }

        System.out.println("== Second Task Schedule");
        new TaskSchedule("s1").task("t1", sc::bar, in, in2, out2).streamOut(out2).execute();

        for (int i = 0; i < out2.length; i++) {
            System.out.println("out2[" + i + "] = " + out2[i]);
        }
    }

    public static void main(String[] args) {
        TestBytecodes ts = new TestBytecodes();
        ts.test();
    }

}
