# Installing TornadoVM with JDK 8

**Pre-requisites**

  * Maven Version 3.6.3
  * CMake 3.6 (or newer)
  * At least one of:   
    * OpenCL: GPUs and CPUs >= 1.2, FPGAs >= 1.0
    * CUDA 9.0 +
  * GCC or clang/LLVM (GCC >= 5.5)
  * Python 2.7 (>= 2.7.5)
  * JDK 8 >= 1.8.0_141


  For Mac OS X users: the OpenCL support for your Apple model can be confirmed [here](https://support.apple.com/en-gb/HT202823).

### 1. Compile JDK 8 with JVMCI-8 support
TornadoVM is built by using a JDK 1.8 version with JVMCI-8 support. The directory which contains the Java binary is used as both the JAVA_HOME (Step 2) and the JVMCI root path (Step 3).

Before building the new JDK, the JAVA_HOME environment variable should point to an already existing installation of a JDK 8 >= 1.8.0_141.

```bash
 $ git clone --depth 1 https://github.com/beehive-lab/mx
 $ export PATH=`pwd`/mx:$PATH
 $ git clone --depth 1 https://github.com/beehive-lab/graal-jvmci-8
 $ cd graal-jvmci-8
 $ mx build
```

These steps will generate on Linux a new Java binary into `jdk1.8.0_<your_version>/<os-architecture>/product` and `jdk1.8.0_<your_version>/<os-architecture>/product/Contents/Home` for MacOS.

E.g: `jdk1.8.0_131/product`. This directory is used as the JAVA_HOME (Step 2).

### 2. Download TornadoVM

```bash
 $ cd ..
 $ git clone https://github.com/beehive-lab/TornadoVM tornadovm
 $ cd tornadovm
 $ vim etc/sources.env
```

Create the `etc/sources.env` file and add the following code in it **(after updating the paths to your correct ones)**:

```bash
#!/bin/bash
export JAVA_HOME=<path to 1.8 jdk with JVMCI> ## This path is produced in Step 1
export PATH=$PWD/bin/bin:$PATH    ## This directory will be automatically generated during Tornado compilation
export TORNADO_SDK=$PWD/bin/sdk   ## This directory will be automatically generated during Tornado compilation
export CMAKE_ROOT=/usr            ## or <path/to/cmake/cmake-3.10.2> (see step 4)
```

This file should be loaded once after opening the command prompt for the setup of the required paths:

```bash
$ source ./etc/sources.env
```

### 3. Install CMAKE (if cmake < 3.6)

```
$ cmake -version
```

**If the version of cmake is > 3.6 then skip the rest of this step and go to Step 5.**
Otherwise try to install cmake.

For simplicity it might be easier to install cmake in your home directory.
  * Redhat Enterprise Linux / CentOS use cmake v2.8
  * We require a newer version so that OpenCL is configured properly.

```bash
$ cd ~/Downloads
$ wget https://cmake.org/files/v3.10/cmake-3.10.1-Linux-x86_64.tar.gz
$ cd ~/opt
$ tar -tvf ~/Downloads/cmake-3.10.1-Linux-x86_64.tar.gz
$ mv cmake-3.10.1-Linux-x86_64 cmake-3.10.1
$ export PATH=$HOME/opt/cmake-3.10.1/bin/:$PATH
$ cmake -version
cmake version 3.10.1
```

Then export `CMAKE_ROOT` variable to the cmake installation. You can add it to the `./etc/sources.env` file.

```bash
export CMAKE_ROOT=/opt/cmake-3.10.1
```

### 4. Compile TornadoVM

**Important:** The build process in TornadoVM will override the file in `~/.m2/settings.xml`.

```bash
$ cd ~/tornadovm
$ . etc/sources.env
$ make BACKEND={ptx,opencl}
```
and done!!

## Known issues

### For Ubuntu >= 16.04, install the package  `ocl-icd-opencl-dev`

In Ubuntu >= 16.04 CMake can cause the following error:  Could NOT find OpenCL (missing: OpenCL_LIBRARY) (found version "2.2"). Then the following package should be installed:

```bash
$ apt-get install ocl-icd-opencl-dev
```
