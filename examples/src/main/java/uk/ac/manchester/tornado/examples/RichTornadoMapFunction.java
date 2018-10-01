package uk.ac.manchester.tornado.examples;

public abstract class RichTornadoMapFunction<T1, T2> implements TornadoMapFunction<T1, T2> {

    private static final long serialVersionUID = 1L;

    public abstract void compute(double[] a, double[] b, double[] c, double[] d, double[] e, double[] f);

    @Override
    public void tmap(double[] input, double[] output) {

    }

    @Override
    public void tmap(double[] a, double[] b, double[] c, double[] d, double[] e, double[] f) {
        compute(a, b, c, d, e, f);
    }

}
