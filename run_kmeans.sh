#!/bin/bash 

for size in 128 256 512 1024 2048 4096 8192 16384 32768 65536 131072 262144 524288 1048576 2097152
do
        echo "Run Kmeans Tornado with input size "$size
        tornado uk.ac.manchester.tornado.examples.TestFlink $size "/home/maryxek/datasets/"$size"_2.txt" 
done
