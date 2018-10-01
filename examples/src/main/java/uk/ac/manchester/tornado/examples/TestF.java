package uk.ac.manchester.tornado.examples;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.collections.types.Double2;
import uk.ac.manchester.tornado.api.collections.types.Double3;
import uk.ac.manchester.tornado.api.collections.types.Double4;

public class TestF {

    public static final class SelectNearestCenter extends RichTornadoMapFunction<Double2, Double3> {

        @Override
        public void compute(double[] centroids_id, double[] centroids_x, double[] centroids_y, double[] points_x, double[] points_y, double[] sel_id) {
            for (@Parallel int j = 0; j < points_x.length; j++) {
                double point_x = points_x[j];
                double point_y = points_y[j];
                double minDistance = Double.MAX_VALUE;
                double closestCentroidId = -1;

                // check all cluster centers
                for (int i = 0; i < centroids_id.length; i++) {
                    // compute distance
                    double distance = TestFlink.euclideanDistance(point_x, point_y, centroids_x[i], centroids_y[i]);

                    // update nearest cluster if necessary
                    if (distance < minDistance) {
                        minDistance = distance;
                        closestCentroidId = centroids_id[i];
                    }
                }
                sel_id[j] = closestCentroidId;
            }
        }

        @Override
        public void tmap(double[] input, double[] output) {

        }

    }

    /** Sums and counts point coordinates. */
    public static final class CentroidAccumulator extends TornadoReduceFunctionBase<Double4> {

        @Override
        public void compute(double[] point_x, double[] point_y, double[] accres) {
            double sumx = 0;
            double sumy = 0;
            double p1,p2;
            for (@Parallel int i = 2; i < point_x.length; i++) {
                if (i == 1) {
                    p1 = point_x[i - 1] + point_x[i];
                    p2 = point_y[i - 1] + point_y[i];
                } else {
                    p1 = sumx + point_x[i];
                    p2 = sumy + point_y[i];
                }
                sumx = p1;
                sumy = p2;
            }
            accres[0] = sumx;
            accres[1] = sumy;
            accres[2] = point_x.length;
        }

    }

    /** Computes new centroid from coordinate sum and count of points. */
    public static final class CentroidAverager extends TornadoMapFunctionBase<Double4, Double3> {

        @Override
        public void compute(double[] accinput, double[] centr) {
            double centrx = accinput[0] / accinput[2];
            double centry = accinput[1] / accinput[2];
            centr[0] = centrx;
            centr[1] = centry;
        }

        @Override
        public void compute(double[] a, double[] b, double[] c, double[] d, double[] e, double[] f) {

        }

    }

    MapFunction retSelect() {
        return new SelectNearestCenter();
    }

    ReduceFunction retAccum() {
        return new CentroidAccumulator();
    }

    MapFunction retAvg() {
        return new CentroidAverager();
    }

}
