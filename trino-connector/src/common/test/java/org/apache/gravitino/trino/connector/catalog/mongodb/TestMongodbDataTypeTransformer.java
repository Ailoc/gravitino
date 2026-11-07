/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.gravitino.trino.connector.catalog.mongodb;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static io.trino.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_MILLIS;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;
import static io.trino.spi.type.VarcharType.createVarcharType;

import io.trino.spi.TrinoException;
import io.trino.spi.type.CharType;
import io.trino.spi.type.TimeType;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MongodbDataTypeTransformer}. */
public class TestMongodbDataTypeTransformer {

  private final MongodbDataTypeTransformer transformer = new MongodbDataTypeTransformer();

  @Test
  void testStringBecomesUnboundedVarchar() {
    Assertions.assertEquals(
        createUnboundedVarcharType(), transformer.getTrinoType(Types.StringType.get()));
  }

  @Test
  void testUnboundedVarcharBecomesString() {
    Assertions.assertEquals(
        Types.StringType.get(), transformer.getGravitinoType(createUnboundedVarcharType()));
  }

  @Test
  void testBoundedVarcharBecomesString() {
    // MongoDB stores all strings as unbounded; bounded varchar from Trino maps to Gravitino string.
    Assertions.assertEquals(
        Types.StringType.get(), transformer.getGravitinoType(createVarcharType(100)));
  }

  @Test
  void testCharTypeBecomesString() {
    Assertions.assertEquals(
        Types.StringType.get(), transformer.getGravitinoType(CharType.createCharType(50)));
  }

  @Test
  void testTimeIsRejected() {
    TrinoException e =
        Assertions.assertThrows(
            TrinoException.class, () -> transformer.getGravitinoType(TimeType.createTimeType(3)));
    Assertions.assertTrue(
        e.getMessage().contains("MongoDB does not support the datatype TIME"), e.getMessage());
  }

  @Test
  void testBooleanPassesThrough() {
    Assertions.assertEquals(Types.BooleanType.get(), transformer.getGravitinoType(BOOLEAN));
    Assertions.assertEquals(BOOLEAN, transformer.getTrinoType(Types.BooleanType.get()));
  }

  @Test
  void testLongAndBigintPassThrough() {
    Assertions.assertEquals(Types.LongType.get(), transformer.getGravitinoType(BIGINT));
    Assertions.assertEquals(BIGINT, transformer.getTrinoType(Types.LongType.get()));
  }

  @Test
  void testDoublePassesThrough() {
    Assertions.assertEquals(Types.DoubleType.get(), transformer.getGravitinoType(DOUBLE));
    Assertions.assertEquals(DOUBLE, transformer.getTrinoType(Types.DoubleType.get()));
  }

  @Test
  void testDatePassesThrough() {
    Assertions.assertEquals(Types.DateType.get(), transformer.getGravitinoType(DATE));
    Assertions.assertEquals(DATE, transformer.getTrinoType(Types.DateType.get()));
  }

  @Test
  void testTimestampPassesThrough() {
    Assertions.assertEquals(
        Types.TimestampType.withoutTimeZone(3), transformer.getGravitinoType(TIMESTAMP_MILLIS));
    Assertions.assertEquals(
        TIMESTAMP_MILLIS, transformer.getTrinoType(Types.TimestampType.withoutTimeZone(3)));
  }

  @Test
  void testTimestampWithTimeZonePassesThrough() {
    Assertions.assertEquals(
        Types.TimestampType.withTimeZone(3), transformer.getGravitinoType(TIMESTAMP_TZ_MILLIS));
    Assertions.assertEquals(
        TIMESTAMP_TZ_MILLIS, transformer.getTrinoType(Types.TimestampType.withTimeZone(3)));
  }

  @Test
  void testBinaryPassesThrough() {
    Assertions.assertEquals(Types.BinaryType.get(), transformer.getGravitinoType(VARBINARY));
    Assertions.assertEquals(VARBINARY, transformer.getTrinoType(Types.BinaryType.get()));
  }
}
