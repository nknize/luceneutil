#!/bin/bash

java -cp /l/lucene/build/backward-codecs/classes/java:/l/lucene/build/core/classes/java:/l/lucene/build/sandbox/classes/java:/l/lucene/build/spatial/classes/java:/l/lucene/build/spatial3d/classes/java:/l/lucene/build/spatial-extras/classes/java:/l/lucene/spatial-extras/lib/spatial4j-0.7.jar:/l/lucene/spatial-extras/lib/jts-core-1.15.0.jar:src/main perf.IndexAndSearchOSMShapes -geohash -polyRussia -term 
