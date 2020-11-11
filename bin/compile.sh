#!/usr/bin/env bash

# Parameters passed to this script:
# $1 - which JDK is used to build Tornado { jdk-8, graal-jdk-8, graal-jdk-11, jdk-11-plus }
# $2 - backends selected for the build. Can be any combination of { opencl, ptx }
# $3 - build Tornado OFFLINE

JAVA_CMD=${JAVA_HOME}/bin/java
JAVA_VERSION_OUTPUT=$("$JAVA_CMD" -version 2>&1)
JAVA_VERSION=$(echo "$JAVA_VERSION_OUTPUT" | awk -F[\"\.] -v OFS=. 'NR==1{print $2,$3}')

# If we have a JDK 11+ version that is not a GraalVM build, then we need to make sure we have the graal jars available
if [[ ! $JAVA_VERSION == "1.8" && ! $JAVA_VERSION_OUTPUT == *"GraalVM"* ]]; then
  bash ./bin/pullGraalJars.sh
fi

python scripts/updateMavenSettings.py
if [ $? -eq 1 ]; then
  exit 1
fi
mvn -Popencl-backend,ptx-backend clean

# The maven profiles of each backend use the naming {ptx,opencl}-backend
selected_backends=''
IFS=',' read -ra selected_backends_list <<< "$2"
for ((i=0;i<${#selected_backends_list[@]};i++)); do
    selected_backends_list[i]="${selected_backends_list[i]}-backend"
    if ((i!=${#selected_backends_list[@]}-1)); then
      selected_backends_list[i]="${selected_backends_list[i]},"
    fi
    selected_backends=${selected_backends}${selected_backends_list[i]}
done

if [[ $3 == "OFFLINE" ]]; then
  mvn -T1.5C -o -Dcmake.root.dir=$CMAKE_ROOT -P$1,${selected_backends} install
else
  mvn -T1.5C -Dcmake.root.dir=$CMAKE_ROOT -P$1,${selected_backends} install
fi

if [ $? -eq 0 ]; then
  ## Update the PATH if the compilation is correct
  bash ./bin/updatePATHS.sh

  ## Update the compiled backends file
  echo "tornado.backends="${selected_backends} > ${TORNADO_SDK}/etc/tornado.backend

  # Place the graal jars in our distribution if we have a JDK 11+ version that is not a GraalVM build
  if [[ ! $JAVA_VERSION == "1.8" && ! $JAVA_VERSION_OUTPUT == *"GraalVM"* ]]; then
    mkdir -p $TORNADO_SDK/share/java/graalJars
    cp $PWD/graalJars/* $TORNADO_SDK/share/java/graalJars/
  fi

else
  echo -e "\n \e[91mCompilation failed\e[39m \n"
fi
