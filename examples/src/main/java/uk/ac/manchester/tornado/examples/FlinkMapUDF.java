package uk.ac.manchester.tornado.examples;

public class FlinkMapUDF {

    public static final class Flink implements MapFunction<Point, Centroid> {

        @Override
        public Centroid map(Point value) {
            // int x = value - 2 + 9;
            // return x;
            int id = value.x;
            int x = value.x + 2 - 9;
            int y = value.y - 2 + 9;
            return new Centroid(id, x, y);
        }

    }

}
