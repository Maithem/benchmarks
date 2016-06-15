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

import java.io.File;
import static java.io.File.createTempFile;
import java.io.IOException;
import static java.lang.Boolean.TRUE;
import static java.lang.System.setProperty;
import java.nio.ByteBuffer;
import static java.nio.ByteBuffer.allocateDirect;

import org.fusesource.lmdbjni.*;

import static org.fusesource.lmdbjni.DirectBuffer.DISABLE_BOUNDS_CHECKS_PROP_NAME;
import static org.fusesource.lmdbjni.DirectBuffer.SHOULD_BOUNDS_CHECK;
import static org.fusesource.lmdbjni.GetOp.FIRST;

import org.lmdbjava.LmdbException;

final class LmdbJni extends AbstractStore {

  private static final int POSIX_MODE = 0664;
  static final String LMDBJNI = "lmdbjni";

  static {
    setProperty(DISABLE_BOUNDS_CHECKS_PROP_NAME, TRUE.toString());
  }
  private Cursor cursor;
  private final Database db;
  private final Env env;
  private final DirectBuffer keyDb;
  private Transaction tx;
  private final DirectBuffer valDb;

  LmdbJni(final ByteBuffer key, final ByteBuffer val) throws LmdbException,
                                                             IOException {
    super(key,
          val,
          allocateDirect(key.capacity()),
          allocateDirect(val.capacity()));

    if (SHOULD_BOUNDS_CHECK) {
      throw new IllegalStateException();
    }

    keyDb = new DirectBuffer(key);
    valDb = new DirectBuffer(val);

    final File tmp = createTempFile("bench", ".db");
    env = new org.fusesource.lmdbjni.Env();
    env.setMapSize(1_024 * 1_024 * 1_024);
    env.setMaxDbs(1);
    env.setMaxReaders(1);
    final int noSubDir = 0x4000;
    env.open(tmp.getAbsolutePath(), noSubDir, POSIX_MODE);

    final Transaction dbCreate = env.createWriteTransaction();
    final int create = 0x4_0000;
    db = env.openDatabase(dbCreate, "db", create);
    dbCreate.commit();
  }

  @Override
  void crc32() throws Exception {
    keyDb.wrap(roKey);
    valDb.wrap(roVal);
    try (final BufferCursor c = db.bufferCursor(tx, keyDb, valDb)) {
      if (c.first()) {
        do {
          keyDb.getBytes(0, roKey, roKey.capacity());
          valDb.getBytes(0, roVal, roVal.capacity());
          roKey.flip();
          roVal.flip();
          CRC.update(roKey);
          CRC.update(roVal);
          roKey.flip();
          roVal.clear();
        } while (c.next());
      }
    }
  }

  @Override
  void cursorGetFirst() throws Exception {
    cursor.position(keyDb, valDb, FIRST);
    keyDb.getBytes(0, roKey, roKey.capacity());
    roKey.flip();
    valDb.getBytes(0, roVal, roVal.capacity());
    roVal.flip();
  }

  @Override
  void finishCrcPhase() throws Exception {
    tx.abort();
  }

  @Override
  void get() throws Exception {
    if (cursor.seekPosition(keyDb, valDb, SeekOp.KEY) != 0) {
      throw new IllegalStateException();
    }
    valDb.getBytes(0, roVal, roVal.capacity());
    roVal.flip();
  }

  @Override
  void put() throws Exception {
    if (cursor.put(keyDb, valDb, 0) != 0) {
      throw new IllegalStateException();
    }
  }

  @Override
  void startReadPhase() throws Exception {
    keyDb.wrap(key);
    valDb.wrap(roVal);
  }

  @Override
  void startWritePhase() throws Exception {
    tx = env.createWriteTransaction();
    cursor = db.openCursor(tx);
    keyDb.wrap(key);
    valDb.wrap(val);
  }

  @Override
  long sumData() throws Exception {
    long result = 0;
    keyDb.wrap(roKey);
    valDb.wrap(roVal);
    try (final BufferCursor c = db.bufferCursor(tx, keyDb, valDb)) {
      if (c.first()) {
        do {
          result += keyDb.capacity();
          result += valDb.capacity();
        } while (c.next());
      }
    }
    return result;
  }

}
