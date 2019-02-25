package perf;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.PointsFormat;
import org.apache.lucene.codecs.PointsReader;
import org.apache.lucene.codecs.PointsWriter;
import org.apache.lucene.codecs.lucene60.Lucene60PointsReader;
import org.apache.lucene.codecs.lucene60.Lucene60PointsWriter;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.geo.*;
//import org.apache.lucene.geo.EarthDebugger;
import org.apache.lucene.index.CodecReader;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.LogDocMergePolicy;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.SerialMergeScheduler;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.lucene.spatial.composite.CompositeSpatialStrategy;
import org.apache.lucene.spatial.prefix.RecursivePrefixTreeStrategy;
import org.apache.lucene.spatial.prefix.TermQueryPrefixTreeStrategy;
import org.apache.lucene.spatial.prefix.tree.GeohashPrefixTree;
import org.apache.lucene.spatial.prefix.tree.PackedQuadPrefixTree;
import org.apache.lucene.spatial.prefix.tree.QuadPrefixTree;
import org.apache.lucene.spatial.prefix.tree.SpatialPrefixTree;
import org.apache.lucene.spatial.query.SpatialArgs;
import org.apache.lucene.spatial.query.SpatialOperation;
import org.apache.lucene.spatial.serialized.SerializedDVStrategy;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.PrintStreamInfoStream;
import org.apache.lucene.util.SloppyMath;
import org.apache.lucene.util.bkd.BKDWriter;

import org.apache.lucene.document.LatLonShape;
import org.apache.lucene.document.LatLonShape.QueryRelation;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.spatial4j.context.SpatialContext;
import org.locationtech.spatial4j.context.jts.JtsSpatialContext;
import org.locationtech.spatial4j.shape.Shape;
import org.locationtech.spatial4j.shape.jts.JtsGeometry;


// STEPS:
//
//   * Download all files from http://home.apache.org/~mikemccand/geobench and place in a local directory ... this is "all points inside London, UK"
//     plus "2.5 % of all points around the world" from the ~September 2015 export of OpenStreetMaps corpus, plus two polygon files
//
//   * Uncompress it (lzma -d /path/to/...) (1.9 GB)
//
//   * cd /path/to/lucene, and go into lucene subdir and run "ant jar"
//
//   * tweak the commands below to match your classpath, then run with options like "-geo3d -poly 100 -reindex"

// javac -cp build/core/classes/java:build/sandbox/classes/java /l/util/src/main/perf/IndexAndSearchOpenStreetMaps.java /l/util/src/main/perf/RandomQuery.java; java -cp /l/util/src/main:build/core/classes/java:build/sandbox/classes/java perf.IndexAndSearchOpenStreetMaps

// rmuir@beast:~/workspace/util$ javac -cp /home/rmuir/workspace/lucene-solr/lucene/build/core/classes/java:/home/rmuir/workspace/lucene-solr/lucene/build/sandbox/classes/java:/home/rmuir/workspace/lucene-solr/lucene/build/spatial/classes/java:/home/rmuir/workspace/lucene-solr/lucene/build/spatial3d/classes/java src/main/perf/IndexAndSearchOpenStreetMaps.java src/main/perf/RandomQuery.java
// rmuir@beast:~/workspace/util$ java -cp /home/rmuir/workspace/lucene-solr/lucene/build/core/classes/java:/home/rmuir/workspace/lucene-solr/lucene/build/sandbox/classes/java:/home/rmuir/workspace/lucene-solr/lucene/build/spatial/classes/java:/home/rmuir/workspace/lucene-solr/lucene/build/spatial3d/classes/java:src/main perf.IndexAndSearchOpenStreetMaps

// convert geojson to poly file src/python/geoJSONToJava.py
//
// e.g. use -polyFile /l/util/src/python/countries.geojson.out.txt.gz


public class IndexAndSearchOSMShapes {

    private static final int ITERS = 1;
    private static final String SHAPE_FIELDNAME = "shapes";

    /** prefix for indexes that will be built */
    static final String INDEX_LOCATION;
    /** prefix for data files such as latlon.subsetPlusAllLondon.txt */
    static final String DATA_LOCATION;
    static {
        switch (System.getProperty("user.name")) {
            case "mike":
                INDEX_LOCATION = "/b/osm";
                DATA_LOCATION = "/lucenedata/open-street-maps/";
                break;
            case "rmuir":
                INDEX_LOCATION = "/data/bkdtest";
                DATA_LOCATION = "/data/";
                break;
            case "kawright":
                INDEX_LOCATION = "c:\\data\\bkdtest";
                DATA_LOCATION = "c:\\data\\";
                break;
            case "jpountz":
                INDEX_LOCATION = "/data/lucene/bkdtest";
                DATA_LOCATION = "/data/lucene/data";
                break;
            case "ivera":
                INDEX_LOCATION = "/data/bkdtest";
                DATA_LOCATION = "/data/";
                break;
            case "nknize":
                INDEX_LOCATION = "/data/bkdtest/";
                DATA_LOCATION = "/data/";
                break;
            default:
                throw new UnsupportedOperationException("the benchmark does not know you, "+System.getProperty("user.name")+". please introduce yourself to the code and push");
        }
    }

    static boolean usePrefixTrees = false;
    static boolean useLatLonShape = false;
    static boolean SMALL = true;
    static int NUM_PARTS;
    static PrefixTree prefixTree;

    private static String getName(int part) {
        String name = INDEX_LOCATION + part;
        if (usePrefixTrees) {
            if (prefixTree.grid instanceof PackedQuadPrefixTree) {
                name += ".packedquadtree";
            } else if (prefixTree.grid instanceof QuadPrefixTree) {
                name += ".quadtree";
            } else if (prefixTree.grid instanceof GeohashPrefixTree) {
                name += ".geohash";
            }

            if (prefixTree.strategy instanceof RecursivePrefixTreeStrategy) {
                name += ".rpt";
            } else if (prefixTree.strategy instanceof TermQueryPrefixTreeStrategy) {
                name += ".term";
            } else if (prefixTree.strategy instanceof CompositeSpatialStrategy) {
                name += ".composite";
            }
        } else if (useLatLonShape) {
            name += ".latlonshape";
        }  else {
            throw new AssertionError();
        }
        if (SMALL) {
            name += ".small";
        } else {
            name += ".large";
        }
        return name;
    }

    /** Only used to compute bbox for all polygons loaded from the -polyFile */
    private static double minLat = Double.POSITIVE_INFINITY;
    private static double maxLat = Double.NEGATIVE_INFINITY;
    private static double minLon = Double.POSITIVE_INFINITY;
    private static double maxLon = Double.NEGATIVE_INFINITY;

    // NOTE: use geoJSONToJava.py to convert the geojson file to simple text file:
    private static List<Polygon[]> readPolygons(String fileName) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        int BUFFER_SIZE = 1 << 16;     // 64K
        InputStream is = Files.newInputStream(Paths.get(fileName));
        if (fileName.endsWith(".gz")) {
            is = new GZIPInputStream(is);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, decoder), BUFFER_SIZE);
        List<Polygon[]> result = new ArrayList<>();
        //EarthDebugger earth = new EarthDebugger(51.45677607571096, 0.13580354718348125, 100000.0);
        int totalVertexCount = 0;

        while (true) {
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            if (line.startsWith("count=") == false) {
                throw new AssertionError();
            }
            // Count is the number of polygons the query has:
            int count = Integer.parseInt(line.substring(6, line.indexOf(' ')));
            List<Polygon> polys = new ArrayList<>();
            for(int i=0;i<count;i++) {
                line = reader.readLine();
                // How many polygons (if this is > 1, the first poly is the real one, and
                // all others are hole-polys that are subtracted):
                if (line.startsWith("  poly count=") == false) {
                    throw new AssertionError();
                }
                int polyCount = Integer.parseInt(line.substring(13));
                List<Polygon> polyPlusHoles = new ArrayList<>();
                double sumLat = 0.0;
                double sumLon = 0.0;
                for(int j=0;j<polyCount;j++) {
                    line = reader.readLine();
                    if (line.startsWith("    vertex count=") == false) {
                        throw new AssertionError();
                    }

                    int vertexCount = Integer.parseInt(line.substring(17));
                    double[] lats = new double[vertexCount];
                    double[] lons = new double[vertexCount];
                    line = reader.readLine();
                    if (line.startsWith("      lats ") == false) {
                        throw new AssertionError();
                    }
                    String[] parts = line.substring(11).split(" ");
                    if (parts.length != vertexCount) {
                        throw new AssertionError();
                    }
                    for(int k=0;k<vertexCount;k++) {
                        lats[k] = Double.parseDouble(parts[k]);
                        sumLat += lats[k];
                        minLat = Math.min(minLat, lats[k]);
                        maxLat = Math.max(maxLat, lats[k]);
                    }

                    line = reader.readLine();
                    if (line.startsWith("      lons ") == false) {
                        throw new AssertionError();
                    }
                    parts = line.substring(11).split(" ");
                    if (parts.length != vertexCount) {
                        throw new AssertionError();
                    }
                    for(int k=0;k<vertexCount;k++) {
                        lons[k] = Double.parseDouble(parts[k]);
                        sumLon += lons[k];
                        minLon = Math.min(minLon, lons[k]);
                        maxLon = Math.max(maxLon, lons[k]);
                    }

                    //System.out.println("check poly");

          /*
          int dupCount = 0;
          for(int k=1;k<vertexCount;k++) {
            if (lats[k] == lats[k-1] && lons[k] == lons[k-1]) {
              dupCount++;
            }
          }
          if (dupCount != 0) {
            System.out.println("  dedup to remove " + dupCount + " duplicate points");
            double[] newLats = new double[lats.length-dupCount];
            double[] newLons = new double[lons.length-dupCount];
            newLats[0] = lats[0];
            newLons[0] = lons[0];
            int upto = 1;
            for(int k=1;k<lats.length;k++) {
              if (lats[k] != lats[k-1] || lons[k] != lons[k-1]) {
                newLats[upto] = lats[k];
                newLons[upto] = lons[k];
                upto++;
              }
            }
            lats = newLats;
            lons = newLons;
          }
          */

                    polyPlusHoles.add(new Polygon(lats, lons));
                    totalVertexCount += vertexCount;
                }

                Polygon firstPoly = polyPlusHoles.get(0);
                Polygon[] holes = polyPlusHoles.subList(1, polyPlusHoles.size()).toArray(new Polygon[polyPlusHoles.size()-1]);

                polys.add(new Polygon(firstPoly.getPolyLats(), firstPoly.getPolyLons(), holes));
            }

            result.add(polys.toArray(new Polygon[polys.size()]));
        }
        System.out.println("Total vertex count: " + totalVertexCount);

    /*
    try (PrintWriter out = new PrintWriter("/x/tmp/londonpoly.html")) {
      out.println(earth.finish());
    }
    */

        return result;
    }

    /** Returns true if a (inclusive) fully contains b (inclusive) */
    private static boolean contains(Rectangle a, Rectangle b) {
        return a.minLat <= b.minLat && a.maxLat >= b.maxLat && a.minLon <= b.minLon && a.maxLon >= b.maxLon;
    }

  /*
  private static void plotBKD(IndexReader reader) throws IOException {
    List<LeafReaderContext> leaves = reader.leaves();
    if (leaves.size() != 1) {
      throw new IllegalArgumentException("reader has " + leaves.size() + " leaves");
    }

    LeafReader leafReader = leaves.get(0).reader();

    final List<Rectangle> stack = new ArrayList<>();

    // Gather all leaf cells:
    List<Rectangle> leafCells = new ArrayList<>();
    leafReader.getPointValues().intersect("point", new IntersectVisitor() {
        private byte[] lastMinPackedValue;
        private byte[] lastMaxPackedValue;

        @Override
        public void visit(int docID) throws IOException {
          throw new AssertionError();
        }

        @Override
        public void visit(int docID, byte[] packedValue) throws IOException {
          /*
          if (lastMinPackedValue != null) {

            double minLat = decodeLatitude(lastMinPackedValue, 0);
            double minLon = decodeLongitude(lastMinPackedValue, Integer.BYTES);
            double maxLat = decodeLatitude(lastMaxPackedValue, 0);
            double maxLon = decodeLongitude(lastMaxPackedValue, Integer.BYTES);

            leafCells.add(new Rectangle(minLat, maxLat, minLon, maxLon));
            lastMinPackedValue = null;
          }
          * /
        }

        @Override
        public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
          lastMinPackedValue = minPackedValue.clone();
          lastMaxPackedValue = maxPackedValue.clone();

          double minLat = decodeLatitude(lastMinPackedValue, 0);
          double minLon = decodeLongitude(lastMinPackedValue, Integer.BYTES);
          double maxLat = decodeLatitude(lastMaxPackedValue, 0);
          double maxLon = decodeLongitude(lastMaxPackedValue, Integer.BYTES);

          Rectangle r = new Rectangle(minLat, maxLat, minLon, maxLon);

          // Pop stack:
          while (stack.size() > 0 && contains(stack.get(stack.size()-1), r) == false) {
            stack.remove(stack.size()-1);
            //System.out.println("  pop");
          }

          // Push stack:
          stack.add(r);
          //System.out.println("  push");

          if (stack.size() == 12) {
            leafCells.add(r);
          }

          return Relation.CELL_CROSSES_QUERY;
        }
      });

    EarthDebugger earth = new EarthDebugger();
    //System.out.println(leafCells.size() + " leaves:");
    for(Rectangle leafCell : leafCells) {
      //System.out.println("  " + leafCell);
      earth.addRect(leafCell.minLat, leafCell.maxLat, leafCell.minLon, leafCell.maxLon);
    }
    System.out.println(earth.finish());
  }
*/

    private static void addShape(Object shape, final IndexWriter w) throws IOException {
        Field[] fields;
        if (shape instanceof Polygon) {
            fields = LatLonShape.createIndexableFields(SHAPE_FIELDNAME, (Polygon)shape);
        } else if (shape instanceof Line) {
            fields = LatLonShape.createIndexableFields(SHAPE_FIELDNAME, (Line)shape);
        } else if (shape instanceof double[]) {
            double[] pt = (double[])shape;
            fields = LatLonShape.createIndexableFields(SHAPE_FIELDNAME, pt[1], pt[0]);
        } else {
            throw new IllegalArgumentException("unknown shape");
        }
        Document doc = new Document();
        for (Field f : fields) {
            doc.add(f);
        }
        w.addDocument(doc);
    }

    private static void createLatLonShapeIndex(String[] lines, final int count, AtomicLong totalCount, final IndexWriter w) throws IOException, ParseException {
        for(int i=0;i<count;i++) {
            Object shape = SimpleWKTShapeParser.parse(lines[i]);
            if (shape instanceof Polygon[]) {
                for (Polygon poly : (Polygon[])shape) {
                    addShape(poly, w);
                }
            } else if (shape instanceof Line[]) {
                for (Line line : (Line[]) shape) {
                    addShape(line, w);
                }
            } else if (shape instanceof double[][]) {
                double[][] pts = (double[][])shape;
                for (int p=0; p<pts.length; ++p) {
                    addShape(pts[p], w);
                }
            } else {
                addShape(shape, w);
            }
            long x = totalCount.incrementAndGet();
            if (x % 1000000 == 0) {
                System.out.println(x + "...");
            }
        }
    }

    private static void createPrefixTreeIndex(String[] lines, final int count, AtomicLong totalCount, final IndexWriter w) throws IOException, ParseException {
        Field[] fields;
        for(int i=0;i<count;i++) {
            Shape shape = prefixTree.ctx.readShapeFromWkt(lines[i]);
            fields = prefixTree.strategy.createIndexableFields(shape);
            Document doc = new Document();
            for (Field f : fields) {
                doc.add(f);
            }
            w.addDocument(doc);
            long x = totalCount.incrementAndGet();
            if (x % 1000000 == 0) {
                System.out.println(x + "...");
            }
        }
    }

    private static void createIndex(boolean fast, boolean doForceMerge) throws IOException, InterruptedException {

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        int BUFFER_SIZE = 1 << 16;     // 64K
        InputStream is;
        if (SMALL) {
            is = Files.newInputStream(Paths.get(DATA_LOCATION, "planetOSMShapes.hundred.wkt"));
        } else {
            is = Files.newInputStream(Paths.get(DATA_LOCATION, "planetOSMShapes.subset.wkt"));
        }

        final BufferedReader reader = new BufferedReader(new InputStreamReader(is, decoder), BUFFER_SIZE);

        int NUM_THREADS;
        if (fast) {
            NUM_THREADS = 4;
        } else {
            NUM_THREADS = 1;
        }

        final int CHUNK = 10000;

        long t0 = System.nanoTime();
        final AtomicLong totalCount = new AtomicLong();

        for(int part=0;part<NUM_PARTS;part++) {
            Directory dir = FSDirectory.open(Paths.get(getName(part)));

            IndexWriterConfig iwc = new IndexWriterConfig(null);
            iwc.setCodec(getCodec(fast));
            iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
            if (fast) {
                ((TieredMergePolicy) iwc.getMergePolicy()).setMaxMergedSegmentMB(Double.POSITIVE_INFINITY);
                iwc.setRAMBufferSizeMB(1024);
            } else {
                iwc.setMaxBufferedDocs(109630);
                iwc.setMergePolicy(new LogDocMergePolicy());
                iwc.setMergeScheduler(new SerialMergeScheduler());
            }
            iwc.setInfoStream(new PrintStreamInfoStream(System.out));
            final IndexWriter w = new IndexWriter(dir, iwc);

            Thread[] threads = new Thread[NUM_THREADS];
            final AtomicBoolean finished = new AtomicBoolean();
            final Object lock = new Object();

            final int finalPart = part;

            for(int t=0;t<NUM_THREADS;t++) {
                threads[t] = new Thread() {
                    @Override
                    public void run() {
                        String[] lines = new String[CHUNK];
                        int chunkCount = 0;
                        while (finished.get() == false) {
                            try {
                                int count = CHUNK;
                                synchronized(lock) {
                                    for(int i=0;i<CHUNK;i++) {
                                        String line = reader.readLine();
                                        if (line == null) {
                                            count = i;
                                            finished.set(true);
                                            break;
                                        }
                                        lines[i] = line;
                                    }
                                    if (finalPart == 0 && totalCount.get()+count >= 2000000000) {
                                        finished.set(true);
                                    }
                                }

                                if (useLatLonShape) {
                                    createLatLonShapeIndex(lines, count, totalCount, w);
                                } else if (usePrefixTrees) {
                                    createPrefixTreeIndex(lines, count, totalCount, w);
                                } else {
                                    throw new AssertionError("must specify one of -useLatLonShape or -usePrefixTrees");
                                }
                                chunkCount++;
                                if (false && SMALL == false && chunkCount == 20000) {
                                    System.out.println("NOW BREAK EARLY");
                                    break;
                                }
                            } catch (IOException ioe) {
                                throw new RuntimeException(ioe);
                            } catch (ParseException pe) {
                                throw new RuntimeException(pe);
                            }
                        }
                    }
                };
                threads[t].start();
            }

            for(Thread thread : threads) {
                thread.join();
            }

            System.out.println("Part " + part + " is done: w.maxDoc()=" + w.getDocStats().maxDoc);
            w.commit();
            System.out.println("done commit");
            long t1 = System.nanoTime();
            System.out.println(((t1-t0)/1000000000.0) + " sec to index part " + part);
            if (doForceMerge) {
                w.forceMerge(1);
                long t2 = System.nanoTime();
                System.out.println(((t2-t1)/1000000000.0) + " sec to force merge part " + part);
            }
            w.close();
        }

        //System.out.println(totalCount.get() + " total docs");
        //System.out.println("Force merge...");
        //w.forceMerge(1);
        //long t2 = System.nanoTime();
        //System.out.println(((t2-t1)/1000000000.0) + " sec to force merge");

        //w.close();
        //long t3 = System.nanoTime();
        //System.out.println(((t3-t2)/1000000000.0) + " sec to close");
        //System.out.println(((t3-t2)/1000000000.0) + " sec to close");
    }

    private static Codec getCodec(boolean fast) {
        if (fast) {
            return new FilterCodec("Lucene80", Codec.getDefault()) {
                @Override
                public PointsFormat pointsFormat() {
                    return new PointsFormat() {
                        @Override
                        public PointsWriter fieldsWriter(SegmentWriteState writeState) throws IOException {
                            int maxPointsInLeafNode = 1024;
                            return new Lucene60PointsWriter(writeState, maxPointsInLeafNode, BKDWriter.DEFAULT_MAX_MB_SORT_IN_HEAP);
                        }

                        @Override
                        public PointsReader fieldsReader(SegmentReadState readState) throws IOException {
                            return new Lucene60PointsReader(readState);
                        }
                    };
                }
            };
        } else {
            return Codec.forName("Lucene80");
        }
    }

    /** One normally need not clone a Polygon (it's a read only holder class), but we do this here just to keep the benchmark honest, by
     *  including Polygon construction cost in measuring search run time. */
    private static Polygon[] clonePolygon(Polygon[] polies) {
        Polygon[] newPolies = new Polygon[polies.length];
        for(int i=0;i<polies.length;i++) {
            Polygon poly = polies[i];
            Polygon[] holes = poly.getHoles();
            if (holes.length > 0) {
                holes = clonePolygon(holes);
            }
            newPolies[i] = new Polygon(poly.getPolyLats(), poly.getPolyLons(), holes);
        }
        return newPolies;
    }

    private static double[] runQueries(IndexSearcher[] searchers, List<Query> queries) throws IOException {
        double bestQPS = Double.NEGATIVE_INFINITY;

        // million hits per second:
        double bestMHPS = Double.NEGATIVE_INFINITY;

        for(int iter=0;iter<ITERS;iter++) {
            long tStart = System.nanoTime();
            long totHits = 0;
            int count = 0;
            int numQueries = 0;
            for (Query q : queries) {
                int hitCount = 0;
                for(IndexSearcher s : searchers) {
                    hitCount += s.count(q);
                }
                if (iter == 0) {
                    //System.out.println("QUERY " + count + ": " + q + " hits=" + hitCount);
                    count++;
                }
                totHits += hitCount;
                ++numQueries;
            }

            long tEnd = System.nanoTime();
            double elapsedSec = (tEnd-tStart)/1000000000.0;
            double qps = queries.size() / elapsedSec;
            double mhps = (totHits/1000000.0) / elapsedSec;
            System.out.println(String.format(Locale.ROOT,
                    "ITER %d: %.2f M hits/sec, %.2f QPS (%.2f sec for %d queries), totHits=%d",
                    iter, mhps, qps, elapsedSec, numQueries, totHits));
            if (qps > bestQPS) {
                System.out.println("  ***");
                bestQPS = qps;
                bestMHPS = mhps;
            }
        }

        return new double[] {bestQPS, bestMHPS};
    }

    private static void queryIndex(String queryClass, int gons, int nearestTopN, String polyFile, boolean preBuildQueries, Double filterPercent) throws IOException {
        IndexSearcher[] searchers = new IndexSearcher[NUM_PARTS];
        Directory[] dirs = new Directory[NUM_PARTS];
        long sizeOnDisk = 0;
        for(int part=0;part<NUM_PARTS;part++) {
            dirs[part] = FSDirectory.open(Paths.get(getName(part)));
            searchers[part] = new IndexSearcher(DirectoryReader.open(dirs[part]));
            searchers[part].setQueryCache(null);
            for(String name : dirs[part].listAll()) {
                sizeOnDisk += dirs[part].fileLength(name);
            }
        }
        //plotBKD(searchers[0].getIndexReader());
        System.out.println("INDEX SIZE: " + (sizeOnDisk/1024./1024./1024.) + " GB");
        long bytes = 0;
        long maxDoc = 0;
        for(IndexSearcher s : searchers) {
            IndexReader r = s.getIndexReader();
            maxDoc += r.maxDoc();
            for(LeafReaderContext ctx : r.leaves()) {
                CodecReader cr = (CodecReader) ctx.reader();
        /*
        for(Accountable acc : cr.getChildResources()) {
          System.out.println("  " + Accountables.toString(acc));
        }
        */
                bytes += cr.ramBytesUsed();
            }
        }
        System.out.println("READER MB: " + (bytes/1024./1024.));
        System.out.println("maxDoc=" + maxDoc);

        double bestQPS = Double.NEGATIVE_INFINITY;

        // million hits per second:
        double bestMHPS = Double.NEGATIVE_INFINITY;

        if (queryClass.equals("polyFile")) {

            // TODO: only load the double[][] here, so that we includ the cost of making Polygon and Query in each iteration!!
            List<Polygon[]> polygons = readPolygons(polyFile);

            // Uncomment to find the lost points!!

      /*
      BooleanQuery.Builder b = new BooleanQuery.Builder();
      b.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
      for(Query q : queries) {
        b.add(q, BooleanClause.Occur.MUST_NOT);
      }
      searchers[0].search(b.build(), new SimpleCollector() {
          private int markerCount;
          private SortedNumericDocValues docValues;

          @Override
          protected void doSetNextReader(LeafReaderContext context) throws IOException {
            docValues = context.reader().getSortedNumericDocValues("point");
          }

          @Override
          public boolean needsScores() {
            return false;
          }

          @Override
          public void collect(int doc) {
            docValues.setDocument(doc);
            int count = docValues.count();
            for (int i = 0; i < count; i++) {
              long encoded = docValues.valueAt(i);
              double docLatitude = LatLonPoint.decodeLatitude((int)(encoded >> 32));
              double docLongitude = LatLonPoint.decodeLongitude((int)(encoded & 0xFFFFFFFF));
              System.out.println("        WE.marker([" + docLatitude + ", " + docLongitude + "]).addTo(earth);");
            }
          }
        });
      */

      /*
      {
        Query q = LatLonPoint.newBoxQuery("point", minLat, maxLat, minLon, maxLon);
        int totHits = 0;

        for(IndexSearcher s : searchers) {
          int hitCount = s.count(q);
          totHits += hitCount;
        }

        System.out.println("Poly file bbox total hits: " + totHits);
      }
      */

            if (preBuildQueries) {
                System.out.println("\nUsing pre-built polygon queries, loaded from file " + polyFile);
                List<Query> queries = new ArrayList<>();
                for(Polygon[] multiPolygon : polygons) {
                    Query q = null;
                    if (useLatLonShape) {
                        q = LatLonShape.newPolygonQuery(SHAPE_FIELDNAME, QueryRelation.INTERSECTS, multiPolygon);
                    } else if (usePrefixTrees) {
                        q = makePrefixTreeMultiPolyQuery(multiPolygon);
                    }
                    queries.add(q);
                }

                double[] result = runQueries(searchers, queries);
                bestQPS = result[0];
                bestMHPS = result[1];

            } else {

                System.out.println("\nUsing on-the-fly polygon queries, loaded from file " + polyFile);

                for(int iter=0;iter<ITERS;iter++) {
                    long tStart = System.nanoTime();
                    long totHits = 0;
                    int queryCount = 0;
                    for(Polygon[] multiPolygon : polygons) {

                        // We do this to keep the benchmark honest, so any construction cost of a polygon is included in our run time measure:
                        multiPolygon = clonePolygon(multiPolygon);

                        Query q;
                        if (useLatLonShape) {
                            q = LatLonShape.newPolygonQuery(SHAPE_FIELDNAME, QueryRelation.INTERSECTS, multiPolygon);
                        } else if (usePrefixTrees) {
                            q = makePrefixTreeMultiPolyQuery(multiPolygon);
                        } else {
                            throw new AssertionError();
                        }

                        for(IndexSearcher s : searchers) {
                            int hitCount = s.count(q);
                            totHits += hitCount;
                        }
                        queryCount++;
                    }

                    long tEnd = System.nanoTime();
                    double elapsedSec = (tEnd-tStart)/1000000000.0;
                    double qps = queryCount / elapsedSec;
                    double mhps = (totHits/1000000.0) / elapsedSec;
                    System.out.println(String.format(Locale.ROOT,
                            "ITER %d: %.2f M hits/sec, %.2f QPS (%.2f sec for %d queries), totHits=%d",
                            iter, mhps, qps, elapsedSec, queryCount, totHits));
                    if (qps > bestQPS) {
                        System.out.println("  ***");
                        bestQPS = qps;
                        bestMHPS = mhps;
                    }
                }
            }

        } else if (preBuildQueries) {
            System.out.println("\nUsing pre-built queries");

            double[] result = runQueries(searchers, makeQueries(queryClass, gons));
            bestQPS = result[0];
            bestMHPS = result[1];

        } else {
            System.out.println("\nUsing on-the-fly queries");

            // Create regularly spaced shapes in a grid around London, UK:
            int STEPS = 5;
            double MIN_LAT = 51.0919106;
            double MAX_LAT = useLatLonShape ? 52.6542719 : 51.6542719;
            double MIN_LON = useLatLonShape ? -2.3867282 : -0.3867282;
            double MAX_LON = 0.8492337;

            // makeRegularPoly has insanely slow math, so make the double[]'s here.
            // we still form the query inside the benchmark loop (e.g. to account for preprocessing)
            ArrayList<double[][]> polys = new ArrayList<double[][]>(225);
            if ("poly".equals(queryClass)) {
                for(int latStep=0;latStep<STEPS;latStep++) {
                    double lat = MIN_LAT + latStep * (MAX_LAT - MIN_LAT) / STEPS;
                    for(int lonStep=0;lonStep<STEPS;lonStep++) {
                        double lon = MIN_LON + lonStep * (MAX_LON - MIN_LON) / STEPS;
                        for(int latStepEnd=latStep+1;latStepEnd<=STEPS;latStepEnd++) {
                            double latEnd = MIN_LAT + latStepEnd * (MAX_LAT - MIN_LAT) / STEPS;
                            for(int lonStepEnd=lonStep+1;lonStepEnd<=STEPS;lonStepEnd++) {
                                double lonEnd = MIN_LON + lonStepEnd * (MAX_LON - MIN_LON) / STEPS;
                                double distanceMeters = SloppyMath.haversinMeters(lat, lon, latEnd, lonEnd)/2.0;
                                double centerLat = (lat+latEnd)/2.0;
                                double centerLon = (lon+lonEnd)/2.0;
                                polys.add(makeRegularPoly(centerLat, centerLon, distanceMeters, gons));
                            }
                        }
                    }
                }
            }

            for(int iter=0;iter<ITERS;iter++) {
                long tStart = System.nanoTime();
                long totHits = 0;
                double totNearestDistance = 0.0;
                int queryCount = 0;

                for(int latStep=0;latStep<STEPS;latStep++) {
                    double lat = MIN_LAT + latStep * (MAX_LAT - MIN_LAT) / STEPS;
                    for(int lonStep=0;lonStep<STEPS;lonStep++) {
                        double lon = MIN_LON + lonStep * (MAX_LON - MIN_LON) / STEPS;
                        for(int latStepEnd=latStep+1;latStepEnd<=STEPS;latStepEnd++) {
                            double latEnd = MIN_LAT + latStepEnd * (MAX_LAT - MIN_LAT) / STEPS;
                            for(int lonStepEnd=lonStep+1;lonStepEnd<=STEPS;lonStepEnd++) {
                                double lonEnd = MIN_LON + lonStepEnd * (MAX_LON - MIN_LON) / STEPS;

                                double distanceMeters = SloppyMath.haversinMeters(lat, lon, latEnd, lonEnd)/2.0;
                                double centerLat = (lat+latEnd)/2.0;
                                double centerLon = (lon+lonEnd)/2.0;
                                ScoreDoc[] nearestHits = null;
                                Query q = null;

                                switch(queryClass) {
                                    case "poly":
                                        double[][] poly = polys.get(queryCount);
                                        //System.out.println("poly lats: " + Arrays.toString(poly[0]));
                                        //System.out.println("poly lons: " + Arrays.toString(poly[1]));
                                        if (useLatLonShape) {
                                            q = LatLonShape.newPolygonQuery(SHAPE_FIELDNAME, QueryRelation.INTERSECTS, new Polygon(poly[0], poly[1]));
                                        } else if (usePrefixTrees) {
                                            q = makePrefixTreePolyQuery(poly[1], poly[0]);
                                        } else {
                                            throw new AssertionError();
                                        }
                                        break;
                                    case "box":
                                        if (useLatLonShape) {
                                            q = LatLonShape.newBoxQuery(SHAPE_FIELDNAME, QueryRelation.INTERSECTS, lat, latEnd, lon, lonEnd);
                                        } else if (usePrefixTrees) {
                                            q = makePrefixTreeBBoxQuery(lat, latEnd, lon, lonEnd);
                                        } else {
                                            throw new AssertionError();
                                        }
                                        break;
                                    default:
                                        throw new AssertionError("unknown queryClass " + queryClass);
                                }

                                // TODO: do this somewhere else?
                                if (filterPercent != null) {
                                    BooleanQuery.Builder builder = new BooleanQuery.Builder();
                                    builder.add(q, BooleanClause.Occur.MUST);
                                    builder.add(new RandomQuery(filterPercent), BooleanClause.Occur.FILTER);
                                    q = builder.build();
                                }


                                if (q != null) {
                                    //System.out.println("\nRUN QUERY " + q);
                                    // long t0 = System.nanoTime();
                                    for(IndexSearcher s : searchers) {
                                        int hitCount = s.count(q);
                                        System.out.println("hitCount = " + hitCount);
                                        totHits += hitCount;
                                        if (false && iter == 0) {
                                            System.out.println("q=" + q + " lat=" + centerLat + " lon=" + centerLon + " distanceMeters=" + distanceMeters + " hits: " + hitCount);
                                        }
                                    }
                                } else {
                                    assert nearestHits != null;
                                    totHits += nearestHits.length;
                                }
                                queryCount++;
                                //throw new RuntimeException("now stop");
                            }
                        }
                    }
                }

                long tEnd = System.nanoTime();
                double elapsedSec = (tEnd-tStart)/1000000000.0;
                double qps = queryCount / elapsedSec;
                double mhps = (totHits/1000000.0) / elapsedSec;
                if (queryClass.equals("nearest")) {
                    System.out.println(String.format(Locale.ROOT,
                            "ITER %d: %.2f QPS (%.2f sec for %d queries), totNearestDistance=%.10f, totHits=%d",
                            iter, qps, elapsedSec, queryCount, totNearestDistance, maxDoc));
                } else {
                    System.out.println(String.format(Locale.ROOT,
                            "ITER %d: %.2f M hits/sec, %.2f QPS (%.2f sec for %d queries), totHits=%d",
                            iter, mhps, qps, elapsedSec, queryCount, totHits));
                }
                if (qps > bestQPS) {
                    System.out.println("  ***");
                    bestQPS = qps;
                    bestMHPS = mhps;
                }
            }
        }
        System.out.println("BEST M hits/sec: " + bestMHPS);
        System.out.println("BEST QPS: " + bestQPS);

        for(IndexSearcher s : searchers) {
            s.getIndexReader().close();
        }
        IOUtils.close(dirs);
    }

    private static Query makePrefixTreeMultiPolyQuery(Polygon... polygons) {
        JtsSpatialContext jtsCtx = JtsSpatialContext.GEO;
        org.locationtech.jts.geom.GeometryFactory factory = jtsCtx.getGeometryFactory();
        org.locationtech.jts.geom.Coordinate[] shell;
        Polygon p;
        org.locationtech.jts.geom.Polygon[] jtsPolys = new org.locationtech.jts.geom.Polygon[polygons.length];
        for (int i = 0; i < polygons.length; ++i) {
            p = polygons[i];
            shell = new org.locationtech.jts.geom.Coordinate[p.numPoints()];
            for (int v = 0; v < p.numPoints(); ++v) {
                shell[v] = new org.locationtech.jts.geom.Coordinate(p.getPolyLon(v), p.getPolyLat(v));
            }
            jtsPolys[i] = factory.createPolygon(shell);
        }
        MultiPolygon multiPolygon = factory.createMultiPolygon(jtsPolys);
        JtsGeometry geom = new JtsGeometry(multiPolygon, JtsSpatialContext.GEO, false, false);
        SpatialArgs args = new SpatialArgs(SpatialOperation.Intersects, geom);
        return prefixTree.strategy.makeQuery(args);
    }

    private static Query makePrefixTreePolyQuery(double[] lats, double[] lons) {
        JtsSpatialContext jtsCtx = JtsSpatialContext.GEO;
        org.locationtech.jts.geom.GeometryFactory factory = jtsCtx.getGeometryFactory();
        org.locationtech.jts.geom.Coordinate[] shell = new org.locationtech.jts.geom.Coordinate[lats.length];
        for (int i = 0; i < lats.length; ++i) {
            shell[i] = new org.locationtech.jts.geom.Coordinate(lats[i], lons[i]);
        }
        JtsGeometry geom = new JtsGeometry(factory.createPolygon(shell), JtsSpatialContext.GEO, false, false);
        SpatialArgs args = new SpatialArgs(SpatialOperation.Intersects, geom);
        return prefixTree.strategy.makeQuery(args);
    }

    private static Query makePrefixTreeBBoxQuery(double minLat, double maxLat, double minLon, double maxLon) {
        SpatialArgs args = new SpatialArgs(SpatialOperation.Intersects, prefixTree.ctx.getShapeFactory().rect(minLon, maxLon, minLat, maxLat));
        return prefixTree.strategy.makeQuery(args);
    }

    private static List<Query> makeQueries(String queryClass, int gons) {
        List<Query> queries = new ArrayList<>();
        // Create regularly spaced shapes in a grid around London, UK:
        int STEPS = 5;
        double MIN_LAT = 51.0919106;
        double MAX_LAT = 51.6542719;
        double MIN_LON = -0.3867282;
        double MAX_LON = 0.8492337;
        for(int latStep=0;latStep<STEPS;latStep++) {
            double lat = MIN_LAT + latStep * (MAX_LAT - MIN_LAT) / STEPS;
            for(int lonStep=0;lonStep<STEPS;lonStep++) {
                double lon = MIN_LON + lonStep * (MAX_LON - MIN_LON) / STEPS;
                for(int latStepEnd=latStep+1;latStepEnd<=STEPS;latStepEnd++) {
                    double latEnd = MIN_LAT + latStepEnd * (MAX_LAT - MIN_LAT) / STEPS;
                    for(int lonStepEnd=lonStep+1;lonStepEnd<=STEPS;lonStepEnd++) {
                        double lonEnd = MIN_LON + lonStepEnd * (MAX_LON - MIN_LON) / STEPS;

                        double distanceMeters = SloppyMath.haversinMeters(lat, lon, latEnd, lonEnd)/2.0;
                        double centerLat = (lat+latEnd)/2.0;
                        double centerLon = (lon+lonEnd)/2.0;

                        Query q = null;

                        switch(queryClass) {
                            case "poly":
                                double[][] poly = makeRegularPoly(centerLat, centerLon, distanceMeters, gons);
                                //System.out.println("poly lats: " + Arrays.toString(poly[0]));
                                //System.out.println("poly lons: " + Arrays.toString(poly[1]));
                                if (useLatLonShape) {
                                    q = LatLonShape.newPolygonQuery(SHAPE_FIELDNAME, QueryRelation.INTERSECTS, new Polygon(poly[0], poly[1]));
                                } else if (usePrefixTrees) {
                                    q = makePrefixTreePolyQuery(poly[0], poly[1]);
                                } else {
                                    throw new AssertionError();
                                }
                                break;
                            case "box":
                                if (useLatLonShape) {
                                    q = LatLonShape.newBoxQuery(SHAPE_FIELDNAME, QueryRelation.INTERSECTS, lat, latEnd, lon, lonEnd);
                                } else if (usePrefixTrees) {
                                    q = makePrefixTreeBBoxQuery(lat, latEnd, lon, lonEnd);
                                } else {
                                    throw new AssertionError();
                                }
                                break;
                            default:
                                throw new AssertionError();
                        }

                        queries.add(q);
                    }
                }
            }
        }

        return queries;
    }

    /** Makes an n-gon, centered at the provided lat/lon, and each vertex approximately
     *  distanceMeters away from the center.
     *
     * Do not invoke me across the dateline or a pole!! */
    private static double[][] makeRegularPoly(double centerLat, double centerLon, double radiusMeters, int gons) {

        // System.out.println("MAKE POLY: centerLat=" + centerLat + " centerLon=" + centerLon + " radiusMeters=" + radiusMeters + " gons=" + gons);

        double[][] result = new double[2][];
        result[0] = new double[gons+1];
        result[1] = new double[gons+1];
        //System.out.println("make gon=" + gons);
        for(int i=0;i<gons;i++) {
            double angle = 360.0-i*(360.0/gons);
            //System.out.println("  angle " + angle);
            double x = Math.cos(Math.toRadians(angle));
            double y = Math.sin(Math.toRadians(angle));
            double factor = 2.0;
            double step = 1.0;
            int last = 0;

            //System.out.println("angle " + angle + " slope=" + slope);
            // Iterate out along one spoke until we hone in on the point that's nearly exactly radiusMeters from the center:
            while (true) {
                double lat = centerLat + y * factor;
                GeoUtils.checkLatitude(lat);
                double lon = centerLon + x * factor;
                GeoUtils.checkLongitude(lon);
                double distanceMeters = SloppyMath.haversinMeters(centerLat, centerLon, lat, lon);

                //System.out.println("  iter lat=" + lat + " lon=" + lon + " distance=" + distanceMeters + " vs " + radiusMeters);
                if (Math.abs(distanceMeters - radiusMeters) < 0.1) {
                    // Within 10 cm: close enough!
                    result[0][i] = lat;
                    result[1][i] = lon;
                    break;
                }

                if (distanceMeters > radiusMeters) {
                    // too big
                    //System.out.println("    smaller");
                    factor -= step;
                    if (last == 1) {
                        //System.out.println("      half-step");
                        step /= 2.0;
                    }
                    last = -1;
                } else if (distanceMeters < radiusMeters) {
                    // too small
                    //System.out.println("    bigger");
                    factor += step;
                    if (last == -1) {
                        //System.out.println("      half-step");
                        step /= 2.0;
                    }
                    last = 1;
                }
            }
        }

        // close poly
        result[0][gons] = result[0][0];
        result[1][gons] = result[1][0];

        //System.out.println("  polyLats=" + Arrays.toString(result[0]));
        //System.out.println("  polyLons=" + Arrays.toString(result[1]));

        return result;
    }

    private static String setQueryClass(String currentValue, String newValue) {
        if (currentValue != null) {
            throw new IllegalArgumentException("specify only one of -poly, -polyFile, -distance, -box");
        }
        return newValue;
    }

    private static class PrefixTree {
        final SpatialStrategy strategy;
        final SpatialPrefixTree grid;
        final int levels;
        final SpatialContext ctx = SpatialContext.GEO;

        PrefixTree(String grid, String strategy, double spatialResolution) {
            switch (grid) {
                case "-packedquadtree":
                    this.levels = quadTreeLevelsForPrecision(spatialResolution);
                    this.grid = new PackedQuadPrefixTree(this.ctx, this.levels);
                    break;
                case "-quadtree":
                    this.levels = quadTreeLevelsForPrecision(spatialResolution);
                    this.grid = new QuadPrefixTree(this.ctx, this.levels);
                    break;
                case "-geohash":
                    this.levels = geoHashLevelsForPrecision(spatialResolution);
                    this.grid = new GeohashPrefixTree(this.ctx, this.levels);
                    break;
                default:
                    throw new IllegalArgumentException("unknown prefix tree grid [" + grid + "]");
            }

            switch (strategy) {
                case "-rpt":
                    this.strategy = new RecursivePrefixTreeStrategy(this.grid, SHAPE_FIELDNAME);
                    break;
                case "-composite":
                    if (this.grid instanceof GeohashPrefixTree) {
                        throw new IllegalArgumentException("GeohashPrefixTree not compatible with composite strategy");
                    }
                    this.strategy = new CompositeSpatialStrategy(SHAPE_FIELDNAME,
                            new RecursivePrefixTreeStrategy(this.grid, SHAPE_FIELDNAME),
                            new SerializedDVStrategy(this.ctx, SHAPE_FIELDNAME));
                    break;
                case "-term":
                    if (this.grid instanceof GeohashPrefixTree == false) {
                        throw new IllegalArgumentException("term strategy only compatible with GeohashPrefixTree");
                    }
                    this.strategy = new TermQueryPrefixTreeStrategy(this.grid, SHAPE_FIELDNAME);
                    break;
                default:
                    throw new IllegalArgumentException("unknown prefix tree strategy [" + strategy + "]");
            }
        }
    }

    /** Earth ellipsoid major axis defined by WGS 84 in meters */
    public static final double EARTH_SEMI_MAJOR_AXIS = 6378137.0;      // meters (WGS 84)

    /** Earth ellipsoid minor axis defined by WGS 84 in meters */
    public static final double EARTH_SEMI_MINOR_AXIS = 6356752.314245; // meters (WGS 84)

    /** Earth ellipsoid equator length in meters */
    public static final double EARTH_EQUATOR = 2*Math.PI * EARTH_SEMI_MAJOR_AXIS;

    /** Earth ellipsoid polar distance in meters */
    public static final double EARTH_POLAR_DISTANCE = Math.PI * EARTH_SEMI_MINOR_AXIS;

    public static int quadTreeLevelsForPrecision(double meters) {
        assert meters >= 0;
        if(meters == 0) {
            return QuadPrefixTree.MAX_LEVELS_POSSIBLE;
        } else {
            final double ratio = 1+(EARTH_POLAR_DISTANCE / EARTH_EQUATOR); // cell ratio
            final double width = Math.sqrt((meters*meters)/(ratio*ratio)); // convert to cell width
            final long part = Math.round(Math.ceil(EARTH_EQUATOR / width));
            final int level = Long.SIZE - Long.numberOfLeadingZeros(part)-1; // (log_2)
            return (part<=(1L<<level)) ?level :(level+1); // adjust level
        }
    }

    public static int geoHashLevelsForPrecision(double meters) {
        assert meters >= 0;

        if(meters == 0) {
            return GeohashPrefixTree.getMaxLevelsPossible();
        } else {
            final double ratio = 1+(EARTH_POLAR_DISTANCE / EARTH_EQUATOR); // cell ratio
            final double width = Math.sqrt((meters*meters)/(ratio*ratio)); // convert to cell width
            final double part = Math.ceil(EARTH_EQUATOR / width);
            if(part == 1)
                return 1;
            final int bits = (int)Math.round(Math.ceil(Math.log(part) / Math.log(2)));
            final int full = bits / 5;                // number of 5 bit subdivisions
            final int left = bits - full*5;           // bit representing the last level
            final int even = full + (left>0?1:0);     // number of even levels
            final int odd = full + (left>3?1:0);      // number of odd levels
            return even+odd;
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int count = 0;
        boolean reindex = false;
        boolean fastReindex = false;
        Double filterPercent = null;
        String queryClass = null;
        String polyFile = null;
        int gons = 0;
        int nearestTopN = 0;
        boolean preBuildQueries = false;
        boolean forceMerge = false;
        boolean doDistanceSort = false;
        String prefixTreeStrategy = null;
        String prefixTreeGrid = null;
        Double prefixTreeResolution = 1d;
        for(int i=0;i<args.length;i++) {
            String arg = args[i];
            if (arg.equals("-reindex")) {
                reindex = true;
            } else if (arg.equals("-full")) {
                SMALL = false;
            } else if (arg.equals("-reindexFast")) {
                reindex = true;
                fastReindex = true;
            } else if (arg.equals("-rpt") || arg.equals("-term") || arg.equals("-composite")) {
                usePrefixTrees = true;
                if (prefixTreeStrategy != null) {
                    throw new IllegalArgumentException("prefixTree strategy already set to [" + prefixTreeStrategy + "]");
                }
                prefixTreeStrategy = arg;
                count++;
            } else if (arg.equals("-quadtree") || arg.equals("-packedquadtree") || arg.equals("-geohash")) {
                usePrefixTrees = true;
                if (prefixTreeGrid != null) {
                    throw new IllegalArgumentException("prefixTree grid already set to [" + prefixTreeGrid + "]");
                }
                prefixTreeGrid = arg;
                count++;
            } else if (arg.contains("-resolutionInMeters=")) {
                prefixTreeResolution =  Double.parseDouble(arg.substring(20));
                count++;
            } else if (arg.equals("-latLonShape")) {
                useLatLonShape = true;
                count++;
            } else if (arg.equals("-sort")) {
                doDistanceSort = true;
            } else if (arg.equals("-preBuildQueries")) {
                preBuildQueries = true;
            } else if (arg.equals("-polyMedium")) {
                // London boroughs:
                queryClass = setQueryClass(queryClass, "polyFile");
                polyFile = DATA_LOCATION + "/london.boroughs.poly.txt.gz";
            } else if (arg.equals("-polyRussia")) {
                // Just the one Russia Federation polygon from geonames shapes_simplified_low.txt
                queryClass = setQueryClass(queryClass, "polyFile");
                polyFile = DATA_LOCATION + "/russia.poly.txt.gz";
            } else if (arg.equals("-polyFile")) {
                queryClass = setQueryClass(queryClass, "polyFile");
                if (i + 1 < args.length) {
                    polyFile = args[i+1];
                    i++;
                } else {
                    throw new IllegalArgumentException("missing file argument to -polyFile");
                }
            } else if (arg.equals("-poly")) {
                queryClass = setQueryClass(queryClass, "poly");
                if (i + 1 < args.length) {
                    gons = Integer.parseInt(args[i+1]);
                    if (gons < 3) {
                        throw new IllegalArgumentException("gons must be >= 3; got " + gons);
                    }
                    i++;
                } else {
                    throw new IllegalArgumentException("missing gons argument to -poly");
                }
            } else if (arg.equals("-nearest")) {
                queryClass = setQueryClass(queryClass, "nearest");
                if (i + 1 < args.length) {
                    nearestTopN = Integer.parseInt(args[i+1]);
                    if (nearestTopN < 1) {
                        throw new IllegalArgumentException("nearest topN must be >= 1; got " + nearestTopN);
                    }
                    i++;
                } else {
                    throw new IllegalArgumentException("missing topN argument to -nearest");
                }
            } else if (arg.equals("-box")) {
                queryClass = setQueryClass(queryClass, "box");
            } else if (arg.equals("-distance")) {
                queryClass = setQueryClass(queryClass, "distance");
            } else if (arg.equals("-filter")) {
                if (i + 1 < args.length) {
                    filterPercent = Double.parseDouble(args[i+1]);
                    if (Double.isNaN(filterPercent) || filterPercent < 0 || filterPercent > 100) {
                        throw new IllegalArgumentException("filter percent must be [0 .. 100]; got " + filterPercent);
                    }
                    i++;
                } else {
                    throw new IllegalArgumentException("missing percentage argument to -filter");
                }
            } else if (arg.equals("-forceMerge")) {
                forceMerge = true;
            } else {
                throw new IllegalArgumentException("unknown command line option \"" + arg + "\"");
            }
        }
        if (preBuildQueries && filterPercent != null) {
            throw new IllegalArgumentException("teach me to do this crazy combination first");
        }
        if (queryClass == null) {
            throw new IllegalArgumentException("must specify exactly one of -box, -poly gons, -distance or -nearest; got none");
        }
        if (doDistanceSort) {
            if (preBuildQueries) {
                throw new IllegalArgumentException("teach me to do this crazy combination first");
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException("must specify exactly one of -prefixTree, -latLonShape; got none");
        } /*else if (count > 1) {
            throw new IllegalArgumentException("must specify exactly one of -prefixTree, -latLonShape; got more than one");
        }*/
        NUM_PARTS = SMALL ? 1 : 2;
        if (useLatLonShape) {
            System.out.println("\nUsing LatLonShape");
        } else if (usePrefixTrees) {
            System.out.println("\nUsing prefix trees");
            prefixTree = new PrefixTree(prefixTreeGrid, prefixTreeStrategy, prefixTreeResolution);
        } else {
            System.out.println("\nUsing geopoint");
        }
        System.out.println("Index path: " + getName(0));

        if (reindex) {
            createIndex(fastReindex, forceMerge);
        }
        queryIndex(queryClass, gons, nearestTopN, polyFile, preBuildQueries, filterPercent);
    }
}