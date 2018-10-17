package uk.ac.manchester.tornado.examples;

public abstract class TornadoMapFunctionBase<T, O> implements TornadoMapFunction<T, O> {

    /*
     * public abstract void compute(double[] a, double[] b);
     * 
     * public abstract void compute(float[] a, float[] b, float[] c, float[] d,
     * float[] e, float[] f);
     * 
     * public abstract void compute(float alpha, float[] x, float[] y);
     * 
     * @Override public void tmap(double[] a, double[] b) { compute(a, b); }
     * 
     * @Override public void tmap(float[] a, float[] b, float[] c, float[] d,
     * float[] e, float[] f) { compute(a, b, c, d, e, f); }
     * 
     * @Override public void tmap(float a, float[] b, float[] c) { compute(a, b, c);
     * }
     */

    public abstract void compute(double[] a, double[] b, double[] c, double[] d, double[] e, double[] f);

    @Override
    public void tmap(double[] a, double[] b, double[] c, double[] d, double[] e, double[] f) {
        compute(a, b, c, d, e, f);
    }

}
