package uk.ac.manchester.tornado.examples;

import uk.ac.manchester.tornado.api.annotations.Parallel;

public class TornadoMap<T, O> {

    public MiddleMap mdm;

    TornadoMap(MiddleMap mdm) {
        this.mdm = mdm;
    }

    public void map(int[] in, int[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {
            out[i] = mdm.mymapintint(in[i]);
        }
    }

    public void map(double[] in, int[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {
            out[i] = mdm.mymapintdouble(in[i]);
        }

    }

    public void map(float[] in, int[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {
            out[i] = mdm.mymapintfloat(in[i]);
        }

    }

    public void map(long[] in, int[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {
            out[i] = mdm.mymapintlong(in[i]);
        }

    }

    public void map(double[] in, double[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {
            out[i] = mdm.mymapdoubledouble(in[i]);
        }

    }

    public void map(int[] in, double[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {
            out[i] = mdm.mymapdoubleint(in[i]);
        }

    }

    public void map(float[] in, double[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {
            out[i] = mdm.mymapdoublefloat(in[i]);
        }

    }

    public void map(long[] in, double[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {
            out[i] = mdm.mymapdoublelong(in[i]);
        }

    }

    public void map(float[] in, float[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {
            out[i] = mdm.mymapfloatfloat(in[i]);
        }

    }

    public void map(int[] in, float[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {
            out[i] = mdm.mymapfloatint(in[i]);
        }

    }

    public void map(long[] in, float[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {
            out[i] = mdm.mymapfloatlong(in[i]);
        }

    }

    public void map(double[] in, float[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {
            out[i] = mdm.mymapfloatdouble(in[i]);
        }

    }

    public void map(long[] in, long[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {
            out[i] = mdm.mymaplonglong(in[i]);
        }

    }

    public void map(int[] in, long[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {
            out[i] = mdm.mymaplongint(in[i]);
        }

    }

    public void map(float[] in, long[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {
            out[i] = mdm.mymaplongfloat(in[i]);
        }

    }

    public void map(double[] in, long[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {
            out[i] = mdm.mymaplongdouble(in[i]);
        }

    }

    public void map(T[] in, O[] out) {

        for (@Parallel int i = 0; i < in.length; i++) {
            out[i] = (O) mdm.mymapobjectobject(in[i]);
        }

    }

}
