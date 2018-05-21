package uk.ac.manchester.tornado.examples;

public abstract class TornadoMapFunctionBase<T, O> implements TornadoMapFunction<T, O> {
    public abstract void compute(double[] a, double[] b, double[] c, double[] d, double[] e, double[] f);

    @Override
    public void tmap(double[] a, double[] b, double[] c, double[] d, double[] e, double[] f) {
        compute(a, b, c, d, e, f);
    }

}