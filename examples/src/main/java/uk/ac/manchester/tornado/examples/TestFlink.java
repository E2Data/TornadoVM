package uk.ac.manchester.tornado.examples;

import uk.ac.manchester.tornado.api.TaskSchedule;
import uk.ac.manchester.tornado.api.TornadoDriver;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.annotations.Reduce;
import uk.ac.manchester.tornado.api.enums.TornadoDeviceType;
import uk.ac.manchester.tornado.api.runtime.TornadoRuntime;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.StringTokenizer;

public class TestFlink {

    static double[] pointsx;
    static double[] pointsy;

    static double[] centroidsID = new double[2];
    static double[] centroidsX = new double[2];
    static double[] centroidsY = new double[2];

    public static void readDataSetsFromFile(String fileName, int numOfpoints) {

        pointsx = new double[numOfpoints];
        pointsy = new double[numOfpoints];
        // This will reference one line at a time
        String line;
        boolean flag = false;
        try {
            FileReader fileReader = new FileReader(fileName);
            BufferedReader bufferedReader = new BufferedReader(fileReader);
            // flag is false while we are reading points
            int i = 0;
            int j = 0;
            while ((line = bufferedReader.readLine()) != null) {
                if (!line.contains("---- Centroids:") && !flag) {
                    StringTokenizer multiTokenizer = new StringTokenizer(line, "{,}");
                    String num1 = null;
                    String num2 = null;
                    while (multiTokenizer.hasMoreTokens()) {
                        if (num1 == null) {
                            num1 = multiTokenizer.nextToken();
                        } else {
                            num2 = multiTokenizer.nextToken();
                        }
                    }
                    pointsx[i] = Double.parseDouble(num1);
                    pointsy[i] = Double.parseDouble(num2);
                    i++;
                }
                if (line.contains("---- Centroids:")) {
                    // we have read all the points, now set flag to true to store the centroids
                    flag = true;
                    line = bufferedReader.readLine();
                }

                if (flag) {
                    // read centroids
                    StringTokenizer multiTokenizer = new StringTokenizer(line, "{,}");
                    String id = null;
                    String num1 = null;
                    String num2 = null;
                    while (multiTokenizer.hasMoreTokens()) {
                        if (id == null) {
                            id = multiTokenizer.nextToken();
                        } else if (num1 == null) {
                            num1 = multiTokenizer.nextToken();
                        } else {
                            num2 = multiTokenizer.nextToken();
                        }
                    }
                    centroidsID[j] = Double.parseDouble(id);
                    centroidsX[j] = Double.parseDouble(num1);
                    centroidsY[j] = Double.parseDouble(num2);
                    j++;
                }
            }
            bufferedReader.close();

        } catch (FileNotFoundException ex) {
            ex.printStackTrace();
        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    public static void selectNearestCentroids(double[] pointsX, double[] pointsY, double[] centroidsId, double[] centroidsX, double[] centroidsY, double[] selId) {
        for (@Parallel int j = 0; j < pointsX.length; j++) {
            double pointX = pointsX[j];
            double pointY = pointsY[j];
            double minDistance = Double.MAX_VALUE;
            double closestCentroidId = -1;
            double currCentrId = centroidsId[0];
            if (currCentrId == 1) {
                double distance = Math.sqrt((pointX - centroidsX[0]) * (pointX - centroidsX[0]) + (pointY - centroidsY[0]) * (pointY - centroidsY[0]));
                if (distance < minDistance) {
                    minDistance = distance;
                    closestCentroidId = centroidsId[0];
                }
                currCentrId = 2;
            }
            if (currCentrId == 2) {
                double distance = Math.sqrt((pointX - centroidsX[1]) * (pointX - centroidsX[1]) + (pointY - centroidsY[1]) * (pointY - centroidsY[1]));
                if (distance < minDistance) {
                    closestCentroidId = centroidsId[1];
                }
            }
            selId[j] = closestCentroidId;
        }
    }

    public static void groupBy(double[] pointsx, double[] pointsy, double[] selid, double[] pointx1, double[] pointy1, double[] pointx2, double[] pointy2) {

        for (@Parallel int i = 0; i < pointsx.length; i++) {
            if (selid[i] == 1) {
                pointx1[i] = pointsx[i];
                pointy1[i] = pointsy[i];
            } else {
                pointx2[i] = pointsx[i];
                pointy2[i] = pointsy[i];
            }
        }
    }

    public static void cendroidAccum(double[] point, @Reduce double[] accres) {
        accres[0] = 0;
        for (@Parallel int k = 0; k < point.length; k++) {
            accres[0] += point[k];
        }
    }

    public static void centroidAvg(double[] redResX, double[] redResY, int[] redResApp, double[] centrX, double[] centrY) {
        for (@Parallel int i = 0; i < redResX.length; i++) {
            centrX[i] = (redResX[i] / redResApp[i]);
            centrY[i] = (redResY[i] / redResApp[i]);
        }
    }

    public static TornadoDeviceType getDefaultDeviceType() {
        TornadoDriver driver = TornadoRuntime.getTornadoRuntime().getDriver(0);
        return driver.getTypeDefaultDevice();
    }

    public static void main(String[] args) {

        int numOfpoints = Integer.parseInt(args[0]);
        String filename = args[1];

        readDataSetsFromFile(filename, numOfpoints);

        // selectNearest
        double[] selId = new double[numOfpoints];
        double[] selId2 = new double[numOfpoints];
        // groupBy
        double[] pointx1 = new double[numOfpoints];
        double[] pointy1 = new double[numOfpoints];
        double[] pointx2 = new double[numOfpoints];
        double[] pointy2 = new double[numOfpoints];
        int[] count = new int[2];
        // centrAcc
        double[] redResX1 = null;
        double[] redResY1 = null;
        double[] redResX2 = null;
        double[] redResY2 = null;
        // avg
        double[] redResX = new double[2];
        double[] redResY = new double[2];
        double[] centrX = new double[2];
        double[] centrY = new double[2];
	long total_start, total_end, ts0_end, count_end, ts1_end, wg_end, red_end, total, ts0_time, ts1_time, ts2_time, count_elem, wg, red, extra, total_sum;
        // ---------------------------

        int numGroups1 = 1;
        int numGroups2 = 1;
	// NOTE: The following two initializations are only correct for
	// for this particular set of experiments with this input datasets!
        int centrpoints1 = numOfpoints / 2;
        int centrpoints2 = numOfpoints / 2;
	
	
        int workgroup_size = 128;

        if (centrpoints1 > workgroup_size) {
            numGroups1 = centrpoints1 / workgroup_size;
        }
        if (centrpoints2 > workgroup_size) {
            numGroups2 = centrpoints2 / workgroup_size;
        }
        TornadoDeviceType deviceType = getDefaultDeviceType();
        switch (deviceType) {
            case CPU:
                redResX1 = new double[Runtime.getRuntime().availableProcessors()];
                redResY1 = new double[Runtime.getRuntime().availableProcessors()];
                redResX2 = new double[Runtime.getRuntime().availableProcessors()];
                redResY2 = new double[Runtime.getRuntime().availableProcessors()];
                break;
            case DEFAULT:
                break;
            case GPU:
                redResX1 = new double[numGroups1];
                redResY1 = new double[numGroups1];
                redResX2 = new double[numGroups2];
                redResY2 = new double[numGroups2];
                break;
            default:
                break;
        }
	
	wg_end = System.nanoTime();

	TaskSchedule ts0 = new TaskSchedule("s0").task("t0", TestFlink::selectNearestCentroids, pointsx, pointsy, centroidsID, centroidsX, centroidsY, selId).streamOut(selId)
                .task("t1", TestFlink::groupBy, pointsx, pointsy, selId, pointx1, pointy1, pointx2, pointy2).streamOut(pointx1).streamOut(pointy1).streamOut(pointx2).streamOut(pointy2);

	TaskSchedule ts1 = new TaskSchedule("s1").task("t0", TestFlink::cendroidAccum, pointx1, redResX1).streamOut(redResX1).task("t1", TestFlink::cendroidAccum, pointy1, redResY1)
                .streamOut(redResY1).task("t2", TestFlink::cendroidAccum, pointx2, redResX2).streamOut(redResX2).task("t3", TestFlink::cendroidAccum, pointy2, redResY2).streamOut(redResY2);

	TaskSchedule ts2 = new TaskSchedule("s2").task("t0", TestFlink::centroidAvg, redResX, redResY, count, centrX, centrY).streamOut(centrX).streamOut(centrY);
	
	ts0.warmup();
	ts1.warmup();
	ts2.warmup();

	// === start benchmarking ===
	
	total_start = System.nanoTime();
	
        ts0.execute();

	ts0_end = System.nanoTime();

        int numpoints1 = 0;
        int numpoints2 = 0;
        for (int i = 0; i < pointsx.length; i++) {
            if (selId[i] == 1) {
                numpoints1++;
            } else {
                numpoints2++;
            }
        }
	
	count[0] = numpoints1;
	count[1] = numpoints2;

	count_end = System.nanoTime();

        ts1.execute();

	ts1_end = System.nanoTime();

        for (int j = 0; j < redResX1.length; j++) {
            redResX[0] += redResX1[j];
            redResY[0] += redResY1[j];
        }
        for (int j = 0; j < redResX2.length; j++) {
            redResX[1] += redResX2[j];
            redResY[1] += redResY2[j];
        }

	red_end = System.nanoTime();
	
        ts2.execute();

	total_end = System.nanoTime(); 
	
	// ------------
	total = total_end - total_start;
	ts0_time = ts0_end - total_start;
	count_elem = count_end - ts0_end;
	ts1_time = ts1_end - count_end;
	red = red_end - ts1_end;
	ts2_time = total_end - red_end;
	extra = red + count_elem;
	total_sum = ts0_time + ts1_time + ts2_time + count_elem + red; 	

	System.out.println("=== Task Schedule 0: " + ts0_time + " (ns)");
	System.out.println("=== Task Schedule 1: " + ts1_time + " (ns)");
	System.out.println("=== Task Schedule 2: " + ts2_time + " (ns)");
	System.out.println("=== Count number of points per centroid: " + count_elem + " (ns)");
	System.out.println("=== Sum results of reduction: " + red + " (ns)");
	System.out.println("=== Total: " + total + " (ns)");
	System.out.println("=== Total (ts0 + ts1 + ts2 + extras): " + total_sum + " (ns)");
	System.out.println("=== Extra operations on CPU: " + extra + " (ns)");
	
    }

}
