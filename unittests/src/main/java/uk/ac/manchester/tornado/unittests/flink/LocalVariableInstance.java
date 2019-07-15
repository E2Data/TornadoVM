package uk.ac.manchester.tornado.unittests.flink;

import org.junit.Test;
import uk.ac.manchester.tornado.api.TaskSchedule;
import uk.ac.manchester.tornado.api.annotations.Parallel;

import static org.junit.Assert.assertArrayEquals;

public class LocalVariableInstance {

    public static class FlinkMapUDF {
        public int map(int value) {
            int x = value - 2 + 9;
            return value * x;
        }
    }

    public static abstract class MiddleMap {
        public abstract int mymapintint(int i);
    }

    public static class MyMap extends MiddleMap {
        @Override
        public int mymapintint(int i) {
            FlinkMapUDF fudf = new FlinkMapUDF();
            return fudf.map(i);
        }
    }

    public static class MapSkeleton {

        public MiddleMap mdm;

        MapSkeleton(MiddleMap mdm) {
            this.mdm = mdm;
        }

        public void map(int[] input, int[] output) {
            for (@Parallel int i = 0; i < input.length; i++) {
                output[i] = mdm.mymapintint(i);
            }
        }

    }

    public int[] seq(int in[]) {
        int[] out = new int[in.length];
        int x;
        for (int i = 0; i < in.length; i++) {
            x = i - 2 + 9;
            out[i] = i * x;
        }
        return out;
    }

    @Test
    public void testLocalVariable() {

        int[] in = new int[5];
        int[] out = new int[5];

        for (int i = 0; i < in.length; i++) {
            in[i] = i;
        }

        MyMap mm = new MyMap();
        MiddleMap md = (MiddleMap) mm;
        MapSkeleton msk = new MapSkeleton(md);
        TaskSchedule task = new TaskSchedule("s0").streamIn(in).task("t0", msk::map, in, out).streamOut(out);

        task.execute();

        int[] seq = seq(in);

        assertArrayEquals(seq, out);
    }

}
