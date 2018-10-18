package uk.ac.manchester.tornado.examples;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.annotations.Reduce;
import uk.ac.manchester.tornado.api.exceptions.Debug;

public class TestF {

    public static final class SelectNearestCenter extends TornadoMapFunctionBase<Double, Double> {
        @Override
        public void compute(double[] points_x, double[] points_y, double[] centroids_id, double[] centroids_x, double[] centroids_y, double[] sel_id) {
            for (@Parallel int j = 0; j < points_x.length; j++) {
                double point_x = points_x[j];
                double point_y = points_y[j];
                double minDistance = Double.MAX_VALUE;
                double closestCentroidId = -1;
                double curr_centr_id = centroids_id[0];

                if (curr_centr_id == 1) {
                    double distance = Math.sqrt((point_x - centroids_x[0]) * (point_x - centroids_x[0]) + (point_y - centroids_y[0]) * (point_y - centroids_y[0]));
                    // update nearest cluster if necessary
                    if (distance < minDistance) {
                        minDistance = distance;
                        closestCentroidId = centroids_id[0];
                    }
                    curr_centr_id = 2;
                }
                if (curr_centr_id == 2) {
                    double distance = Math.sqrt((point_x - centroids_x[1]) * (point_x - centroids_x[1]) + (point_y - centroids_y[1]) * (point_y - centroids_y[1]));
                    // update nearest cluster if necessary
                    if (distance < minDistance) {
                        minDistance = distance;
                        closestCentroidId = centroids_id[1];
                    }
                    curr_centr_id = 3;
                }
                if (curr_centr_id == 3) {
                    double distance = Math.sqrt((point_x - centroids_x[2]) * (point_x - centroids_x[2]) + (point_y - centroids_y[2]) * (point_y - centroids_y[2]));
                    // update nearest cluster if necessary
                    if (distance < minDistance) {
                        closestCentroidId = centroids_id[2];
                    }
                }
                sel_id[j] = closestCentroidId;
            }
        }

    }

    public static final class CentroidAccumulator extends TornadoReduceFunctionBase<Double> {

        @Override
        public void compute(double[] point, @Reduce double[] accres) {
            accres[0] = 0;
            for (@Parallel int k = 0; k < point.length; k++) {
                accres[0] += point[k];
            }

        }

    }

    public static final class CentroidAverager extends TornadoMapFunctionBase2<Double, Double> {

        @Override
        public void compute(double[] red_res_x, double[] red_res_y, double[] red_res_app, double[] centr_x, double[] centr_y) {
            for (@Parallel int i = 0; i < red_res_x.length; i++) {
                centr_x[i] = (red_res_x[i] / red_res_app[i]);
                centr_y[i] = (red_res_y[i] / red_res_app[i]);
            }
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
