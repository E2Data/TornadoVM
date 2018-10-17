package uk.ac.manchester.tornado.examples;

public abstract class TornadoMapFunctionBase2<T, O> implements TornadoMapFunction2<T, O> {

    public abstract void compute(double[] a, double[] b, double[] c, double[] d, double[] e);

    @Override
    public void tmap(double[] a, double[] b, double[] c, double[] d, double[] e) {
        compute(a, b, c, d, e);
    }

}
