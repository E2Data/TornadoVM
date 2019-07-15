package uk.ac.manchester.tornado.unittests.flink;

import org.junit.Test;
import uk.ac.manchester.tornado.api.TaskSchedule;
import uk.ac.manchester.tornado.api.annotations.Parallel;

import java.io.Serializable;

public class TestPointsAndCentroids {

    /**
     * A simple two-dimensional point.
     */
    public static class Point implements Serializable {

        public double x,y;

        public Point() {
        }

        public Point(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public Point add(Point other) {
            x += other.x;
            y += other.y;
            return this;
        }

        public Point div(long val) {
            x /= val;
            y /= val;
            return this;
        }

        public double euclideanDistance(Point other) {
            return Math.sqrt((x - other.x) * (x - other.x) + (y - other.y) * (y - other.y));
        }

        public void clear() {
            x = y = 0.0;
        }

        @Override
        public String toString() {
            return x + " " + y;
        }
    }

    /**
     * A simple two-dimensional centroid, basically a point with an ID.
     */
    public static class Centroid extends Point {

        public int id;

        public Centroid() {
        }

        public Centroid(int id, double x, double y) {
            super(x, y);
            this.id = id;
        }

        public Centroid(int id, Point p) {
            super(p.x, p.y);
            this.id = id;
        }

        @Override
        public String toString() {
            return id + " " + super.toString();
        }

    }

    public static class mapPointsToCentroids {

        public static void map(Point[] points, Centroid[] centroids, int[] ids) {
            for (@Parallel int i = 0; i < points.length; i++) {
                double minDistance = Double.MAX_VALUE;
                int closestCentroidId = -1;

                // check all cluster centers
                for (int j = 0; j < centroids.length; j++) {
                    // compute distance
                    double distance = points[i].euclideanDistance(centroids[j]);

                    // update nearest cluster if necessary
                    if (distance < minDistance) {
                        minDistance = distance;
                        closestCentroidId = centroids[j].id;
                    }
                }
                ids[i] = closestCentroidId;
            }
        }

    }

    @Test
    public void testFlinkTypes() {
        Point[] points = new Point[15];
        Centroid[] centroids = new Centroid[2];
        int ids[] = new int[15];

        for (int i = 0; i < points.length; i++) {
            points[i] = new Point(i, i);
        }
        int j = 2;
        int z = 3;
        for (int i = 0; i < centroids.length; i++) {
            centroids[i] = new Centroid(i, i * j, i * z);
        }

        TaskSchedule task = new TaskSchedule("s0").streamIn(points).streamIn(centroids).task("t0", mapPointsToCentroids::map, points, centroids, ids).streamOut(ids);
        task.execute();
    }

}
