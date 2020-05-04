/*
 * Copyright (c) 2013-2020, APT Group, Department of Computer Science,
 * The University of Manchester.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */
package uk.ac.manchester.tornado.benchmarks.dft;

import static uk.ac.manchester.tornado.api.collections.math.TornadoMath.abs;

import uk.ac.manchester.tornado.api.TaskSchedule;
import uk.ac.manchester.tornado.benchmarks.BenchmarkDriver;
import uk.ac.manchester.tornado.benchmarks.ComputeKernels;

public class DftTornado extends BenchmarkDriver {

    private int size;
    private TaskSchedule graph;
    private double[] inReal;
    private double[] inImag;
    private double[] outReal;
    private double[] outImag;

    public DftTornado(int iterations, int size) {
        super(iterations);
        this.size = size;
    }

    private void initData() {
        inReal = new double[size];
        inImag = new double[size];
        outReal = new double[size];
        outImag = new double[size];
        for (int i = 0; i < size; i++) {
            inReal[i] = 1 / (double) (i + 2);
            inImag[i] = 1 / (double) (i + 2);
        }
    }

    @Override
    public void setUp() {
        initData();
        graph = new TaskSchedule("benchmark") //
                .streamIn(inReal, inImag) //
                .task("t0", ComputeKernels::computeDft, inReal, inImag, outReal, outImag) //
                .streamOut(outReal, outImag);
        graph.warmup();
    }

    @Override
    public boolean validate() {
        boolean validation = true;
        double[] outRealTor = new double[size];
        double[] outImagTor = new double[size];

        graph.warmup();
        graph.execute();
        graph.streamOut(outReal, outImag);

        ComputeKernels.computeDft(inReal, inImag, outRealTor, outImagTor);

        for (int i = 0; i < size; i++) {
            if (abs(outImagTor[i] - outImag[i]) > 0.01) {
                validation = false;
                break;
            }
            if (abs(outReal[i] - outRealTor[i]) > 0.01) {
                validation = false;
                break;
            }
        }
        System.out.print("Is correct?: " + validation + "\n");
        return validation;
    }

    @Override
    public void tearDown() {
        graph.dumpProfiles();

        outImag = null;
        outReal = null;

        graph.getDevice().reset();
        super.tearDown();
    }

    @Override
    public void benchmarkMethod() {
        graph.execute();

    }
}
