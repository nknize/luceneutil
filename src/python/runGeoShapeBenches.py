import sys
import pickle
import subprocess
import os
import re
import datetime
import time

reTotHits = re.compile('totHits=(\d+)$')

GEO_LOGS_DIR = '/l/logs.nightly/geoshape'

nightly = '-nightly' in sys.argv

if nightly and '-reindex' not in sys.argv:
  sys.argv.append('-reindex')

haveRussia = True
haveFullPolygon = True

if nightly:
  if '-timeStamp' in sys.argv:
    timeStamp = sys.argv[sys.argv.index('-timeStamp')+1]
    year, month, day, hour, minute, second = (int(x) for x in timeStamp.split('.'))
    timeStampDateTime = datetime.datetime(year, month, day, hour, minute, second)
    if timeStampDateTime < datetime.datetime(year=2016, month=4, day=15):
      haveRussia = False
    haveFullPolygon = os.path.exists('/l/trunk.nightly/lucene/core/src/java/org/apache/lucene/geo/Polygon.java') or \
                      os.path.exists('/l/trunk.nightly/lucene/spatial/src/java/org/apache/lucene/util/Polygon.java')
  else:
    start = datetime.datetime.now()
    timeStamp = '%04d.%02d.%02d.%02d.%02d.%02d' % (start.year, start.month, start.day, start.hour, start.minute, start.second)
  resultsFileName = '%s/%s.pk' % (GEO_LOGS_DIR, timeStamp)
else:
  resultsFileName = 'geo.results.pk'

# nocommit should we "ant jar"?

if nightly:
  sources = '/l/util.nightly/src/main/perf/IndexAndSearchOSMShapes.java /l/util.nightly/src/main/perf/RandomQuery.java'
else:
  sources = '/l/util/src/main/perf/IndexAndSearchOSMShapes.java /l/util/src/main/perf/RandomQuery.java'

if os.system('javac -cp build/test-framework/classes/java:build/codecs/classes/java:build/core/classes/java:build/sandbox/classes/java:build/spatial/classes/java:build/spatial3d/classes/java:build/spatial-extras/classes/java:spatial-extras/lib/spatial4j-0.7.jar:spatial-extras/lib/jts-core-1.15.0.jar %s' % sources):
  raise RuntimeError('compile failed')

results = {}
stats = {}
theMaxDoc = None

def printResults(results, stats, maxDoc):
  print()
  print('Results on %2fM shapes:' % (maxDoc/1000000.))

  print()

  if '-reindex' in sys.argv:
    print('||Approach||Index time (sec)||Force merge time (sec)||Index size (GB)||Reader heap (MB)||')
    for approach in ('geohash_term', 'geohash_rpt', 'quadtree_rpt', 'packedquadtree_rpt', 'latLonShape'):
      if approach in stats:
        readerHeapMB, indexSizeGB, indexTimeSec, forceMergeTimeSec = stats[approach]
        print('|%s|%.1fs|%.1fs|%.2f|%.2f|' % (approach, indexTimeSec, forceMergeTimeSec, indexSizeGB, readerHeapMB))
  else:
    print('||Approach||Index size (GB)||Reader heap (MB)||')
    for approach in ('geohash_term', 'geohash_rpt', 'quadtree_rpt', 'packedquadtree_rpt', 'latLonShape'):
      if approach in stats:
        readerHeapMB, indexSizeGB = stats[approach][:2]
        print('|%s|%.2f|%.2f|' % (approach, indexSizeGB, readerHeapMB))

  print()
  print('||Shape||Approach||M hits/sec||QPS||Hit count||')
  for shape in ('box', 'poly 10', 'polyMedium'):
    for approach in ('geohash_term', 'geohash_rpt', 'quadtree_rpt', 'packedquadtree_rpt', 'latLonShape'):
      tup = shape, approach
      if tup in results:
        qps, mhps, totHits = results[tup]
        print('|%s|%s|%.2f|%.2f|%d|' % (shape, approach, mhps, qps, totHits))
      else:
        print('|%s|%s||||' % (shape, approach))


didReIndex = set()

t0 = time.time()

if nightly:
  logFileName = '%s/%s.log.txt' % (GEO_LOGS_DIR, timeStamp)
else:
  logFileName = '/l/logs/geoshapeBenchLog.txt'

rev = os.popen('git rev-parse HEAD').read().strip()
print('git head revision %s' % rev)
print('\nNOTE: logging all output to %s; saving results to %s\n' % (logFileName, resultsFileName))


def runProcess(utilSrcDir, approach, strategy, shape, shapeCmd, extra):
  global theMaxDoc
  global stats
  global results

  log.write('%7.1fs: -%s -%s %s\n' % (time.time()-t0, approach, shapeCmd, extra))
  p = subprocess.Popen('java -Xmx10g -cp %s:build/test-framework/classes/java:build/codecs/classes/java:build/core/classes/java:build/sandbox/classes/java:build/spatial/classes/java:build/spatial3d/classes/java:build/spatial-extras/classes/java:spatial-extras/lib/spatial4j-0.7.jar:spatial-extras/lib/jts-core-1.15.0.jar perf.IndexAndSearchOSMShapes -%s -%s %s' % (utilSrcDir, approach, shapeCmd, extra), shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

  totHits = None
  indexSizeGB = None
  readerHeapMB = None
  maxDoc = None
  indexTimeSec = 0.0
  forceMergeTimeSec = 0.0

  if strategy != None:
    indexKey = approach + '_' + strategy
  else:
    indexKey = approach

  while True:
    line = p.stdout.readline().decode('utf-8')
    if len(line) == 0:
      break
    line = line.rstrip()
    m = reTotHits.search(line)
    if m is not None:
      x = m.group(1)
      if totHits is None:
        totHits = x
      elif totHits != x:
        raise RuntimeError('total hits changed from %s to %s' % (totHits, x))
    log.write('%7.1fs: %s, %s: %s\n' % (time.time()-t0, indexKey, shape, line))
    doPrintLine = False
    if line.find('...') != -1 or line.find('ITER') != -1 or line.find('***') != -1:
      doPrintLine = True
    if line.startswith('BEST QPS: '):
      doPrintLine = True
      results[(shape, indexKey)] = (float(line[10:]), bestMHPS, int(totHits))
      pickle.dump((rev, stats, results), open(resultsFileName, 'wb'))
    if line.startswith('BEST M hits/sec: '):
      doPrintLine = True
      bestMHPS = float(line[17:])
    if line.startswith('INDEX SIZE: '):
      doPrintLine = True
      indexSizeGB = float(line[12:-3])
    if line.startswith('READER MB: '):
      doPrintLine = True
      readerHeapMB = float(line[11:])
    if line.startswith('maxDoc='):
      maxDoc = int(line[7:])
      doPrintLine = True
    i = line.find(' sec to index part ')
    if i != -1:
      doPrintLine = True
      indexTimeSec += float(line[:i])
    i = line.find(' sec to force merge part ')
    if i != -1:
      doPrintLine = True
      forceMergeTimeSec += float(line[:i])

    if doPrintLine:
      print('%7.1fs: %s, %s: %s' % (time.time()-t0, indexKey, shape, line))

  if maxDoc is None:
    raise RuntimeError('did not see maxDoc')

  tup = readerHeapMB, indexSizeGB, indexTimeSec, forceMergeTimeSec

  if indexKey not in stats:
    print('adding approach %s to stats' % indexKey)
    stats[indexKey] = tup
  elif stats[indexKey][:2] != tup[:2]:
    raise RuntimeError('stats changed for %s: %s vs %s' % (indexKey, stats[indexKey], tup))

  if theMaxDoc is None:
    theMaxDoc = maxDoc
  elif maxDoc != theMaxDoc:
    raise RuntimeError('maxDoc changed from %s to %s' % (theMaxDoc, maxDoc))

  printResults(results, stats, maxDoc)


# TODO: filters
with open(logFileName, 'w') as log:
  log.write('\ngit head revision %s\n' % rev)
  for shape in ('polyMedium', 'poly 10', 'box'):
    for approach in ('geohash', 'quadtree', 'packedquadtree', 'latLonShape'):

      if not haveFullPolygon and shape in ('polyMedium', 'polyRussia'):
        continue

      if shape == 'polyRussia' and not haveRussia:
        continue

      if '-reindex' in sys.argv and approach not in didReIndex:
        extra = ' -reindex'
        didReIndex.add(approach)
      else:
        extra = ''

      if '-full' in sys.argv:
        extra = extra + ' -full'

      if shape == 'sort':
        shapeCmd = 'sort -box'
      else:
        shapeCmd = shape

      if nightly:
        utilSrcDir = '/l/util.nightly/src/main'
      else:
        utilSrcDir = '/l/util/src/main'

      prefixXtra = extra

      if approach == 'quadtree' or approach == 'packedquadtree':
        extra = extra + ' -rpt'
        runProcess(utilSrcDir, approach, 'rpt', shape, shapeCmd, extra)
      elif approach == 'geohash':
        for strategy in ('rpt', 'term'):
          extra = prefixXtra + ' -' + strategy
          runProcess(utilSrcDir, approach, strategy, shape, shapeCmd, extra)
      else:
        runProcess(utilSrcDir, approach, None, shape, shapeCmd, extra)

if nightly:
  os.system('bzip2 --best %s' % logFileName)

print('Took %.1f sec to run all geo benchmarks' % (time.time()-t0))
