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

import java.nio.ByteBuffer;
import static java.nio.ByteBuffer.allocateDirect;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static net.openhft.hashing.LongHashFunction.xx_r39;
import static org.lmdbjava.ByteBufferProxy.PROXY_OPTIMAL;
import static org.lmdbjava.ByteBufferProxy.PROXY_SAFE;
import org.lmdbjava.Cursor;
import org.lmdbjava.EnvFlags;
import static org.lmdbjava.GetOp.MDB_SET_KEY;
import org.lmdbjava.PutFlags;
import static org.lmdbjava.PutFlags.MDB_APPEND;
import static org.lmdbjava.SeekOp.MDB_FIRST;
import static org.lmdbjava.SeekOp.MDB_LAST;
import static org.lmdbjava.SeekOp.MDB_NEXT;
import static org.lmdbjava.SeekOp.MDB_PREV;
import org.lmdbjava.Txn;
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

@OutputTimeUnit(MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 3)
@BenchmarkMode(SampleTime)
public class LmdbJavaByteBuffer {

  @Benchmark
  public void readCrc(final Reader r, final Blackhole bh) throws Exception {
    r.crc.reset();
    bh.consume(r.c.seek(MDB_FIRST));
    do {
      r.crc.update(r.txn.key());
      r.crc.update(r.txn.val());
    } while (r.c.seek(MDB_NEXT));
    bh.consume(r.crc.getValue());
  }

  @Benchmark
  public void readKey(final Reader r, final Blackhole bh) throws Exception {
    for (final int key : r.keys) {
      r.rwKey.clear();
      if (r.intKey) {
        r.rwKey.putInt(0, key).flip();
      } else {
        final byte[] str = r.padKey(key).getBytes();
        r.rwKey.put(str, 0, str.length).flip();
      }
      bh.consume(r.c.get(r.rwKey, MDB_SET_KEY));
      bh.consume(r.txn.val());
    }
  }

  @Benchmark
  public void readRev(final Reader r, final Blackhole bh) throws Exception {
    bh.consume(r.c.seek(MDB_LAST));
    do {
      bh.consume(r.txn.val());
    } while (r.c.seek(MDB_PREV));
  }

  @Benchmark
  public void readSeq(final Reader r, final Blackhole bh) throws Exception {
    bh.consume(r.c.seek(MDB_FIRST));
    do {
      bh.consume(r.txn.val());
    } while (r.c.seek(MDB_NEXT));
  }

  @Benchmark
  public void readXxh64(final Reader r, final Blackhole bh) throws Exception {
    long result = 0;
    bh.consume(r.c.seek(MDB_FIRST));
    do {
      result += xx_r39().hashBytes(r.txn.key());
      result += xx_r39().hashBytes(r.txn.val());
    } while (r.c.seek(MDB_NEXT));
    bh.consume(result);
  }

  @Benchmark
  public void write(final Writer w, final Blackhole bh) throws Exception {
    w.write();
  }

  @State(Benchmark)
  public static class LmdbJava extends CommonLmdbJava<ByteBuffer> {

    ByteBuffer rwKey;
    ByteBuffer rwVal;

    @Override
    public void setup(BenchmarkParams b, final boolean sync) throws
        Exception {
      super.setup(b, sync);
      rwKey = allocateDirect(keySize).order(LITTLE_ENDIAN);
      rwVal = allocateDirect(valSize);
    }

    void write() throws Exception {
      try (final Txn<ByteBuffer> tx = env.txnWrite();) {
        try (final Cursor<ByteBuffer> c = db.openCursor(tx);) {
          final PutFlags flags = sequential ? MDB_APPEND : null;
          final int rndByteMax = RND_MB.length - valSize;
          int rndByteOffset = 0;
          for (final int key : keys) {
            rwKey.clear();
            rwVal.clear();
            if (intKey) {
              rwKey.putInt(0, key).flip();
            } else {
              final byte[] str = padKey(key).getBytes();
              rwKey.put(str, 0, str.length).flip();
            }
            if (valRandom) {
              rwVal.put(RND_MB, rndByteOffset, valSize).flip();
              rndByteOffset += valSize;
              if (rndByteOffset >= rndByteMax) {
                rndByteOffset = 0;
              }
            } else {
              rwVal.putInt(0, key).flip();
            }
            c.put(rwKey, rwVal, flags);
          }
        }
        tx.commit();
      }
    }

  }

  @State(Benchmark)
  public static class Reader extends LmdbJava {

    Cursor<ByteBuffer> c;

    /**
     * Whether the byte buffer accessor is safe or not
     */
    @Param({"false"})
    boolean forceSafe;

    Txn<ByteBuffer> txn;

    @Setup(Trial)
    @Override
    public void setup(BenchmarkParams b) throws Exception {
      bufferProxy = forceSafe ? PROXY_SAFE : PROXY_OPTIMAL;
      super.setup(b, false);
      super.write();
      txn = env.txnRead();
      c = db.openCursor(txn);
    }

    @TearDown(Trial)
    @Override
    public void teardown() throws Exception {
      c.close();
      txn.abort();
      super.teardown();
    }
  }

  @State(Benchmark)
  public static class Writer extends LmdbJava {

    /**
     * Whether {@link EnvFlags#MDB_NOSYNC} is used.
     */
    @Param({"false"})
    boolean sync;

    @Setup(Invocation)
    @Override
    public void setup(BenchmarkParams b) throws Exception {
      bufferProxy = PROXY_OPTIMAL;
      super.setup(b, sync);
    }

    @TearDown(Invocation)
    @Override
    public void teardown() throws Exception {
      super.teardown();
    }
  }

}
