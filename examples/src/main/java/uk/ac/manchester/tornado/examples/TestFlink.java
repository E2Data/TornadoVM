package uk.ac.manchester.tornado.examples;

import uk.ac.manchester.tornado.api.TaskSchedule;

import java.util.ArrayList;

public class TestFlink {

    public static final double[] centroids_id = new double[] { 1, 2, 3 };
    public static final double[] centroids_x = new double[] { -31.85, 35.16, 2.3 };
    public static final double[] centroids_y = new double[] { -44.77, 17.46, 3.1 };

    public static final double[] points_x = new double[] { -14.22, -22.78, 56.18, 35.04, -9.53, -34.35, 1.2 };
    public static final double[] points_y = new double[] { -48.01, 37.10, 56.18, -42.99, 50.29, -46.26, 3.4 };

    public static double euclideanDistance(double p1_x, double p1_y, double p2_x, double p2_y) {
        return Math.sqrt((p1_x - p2_x) * (p1_x - p2_x) + (p1_y - p2_y) * (p1_y - p2_y));
    }

    public static void main(String[] args) {

        double sel_id[] = new double[points_x.length];
        double[] newcentrx = new double[centroids_id.length];
        double[] newcentry = new double[centroids_id.length];
        TestF tf = new TestF();
        RichTornadoMapFunction selectnearest = (RichTornadoMapFunction) tf.retSelect();
        // for(int i = 0; i < iterations; i++) {

        new TaskSchedule("s0").task("t0", selectnearest::tmap, centroids_id, centroids_x, centroids_y, points_x, points_y, sel_id).streamOut(sel_id).execute();

        for (int i = 0; i < sel_id.length; i++) {
            System.out.println("sel_id[" + i + "] = " + sel_id[i] + " point_x[" + i + "] = " + points_x[i] + " point_y[" + i + "] = " + points_y[i]);
        }

        double sel_app[] = new double[sel_id.length];
        for (int i = 0; i < sel_app.length; i++) {
            sel_app[i] = 1D;
        }
        // so the arrays sel_id, sel_x, sel_y, sel_app einai to apotelesma tou
        // select.append
        ArrayList<Double> centr_array[] = new ArrayList[centroids_id.length];
        // for each centroid create an arraylist this is done in order to simulate the
        // groupBy() operation of Flink

        // populate centroid arraylists

        for (int z = 0; z < centroids_id.length; z++) {
            ArrayList<Double> centroidPoints = new ArrayList<>();
            for (int w = 0; w < sel_id.length; w++) {
                if (sel_id[w] == centroids_id[z]) {
                    centroidPoints.add(points_x[w]);
                    centroidPoints.add(points_y[w]);
                }
            }
            centr_array[z] = centroidPoints;
        }

        for (int i = 0; i < centr_array.length; i++) {
            for (int j = 1; j < centr_array[i].size(); j++) {
                System.out.println("centroid_id: " + centroids_id[i] + " point_x= " + centr_array[i].get(j - 1) + " point_y= " + centr_array[i].get(j));
            }
        }

        // for each centroid: execute accumulate and average on its points
        for (int i = 0; i < centr_array.length; i++) {
            // how many points are assigned to this centroid?
            int size = centr_array[i].size();
            if (size != 0) {
                // create the point arrays
                double[] centrpoints_x = new double[size / 2];
                double[] centrpoints_y = new double[size / 2];
                for (int j = 0; j < size / 2; j++) {
                    centrpoints_x[j] = centr_array[i].get(j * 2);
                }
                for (int k = 0; k < size / 2; k++) {
                    centrpoints_y[k] = centr_array[i].get((k * 2) + 1);
                }
                for (int w = 0; w < size / 2; w++) {
                    System.out.println("x: " + centrpoints_x[w]);
                }
                for (int w = 0; w < size / 2; w++) {
                    System.out.println("y: " + centrpoints_y[w]);
                }
                double[] accres = new double[3];
                double[] newcentr = new double[2];
                TornadoReduceFunctionBase accum = (TornadoReduceFunctionBase) tf.retAccum();
                TornadoMapFunctionBase avg = (TornadoMapFunctionBase) tf.retAvg();
                new TaskSchedule("s1").task("t1", accum::treduce, centrpoints_x, centrpoints_y, accres).task("t2", avg::tmap, accres, newcentr).streamOut(newcentr).execute();
                System.out.println("New centroid: x = " + newcentr[0] + " y = " + newcentr[1]);
                newcentrx[i] = newcentr[0];
                newcentry[i] = newcentr[1];
            } else {
                // no points assigned to this centroid. The coords do not change
                newcentrx[i] = centroids_x[i];
                newcentry[i] = centroids_y[i];
            }
        }
        // }
        System.out.println("=== new centroids: ===");
        for (int i = 0; i < newcentrx.length; i++) {
            System.out.println("x: " + newcentrx[i] + " y: " + newcentry[i]);
        }
        double[] final_output = new double[points_x.length];
        RichTornadoMapFunction selectnearest2 = (RichTornadoMapFunction) tf.retSelect();
        new TaskSchedule("s2").task("t3", selectnearest2::tmap, centroids_id, newcentrx, newcentry, points_x, points_y, final_output).streamOut(final_output).execute();
        System.out.println("------ Kmeans results ------");
        for (int i = 0; i < centr_array.length; i++) {
            for (int j = 1; j < centr_array[i].size(); j++) {
                System.out.println("centroid_id: " + centroids_id[i] + " point_x= " + centr_array[i].get(j - 1) + " point_y= " + centr_array[i].get(j));
            }
        }
    }
}