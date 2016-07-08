/*
 * Copyright 2016 The LmdbJava Project, http://lmdbjava.org/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lmdbjava.bench;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static net.openhft.hashing.LongHashFunction.xx_r39;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import static org.openjdk.jmh.annotations.Level.Invocation;
import static org.openjdk.jmh.annotations.Level.Trial;
import org.openjdk.jmh.annotations.Measurement;
import static org.openjdk.jmh.annotations.Mode.SampleTime;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import static org.openjdk.jmh.annotations.Scope.Benchmark;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;
import static org.rocksdb.CompressionType.NO_COMPRESSION;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import static org.rocksdb.RocksDB.loadLibrary;
import static org.rocksdb.RocksDB.open;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

@OutputTimeUnit(MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 3)
@BenchmarkMode(SampleTime)
public class RocksDb {

  @Benchmark
  public void readCrc(final Reader r, final Blackhole bh) throws Exception {
    r.crc.reset();
    final RocksIterator iterator = r.db.newIterator();
    iterator.seekToFirst();
    while (iterator.isValid()) {
      r.crc.update(iterator.key());
      r.crc.update(iterator.value());
      iterator.next();
    }
    bh.consume(r.crc.getValue());
  }

  @Benchmark
  public void readKey(final Reader r, final Blackhole bh) throws Exception {
    for (final int key : r.keys) {
      if (r.intKey) {
        r.wkb.putInt(0, key);
      } else {
        r.wkb.putStringWithoutLengthUtf8(0, r.padKey(key));
      }
      bh.consume(r.db.get(r.wkb.byteArray(), r.wvb.byteArray()));
    }
  }

  @Benchmark
  public void readRev(final Reader r, final Blackhole bh) throws Exception {
    final RocksIterator iterator = r.db.newIterator();
    iterator.seekToLast();
    while (iterator.isValid()) {
      bh.consume(iterator.value());
      iterator.prev();
    }
  }

  @Benchmark
  public void readSeq(final Reader r, final Blackhole bh) throws Exception {
    final RocksIterator iterator = r.db.newIterator();
    iterator.seekToFirst();
    while (iterator.isValid()) {
      bh.consume(iterator.value());
      iterator.next();
    }
  }

  @Benchmark
  public void readXxh64(final Reader r, final Blackhole bh) throws Exception {
    long result = 0;
    final RocksIterator iterator = r.db.newIterator();
    iterator.seekToFirst();
    while (iterator.isValid()) {
      result += xx_r39().hashBytes(iterator.key());
      result += xx_r39().hashBytes(iterator.value());
      iterator.next();
    }
    bh.consume(result);
  }

  @Benchmark
  public void write(final Writer w, final Blackhole bh) throws Exception {
    w.write(w.batchSize);
  }

  @State(value = Benchmark)
  public static class CommonRocksDb extends Common {

    RocksDB db;

    /**
     * Writable key buffer. Backed by a plain byte[] for RocksDB API ease.
     */
    MutableDirectBuffer wkb;

    /**
     * Writable value buffer. Backed by a plain byte[] for RocksDB API ease.
     */
    MutableDirectBuffer wvb;

    @Override
    public void setup(BenchmarkParams b) throws Exception {
      super.setup(b);
      wkb = new UnsafeBuffer(new byte[keySize]);
      wvb = new UnsafeBuffer(new byte[valSize]);
      loadLibrary();
      final Options options = new Options();
      options.setCreateIfMissing(true);
      options.setCompressionType(NO_COMPRESSION);
      db = open(options, tmp.getAbsolutePath());
    }

    @Override
    public void teardown() throws Exception {
      reportSpaceBeforeClose();
      if (db != null) {
        db.close();
      }
      super.teardown();
    }

    void write(final int batchSize) throws Exception {
      final int rndByteMax = RND_MB.length - valSize;
      int rndByteOffset = 0;

      final WriteBatch batch = new WriteBatch();
      final WriteOptions opt = new WriteOptions();
      for (int i = 0; i < keys.length; i++) {
        final int key = keys[i];
        if (intKey) {
          wkb.putInt(0, key, LITTLE_ENDIAN);
        } else {
          wkb.putStringWithoutLengthUtf8(0, padKey(key));
        }
        if (valRandom) {
          wvb.putBytes(0, RND_MB, rndByteOffset, valSize);
          rndByteOffset += valSize;
          if (rndByteOffset >= rndByteMax) {
            rndByteOffset = 0;
          }
        } else {
          wvb.putInt(0, key);
        }
        batch.put(wkb.byteArray(), wvb.byteArray());
        if (i % batchSize == 0) {
          db.write(opt, batch);
          batch.clear();
        }
      }
      db.write(opt, batch); // possible partial batch
      batch.clear();
    }
  }

  @State(Benchmark)
  public static class Reader extends CommonRocksDb {

    @Setup(Trial)
    @Override
    public void setup(BenchmarkParams b) throws Exception {
      super.setup(b);
      super.write(num);
    }

    @TearDown(Trial)
    @Override
    public void teardown() throws Exception {
      super.teardown();
    }
  }

  @State(Benchmark)
  public static class Writer extends CommonRocksDb {

    @Param({"1000000"})
    int batchSize;

    @Setup(Invocation)
    @Override
    public void setup(BenchmarkParams b) throws Exception {
      super.setup(b);
    }

    @TearDown(Invocation)
    @Override
    public void teardown() throws Exception {
      super.teardown();
    }
  }

}
