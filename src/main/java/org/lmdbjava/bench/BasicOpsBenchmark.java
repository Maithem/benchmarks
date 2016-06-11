package org.lmdbjava.bench;


import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.reflect.Constructor;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.lmdbjava.bench.AbstractStore.constructor;
import static org.lmdbjava.bench.LmdbJava.LMDBJAVA;
import static org.lmdbjava.bench.LmdbJni.LMDBJNI;
import static org.openjdk.jmh.annotations.Level.Iteration;
import static org.openjdk.jmh.annotations.Mode.AverageTime;
import static org.openjdk.jmh.annotations.Scope.Thread;

@State(Thread)
@OutputTimeUnit(NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1, timeUnit = SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = SECONDS)
@BenchmarkMode(AverageTime)
public class BasicOpsBenchmark {

  private static final CRC32 CRC = new CRC32();

  private static final Random RND = new SecureRandom();
  private static final Map<String, Constructor<? extends AbstractStore>> STORES
    = new HashMap<>();

  static {
    STORES.put(LMDBJNI, constructor(LmdbJni.class));
    STORES.put(LMDBJAVA, constructor(LmdbJava.class));
  }

  @Param({"false"})
  private boolean random;

  @Param({"15000", "30000"})
  private long size;

  @Param(value = {LMDBJAVA, LMDBJNI})
  private String store;

  private AbstractStore target;

  private byte[] valByteRnd;

  @Param({"512"})
  private int valBytes;

  @Setup(value = Iteration)
  public void setup() throws Exception {
    if (!STORES.containsKey(store)) {
      throw new IllegalArgumentException("Unknown store: '" + store + "'");
    }
    this.target = AbstractStore.create(STORES.get(store), Long.BYTES, valBytes);
    this.valByteRnd = new byte[valBytes];
    this.target.startWritePhase();
    this.target.put();
  }

  @Benchmark
  public void get(Blackhole bh) throws Exception {
    this.target.get();
  }

  @Benchmark
  public void cursorGet(Blackhole bh) throws Exception {
    this.target.cursorGetFirst();
  }


}
