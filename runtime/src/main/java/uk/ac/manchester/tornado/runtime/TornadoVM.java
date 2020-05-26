/*
 * This file is part of Tornado: A heterogeneous programming framework:
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2013-2020, APT Group, Department of Computer Science,
 * The University of Manchester. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Authors: James Clarkson
 *
 */
package uk.ac.manchester.tornado.runtime;

import static uk.ac.manchester.tornado.api.enums.TornadoExecutionStatus.COMPLETE;
import static uk.ac.manchester.tornado.runtime.common.Tornado.ENABLE_PROFILING;
import static uk.ac.manchester.tornado.runtime.common.Tornado.USE_VM_FLUSH;
import static uk.ac.manchester.tornado.runtime.common.Tornado.VM_USE_DEPS;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import uk.ac.manchester.tornado.api.common.Access;
import uk.ac.manchester.tornado.api.common.Event;
import uk.ac.manchester.tornado.api.common.SchedulableTask;
import uk.ac.manchester.tornado.api.common.TornadoEvents;
import uk.ac.manchester.tornado.api.exceptions.TornadoFailureException;
import uk.ac.manchester.tornado.api.exceptions.TornadoInternalError;
import uk.ac.manchester.tornado.api.exceptions.TornadoRuntimeException;
import uk.ac.manchester.tornado.api.flink.FlinkData;
import uk.ac.manchester.tornado.api.profiler.ProfilerType;
import uk.ac.manchester.tornado.api.profiler.TornadoProfiler;
import uk.ac.manchester.tornado.runtime.common.CallStack;
import uk.ac.manchester.tornado.runtime.common.DeviceObjectState;
import uk.ac.manchester.tornado.runtime.common.TornadoAcceleratorDevice;
import uk.ac.manchester.tornado.runtime.common.TornadoInstalledCode;
import uk.ac.manchester.tornado.runtime.common.TornadoLogger;
import uk.ac.manchester.tornado.runtime.common.TornadoOptions;
import uk.ac.manchester.tornado.runtime.graph.TornadoExecutionContext;
import uk.ac.manchester.tornado.runtime.graph.TornadoGraphAssembler.TornadoVMBytecodes;
import uk.ac.manchester.tornado.runtime.tasks.GlobalObjectState;
import uk.ac.manchester.tornado.runtime.tasks.TornadoTaskSchedule;
import uk.ac.manchester.tornado.runtime.tasks.meta.TaskMetaData;

/**
 * TornadoVM: it includes a bytecode interpreter (Tornado bytecodes), a memory
 * manager for all devices (FPGAs, GPUs and multi-core that follows the OpenCL
 * programming model), and a JIT compiler from Java bytecode to OpenCL.
 * <p>
 * The JIT compiler extends the Graal JIT Compiler for OpenCL compilation.
 * <p>
 * There is an instance of the {@link TornadoVM} per
 * {@link TornadoTaskSchedule}. Each TornadoVM contains the logic to orchestrate
 * the execution on the parallel device (e.g., a GPU).
 */
public class TornadoVM extends TornadoLogger {

    private static final Event EMPTY_EVENT = new EmptyEvent();

    private static final int MAX_EVENTS = 32;
    private final boolean useDependencies;

    private final TornadoExecutionContext graphContext;
    private final List<Object> objects;
    private final GlobalObjectState[] globalStates;
    private final CallStack[] stacks;
    private final int[][] events;
    private final int[] eventsIndicies;
    private final List<TornadoAcceleratorDevice> contexts;
    private final TornadoInstalledCode[] installedCodes;

    private final List<Object> constants;
    private final List<SchedulableTask> tasks;

    private final ByteBuffer buffer;

    private double totalTime;
    private long invocations;
    private TornadoProfiler timeProfiler;
    private boolean finishedWarmup;

    public TornadoVM(TornadoExecutionContext graphContext, byte[] code, int limit, TornadoProfiler timeProfiler) {

        this.graphContext = graphContext;
        this.timeProfiler = timeProfiler;

        useDependencies = graphContext.meta().enableOooExecution() | VM_USE_DEPS;
        totalTime = 0;
        invocations = 0;

        buffer = ByteBuffer.wrap(code);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.limit(limit);

        debug("loading tornado vm...");

        TornadoInternalError.guarantee(buffer.get() == TornadoVMBytecodes.SETUP.value(), "invalid code");
        contexts = graphContext.getDevices();
        buffer.getInt();
        int taskCount = buffer.getInt();
        stacks = graphContext.getFrames();
        events = new int[buffer.getInt()][MAX_EVENTS];
        eventsIndicies = new int[events.length];

        // System.out.println("- initialize installedCodes in constructor");
        installedCodes = new TornadoInstalledCode[taskCount];

        for (int i = 0; i < events.length; i++) {
            Arrays.fill(events[i], -1);
            eventsIndicies[i] = 0;
        }

        debug("found %d contexts", contexts.size());
        debug("created %d stacks", stacks.length);
        debug("created %d event lists", events.length);

        objects = graphContext.getObjects();
        globalStates = new GlobalObjectState[objects.size()];
        debug("fetching %d object states...", globalStates.length);
        for (int i = 0; i < objects.size(); i++) {
            final Object object = objects.get(i);
            TornadoInternalError.guarantee(object != null, "null object found in TornadoVM");
            globalStates[i] = TornadoCoreRuntime.getTornadoRuntime().resolveObject(object);
            debug("\tobject[%d]: [0x%x] %s %s", i, object.hashCode(), object.getClass().getTypeName(), globalStates[i]);
        }

        byte op = buffer.get();
        while (op != TornadoVMBytecodes.BEGIN.value()) {
            TornadoInternalError.guarantee(op == TornadoVMBytecodes.CONTEXT.value(), "invalid code: 0x%x", op);
            final int deviceIndex = buffer.getInt();
            debug("loading context %s", contexts.get(deviceIndex));
            final long t0 = System.nanoTime();
            contexts.get(deviceIndex).ensureLoaded();
            final long t1 = System.nanoTime();
            debug("loaded in %.9f s", (t1 - t0) * 1e-9);
            op = buffer.get();
        }

        constants = graphContext.getConstants();
        tasks = graphContext.getTasks();

        debug("%s - vm ready to go", graphContext.getId());
        buffer.mark();
    }

    private GlobalObjectState resolveGlobalObjectState(int index) {
        return globalStates[index];
    }

    private DeviceObjectState resolveObjectState(int index, int device) {
        return globalStates[index].getDeviceState(contexts.get(device));
    }

    private CallStack resolveStack(int index, int numArgs, CallStack[] stacks, TornadoAcceleratorDevice device, boolean setNewDevice) {
        if (graphContext.meta().isDebug() && setNewDevice) {
            debug("Recompiling task on device " + device);
        }
        if (stacks[index] == null || setNewDevice) {
            stacks[index] = device.createStack(numArgs);
        }
        return stacks[index];
    }

    public void invalidateObjects() {
        for (GlobalObjectState globalState : globalStates) {
            globalState.invalidate();
        }
    }

    public void warmup() {
        execute(true);
        finishedWarmup = true;
    }

    public void compile() {
        execute(true);
    }

    public Event execute() {
        return execute(false);
    }

    private final String MESSAGE_ERROR = "object is not valid: %s %s";

    private void initWaitEventList() {
        for (int[] waitList : events) {
            Arrays.fill(waitList, -1);
        }
    }

    private Event execute(boolean isWarmup) {

        final long t0 = System.nanoTime();
        int lastEvent = -1;
        initWaitEventList();

        StringBuilder bytecodesList = null;
        if (TornadoOptions.printBytecodes) {
            bytecodesList = new StringBuilder();
        }

        while (buffer.hasRemaining()) {
            final byte op = buffer.get();
            if (op == TornadoVMBytecodes.ALLOCATE.value()) {
                final int objectIndex = buffer.getInt();
                final int contextIndex = buffer.getInt();
                final long sizeBatch = buffer.getLong();

                if (isWarmup) {
                    continue;
                }

                final TornadoAcceleratorDevice device = contexts.get(contextIndex);
                final Object object = objects.get(objectIndex);

                if (TornadoOptions.printBytecodes) {
                    String verbose = String.format("vm: ALLOCATE [0x%x] %s on %s, size=%d", object.hashCode(), object, device, sizeBatch);
                    bytecodesList.append(verbose + "\n");
                }

                final DeviceObjectState objectState = resolveObjectState(objectIndex, contextIndex);
                FlinkData finfo = graphContext.getFinfo();
                if (finfo != null && !finfo.isReduction()) {
                    Object ob = finfo.getByteResults();
                    lastEvent = device.ensureAllocated(ob, sizeBatch, objectState);
                } else {
                    lastEvent = device.ensureAllocated(object, sizeBatch, objectState);
                }

            } else if (op == TornadoVMBytecodes.COPY_IN.value()) {
                final int objectIndex = buffer.getInt();
                final int contextIndex = buffer.getInt();
                final int eventList = buffer.getInt();
                final long offset = buffer.getLong();
                final long sizeBatch = buffer.getLong();

                final int[] waitList = (useDependencies && eventList != -1) ? events[eventList] : null;

                if (isWarmup) {
                    continue;
                }

                final TornadoAcceleratorDevice device = contexts.get(contextIndex);
                final Object object = objects.get(objectIndex);

                FlinkData finfo = graphContext.getFinfo();

                final DeviceObjectState objectState = resolveObjectState(objectIndex, contextIndex);

                if (TornadoOptions.printBytecodes) {
                    String verbose = String.format("vm: COPY_IN [Object Hash Code=0x%x] %s on %s, size=%d, offset=%d [event list=%d]", object.hashCode(), object, device, sizeBatch, offset, eventList);
                    bytecodesList.append(verbose + "\n");
                }

                List<Integer> allEvents;

                if (sizeBatch > 0) {
                    // We need to stream-in when using batches, because the
                    // whole data is not copied yet.
                    if (finfo != null) {
                        if (finfo.isReduction()) {
                            if (objectIndex == 0) {
                                Object ob = finfo.getFirstByteDataSet();
                                allEvents = device.streamIn(ob, sizeBatch, offset, objectState, waitList);
                            } else if (objectIndex == 1) {
                                Object ob = finfo.getSecondByteDataSet();
                                allEvents = device.streamIn(ob, sizeBatch, offset, objectState, waitList);
                            } else if (objectIndex == 2) {
                                Object ob = finfo.getThirdByteDataSet();
                                allEvents = device.streamIn(ob, sizeBatch, offset, objectState, waitList);
                            } else if (objectIndex == 3) {
                                Object ob = finfo.getFourthByteDataSet();
                                allEvents = device.streamIn(ob, sizeBatch, offset, objectState, waitList);
                            } else {
                                allEvents = device.streamIn(object, sizeBatch, offset, objectState, waitList);
                            }
                        } else {
                            if (objectIndex == 1) {
                                Object ob = finfo.getFirstByteDataSet();
                                allEvents = device.streamIn(ob, sizeBatch, offset, objectState, waitList);
                            } else if (objectIndex == 2) {
                                Object ob = finfo.getSecondByteDataSet();
                                allEvents = device.streamIn(ob, sizeBatch, offset, objectState, waitList);
                            } else {
                                allEvents = device.streamIn(object, sizeBatch, offset, objectState, waitList);
                            }
                        }
                    } else {
                        allEvents = device.streamIn(object, sizeBatch, offset, objectState, waitList);
                    }
                } else {
                    if (finfo != null) {
                        if (finfo.isReduction()) {
                            if (objectIndex == 0) {
                                Object ob = finfo.getFirstByteDataSet();
                                allEvents = device.ensurePresent(ob, objectState, waitList, sizeBatch, offset);
                            } else if (objectIndex == 1) {
                                Object ob = finfo.getSecondByteDataSet();
                                allEvents = device.ensurePresent(ob, objectState, waitList, sizeBatch, offset);
                            } else if (objectIndex == 2) {
                                Object ob = finfo.getThirdByteDataSet();
                                allEvents = device.ensurePresent(ob, objectState, waitList, sizeBatch, offset);
                            } else if (objectIndex == 3) {
                                Object ob = finfo.getFourthByteDataSet();
                                allEvents = device.ensurePresent(ob, objectState, waitList, sizeBatch, offset);
                            } else {
                                allEvents = device.ensurePresent(object, objectState, waitList, sizeBatch, offset);
                            }
                        } else {
                            if (objectIndex == 1) {
                                Object ob = finfo.getFirstByteDataSet();
                                allEvents = device.ensurePresent(ob, objectState, waitList, sizeBatch, offset);
                            } else if (objectIndex == 2) {
                                Object ob = finfo.getSecondByteDataSet();
                                if (ob != null) {
                                    allEvents = device.ensurePresent(ob, objectState, waitList, sizeBatch, offset);
                                } else {
                                    ob = finfo.getByteResults();
                                    allEvents = device.ensurePresent(ob, objectState, waitList, sizeBatch, offset);
                                }
                            } else if (objectIndex == 3) {
                                // streamout array
                                Object ob = finfo.getByteResults();
                                allEvents = device.ensurePresent(ob, objectState, waitList, sizeBatch, offset);
                            } else {
                                allEvents = device.ensurePresent(object, objectState, waitList, sizeBatch, offset);
                            }
                        }
                    } else {
                        allEvents = device.ensurePresent(object, objectState, waitList, sizeBatch, offset);
                    }
                }
                if (eventList != -1) {
                    eventsIndicies[eventList] = 0;
                }

                if (TornadoOptions.isProfilerEnabled() && allEvents != null) {
                    for (Integer e : allEvents) {
                        Event event = device.resolveEvent(e);
                        event.waitForEvents();
                        long value = timeProfiler.getTimer(ProfilerType.COPY_IN_TIME);
                        value += event.getExecutionTime();
                        timeProfiler.setTimer(ProfilerType.COPY_IN_TIME, value);
                    }
                }

            } else if (op == TornadoVMBytecodes.STREAM_IN.value()) {
                final int objectIndex = buffer.getInt();
                final int contextIndex = buffer.getInt();
                final int eventList = buffer.getInt();
                final long offset = buffer.getLong();
                final long sizeBatch = buffer.getLong();

                final int[] waitList = (useDependencies && eventList != -1) ? events[eventList] : null;

                if (isWarmup) {
                    continue;
                }

                final TornadoAcceleratorDevice device = contexts.get(contextIndex);
                final Object object = objects.get(objectIndex);

                if (TornadoOptions.printBytecodes) {
                    String verbose = String.format("vm: STREAM_IN [0x%x] %s on %s, size=%d, offset=%d [event list=%d]", object.hashCode(), object, device, sizeBatch, offset, eventList);
                    bytecodesList.append(verbose + "\n");
                }

                final DeviceObjectState objectState = resolveObjectState(objectIndex, contextIndex);

                List<Integer> allEvents = device.streamIn(object, sizeBatch, offset, objectState, waitList);
                if (eventList != -1) {
                    eventsIndicies[eventList] = 0;
                }
                if (TornadoOptions.isProfilerEnabled() && allEvents != null) {
                    for (Integer e : allEvents) {
                        Event event = device.resolveEvent(e);
                        event.waitForEvents();
                        long value = timeProfiler.getTimer(ProfilerType.COPY_IN_TIME);
                        value += event.getExecutionTime();
                        timeProfiler.setTimer(ProfilerType.COPY_IN_TIME, value);
                    }
                }

            } else if (op == TornadoVMBytecodes.STREAM_OUT.value()) {
                final int objectIndex = buffer.getInt();
                final int contextIndex = buffer.getInt();
                final int eventList = buffer.getInt();

                final long offset = buffer.getLong();
                final long sizeBatch = buffer.getLong();

                final int[] waitList = (useDependencies) ? events[eventList] : null;

                if (isWarmup) {
                    continue;
                }

                final TornadoAcceleratorDevice device = contexts.get(contextIndex);
                final Object object = objects.get(objectIndex);

                if (TornadoOptions.printBytecodes) {
                    String verbose = String.format("vm: STREAM_OUT [0x%x] %s on %s, size=%d, offset=%d [event list=%d]", object.hashCode(), object, device, sizeBatch, offset, eventList);
                    bytecodesList.append(verbose + "\n");
                }

                final DeviceObjectState objectState = resolveObjectState(objectIndex, contextIndex);

                lastEvent = device.streamOutBlocking(object, offset, objectState, waitList);
                if (eventList != -1) {
                    eventsIndicies[eventList] = 0;
                }
                if (TornadoOptions.isProfilerEnabled() && lastEvent != -1) {
                    Event event = device.resolveEvent(lastEvent);
                    event.waitForEvents();
                    long value = timeProfiler.getTimer(ProfilerType.COPY_OUT_TIME);
                    value += event.getExecutionTime();
                    timeProfiler.setTimer(ProfilerType.COPY_OUT_TIME, value);
                }

            } else if (op == TornadoVMBytecodes.STREAM_OUT_BLOCKING.value()) {
                final int objectIndex = buffer.getInt();
                final int contextIndex = buffer.getInt();
                final int eventList = buffer.getInt();

                final long offset = buffer.getLong();
                final long sizeBatch = buffer.getLong();

                final int[] waitList = (useDependencies) ? events[eventList] : null;

                if (isWarmup) {
                    continue;
                }

                final TornadoAcceleratorDevice device = contexts.get(contextIndex);
                final Object object = objects.get(objectIndex);

                if (TornadoOptions.printBytecodes) {
                    String verbose = String.format("vm: STREAM_OUT_BLOCKING [0x%x] %s on %s, size=%d, offset=%d [event list=%d]", object.hashCode(), object, device, sizeBatch, offset, eventList);
                    bytecodesList.append(verbose + "\n");
                }

                final DeviceObjectState objectState = resolveObjectState(objectIndex, contextIndex);

                FlinkData finfo = graphContext.getFinfo();

                final int tornadoEventID;

                if (finfo != null && !finfo.isReduction()) {
                    Object ob = finfo.getByteResults();
                    tornadoEventID = device.streamOutBlocking(ob, offset, objectState, waitList);
                } else {
                    tornadoEventID = device.streamOutBlocking(object, offset, objectState, waitList);
                }

                if (TornadoOptions.isProfilerEnabled() && tornadoEventID != -1) {
                    Event event = device.resolveEvent(tornadoEventID);
                    event.waitForEvents();
                    long value = timeProfiler.getTimer(ProfilerType.COPY_OUT_TIME);
                    value += event.getExecutionTime();
                    timeProfiler.setTimer(ProfilerType.COPY_OUT_TIME, value);
                }

                if (eventList != -1) {
                    eventsIndicies[eventList] = 0;
                }

            } else if (op == TornadoVMBytecodes.LAUNCH.value()) {
                final int stackIndex = buffer.getInt();
                final int contextIndex = buffer.getInt();
                final int taskIndex = buffer.getInt();
                final int numArgs = buffer.getInt();
                final int eventList = buffer.getInt();

                final long offset = buffer.getLong();
                final long batchThreads = buffer.getLong();

                final TornadoAcceleratorDevice device = contexts.get(contextIndex);

                if (device.getDeviceContext().wasReset() && finishedWarmup) {
                    throw new TornadoFailureException("[ERROR] reset() was called after warmup()");
                }

                boolean redeployOnDevice = graphContext.redeployOnDevice();

                final CallStack stack = resolveStack(stackIndex, numArgs, stacks, device, redeployOnDevice);

                final int[] waitList = (useDependencies && eventList != -1) ? events[eventList] : null;
                final SchedulableTask task = tasks.get(taskIndex);

                // Set the batch size in the task information
                task.setBatchThreads(batchThreads);

                if (TornadoOptions.printBytecodes) {
                    String verbose = String.format("vm: LAUNCH %s on %s, size=%d, offset=%d [event list=%d]", task.getName(), contexts.get(contextIndex), batchThreads, offset, eventList);
                    bytecodesList.append(verbose + "\n");
                }

                if (installedCodes[taskIndex] == null) {
                    // System.out.println("- installedCodes is null (first check)");
                    task.mapTo(device);
                    try {
                        task.attachProfiler(timeProfiler);
                        if (taskIndex == (tasks.size() - 1)) {
                            // If last task within the task-schedule -> we force compilation
                            // This is useful when compiling code for Xilinx/Altera FPGAs, that has to
                            // be a single source
                            task.forceCompilation();
                        }
                        installedCodes[taskIndex] = device.installCode(task);
                    } catch (Error | Exception e) {
                        fatal("unable to compile task %s", task.getName());
                    }
                    // System.out.println("- installedCodes try-catch successful: " +
                    // installedCodes[taskIndex]);
                }

                if (isWarmup) {
                    popArgumentsFromStack(numArgs);
                    continue;
                }

                if (installedCodes[taskIndex] == null) {
                    // System.out.println("- installedCodes is null (second check)");
                    // After warming-up, it is possible to get a null pointer in the task-cache due
                    // to lazy compilation for FPGAs. In tha case, we check again the code cache.
                    installedCodes[taskIndex] = device.getCodeFromCache(task);
                    // System.out.println("- installedCodes from cache: " +
                    // installedCodes[taskIndex]);
                }

                final TornadoInstalledCode installedCode = installedCodes[taskIndex];
                // System.out.println("- installedCode variable: " + installedCode);
                final Access[] accesses = task.getArgumentsAccess();

                if (redeployOnDevice || !stack.isOnDevice()) {
                    stack.reset();
                }
                for (int i = 0; i < numArgs; i++) {
                    final byte argType = buffer.get();
                    final int argIndex = buffer.getInt();

                    if (stack.isOnDevice()) {
                        continue;
                    }

                    if (argType == TornadoVMBytecodes.CONSTANT_ARGUMENT.value()) {
                        stack.push(constants.get(argIndex));
                    } else if (argType == TornadoVMBytecodes.REFERENCE_ARGUMENT.value()) {
                        final GlobalObjectState globalState = resolveGlobalObjectState(argIndex);
                        final DeviceObjectState objectState = globalState.getDeviceState(contexts.get(contextIndex));

                        TornadoInternalError.guarantee(objectState.isValid(), MESSAGE_ERROR, objects.get(argIndex), objectState);

                        stack.push(objects.get(argIndex), objectState);
                        System.out.print("------- Object " + objects.get(argIndex) + " access: ");
                        if (accesses[i] == Access.WRITE) {
                            System.out.println("WRITE");
                        } else if (accesses[i] == Access.READ) {
                            System.out.println("WRITE");
                        } else if (accesses[i] == Access.READ_WRITE) {
                            System.out.println("READ_WRITE");
                        } else if (accesses[i] == Access.NONE) {
                            System.out.println("NONE");
                        } else if (accesses[i] == Access.UNKNOWN) {
                            System.out.println("UNKNOWN");
                        }
                        if (accesses[i] == Access.WRITE || accesses[i] == Access.READ_WRITE) {
                            globalState.setOwner(device);
                            objectState.setContents(true);
                            objectState.setModified(true);
                        }
                    } else {
                        TornadoInternalError.shouldNotReachHere();
                    }
                }

                TaskMetaData metadata = null;
                if (task.meta() instanceof TaskMetaData) {
                    metadata = (TaskMetaData) task.meta();
                } else {
                    throw new RuntimeException("task.meta is not instanceof TaskMetada");
                }

                // We attach the profiler
                metadata.attachProfiler(timeProfiler);
                byte[] b = stack.getBuffer().array();
                System.out.println("-------- stack contents: ");
                for (int i = 0; i < b.length; i++) {
                    System.out.print(b[i] + " ");
                }
                System.out.println();
                if (useDependencies) {
                    lastEvent = installedCode.launchWithDeps(stack, metadata, batchThreads, waitList);
                } else {
                    // System.out.println("- installedCode: " + installedCode);
                    // System.out.println("- stack: " + stack);
                    // System.out.println("- metadata: " + metadata);
                    // System.out.println("- batchThreads: " + batchThreads);
                    lastEvent = installedCode.launchWithoutDeps(stack, metadata, batchThreads);
                }
                if (eventList != -1) {
                    eventsIndicies[eventList] = 0;
                }
            } else if (op == TornadoVMBytecodes.ADD_DEP.value()) {
                final int eventList = buffer.getInt();
                if (isWarmup) {
                    continue;
                }
                if (useDependencies && lastEvent != -1) {

                    if (TornadoOptions.printBytecodes) {
                        String verbose = String.format("vm: ADD_DEP %s to event list %d", lastEvent, eventList);
                        bytecodesList.append(verbose + "\n");
                    }

                    TornadoInternalError.guarantee(eventsIndicies[eventList] < events[eventList].length, "event list is too small");
                    events[eventList][eventsIndicies[eventList]] = lastEvent;
                    eventsIndicies[eventList]++;
                }

            } else if (op == TornadoVMBytecodes.BARRIER.value()) {
                final int eventList = buffer.getInt();
                final int[] waitList = (useDependencies && eventList != -1) ? events[eventList] : null;

                if (isWarmup) {
                    continue;
                }

                if (TornadoOptions.printBytecodes) {
                    bytecodesList.append(String.format("BARRIER event list %d\n", eventList));
                }

                if (contexts.size() == 1) {
                    final TornadoAcceleratorDevice device = contexts.get(0);
                    lastEvent = device.enqueueMarker(waitList);
                } else if (contexts.size() > 1) {
                    TornadoInternalError.shouldNotReachHere("unimplemented multi-context barrier");
                }

                if (eventList != -1) {
                    eventsIndicies[eventList] = 0;
                }
            } else if (op == TornadoVMBytecodes.END.value()) {
                if (TornadoOptions.printBytecodes) {
                    bytecodesList.append(String.format("END\n"));
                }

                break;
            } else {
                if (graphContext.meta().isDebug()) {
                    debug("vm: invalid op 0x%x(%d)", op, op);
                }
                throw new TornadoRuntimeException("[ERROR] TornadoVM Bytecode not recognized");
            }
        }

        Event barrier = EMPTY_EVENT;
        if (!isWarmup) {
            for (TornadoAcceleratorDevice dev : contexts) {
                if (useDependencies) {
                    final int event = dev.enqueueMarker();
                    barrier = dev.resolveEvent(event);
                }

                if (USE_VM_FLUSH) {
                    dev.flush();
                }
            }
        }

        final long t1 = System.nanoTime();
        final double elapsed = (t1 - t0) * 1e-9;
        if (!isWarmup) {
            totalTime += elapsed;
            invocations++;
        }

        if (graphContext.meta().isDebug()) {
            debug("vm: complete elapsed=%.9f s (%d iterations, %.9f s mean)", elapsed, invocations, (totalTime / invocations));
        }

        buffer.reset();

        if (TornadoOptions.printBytecodes) {
            System.out.println(bytecodesList.toString());
            bytecodesList = null;
        }

        return barrier;
    }

    private void popArgumentsFromStack(int numArgs) {
        for (int i = 0; i < numArgs; i++) {
            buffer.get();
            buffer.getInt();
        }
    }

    public void printTimes() {
        System.out.printf("vm: complete %d iterations - %.9f s mean and %.9f s total\n", invocations, (totalTime / invocations), totalTime);
    }

    public void clearProfiles() {
        for (final SchedulableTask task : tasks) {
            task.meta().getProfiles().clear();
        }
    }

    public void dumpEvents() {
        if (!ENABLE_PROFILING || !graphContext.meta().shouldDumpEvents()) {
            info("profiling and/or event dumping is not enabled");
            return;
        }

        for (final TornadoAcceleratorDevice device : contexts) {
            device.dumpEvents();
        }
    }

    public void dumpProfiles() {
        if (!graphContext.meta().shouldDumpProfiles()) {
            info("profiling is not enabled");
            return;
        }

        for (final SchedulableTask task : tasks) {
            final TaskMetaData meta = (TaskMetaData) task.meta();
            for (final TornadoEvents eventset : meta.getProfiles()) {
                final BitSet profiles = eventset.getProfiles();
                for (int i = profiles.nextSetBit(0); i != -1; i = profiles.nextSetBit(i + 1)) {

                    if (!(eventset.getDevice() instanceof TornadoAcceleratorDevice)) {
                        throw new RuntimeException("TornadoDevice not found");
                    }

                    TornadoAcceleratorDevice device = (TornadoAcceleratorDevice) eventset.getDevice();
                    final Event profile = device.resolveEvent(i);
                    if (profile.getStatus() == COMPLETE) {
                        System.out.printf("task: %s %s %9d %9d %9d %9d\n", device.getDeviceName(), meta.getId(), profile.getExecutionTime(), profile.getSubmitTime(), profile.getStartTime(),
                                profile.getEndTime());
                    }
                }
            }
        }
    }

}
