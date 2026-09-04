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

package org.apache.gravitino.trino.connector.catalog.jdbc.oracle;

import io.trino.spi.TrinoException;
import io.trino.spi.type.CharType;
import io.trino.spi.type.TimeType;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.TimestampWithTimeZoneType;
import io.trino.spi.type.VarcharType;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link OracleDataTypeTransformer}. */
public class TestOracleDataTypeTransformer {

  private final OracleDataTypeTransformer transformer = new OracleDataTypeTransformer();

  @Test
  void testStringBecomesUnboundedVarchar() {
    Assertions.assertEquals(
        VarcharType.createUnboundedVarcharType(), transformer.getTrinoType(Types.StringType.get()));
  }

  @Test
  void testUnboundedVarcharBecomesString() {
    Assertions.assertEquals(
        Types.StringType.get(),
        transformer.getGravitinoType(VarcharType.createUnboundedVarcharType()));
  }

  @Test
  void testBoundedCharacterTypesPassThrough() {
    Assertions.assertEquals(
        Types.FixedCharType.of(1), transformer.getGravitinoType(CharType.createCharType(1)));
    Assertions.assertEquals(
        Types.VarCharType.of(100),
        transformer.getGravitinoType(VarcharType.createVarcharType(100)));
  }

  @Test
  void testCharacterLengthsAboveOracleLimitsAreNotRejected() {
    // Oracle caps CHAR at 2000 bytes and VARCHAR2 at 4000 characters, but the Oracle catalog
    // widens longer values to VARCHAR2 or CLOB itself, so rejecting them here would lose the
    // ability to create a CLOB-backed column from Trino.
    Assertions.assertEquals(
        Types.FixedCharType.of(65536),
        transformer.getGravitinoType(CharType.createCharType(65536)));
    Assertions.assertEquals(
        Types.VarCharType.of(65536),
        transformer.getGravitinoType(VarcharType.createVarcharType(65536)));
  }

  @Test
  void testZeroLengthCharIsRejected() {
    TrinoException e =
        Assertions.assertThrows(
            TrinoException.class, () -> transformer.getGravitinoType(CharType.createCharType(0)));
    Assertions.assertTrue(
        e.getMessage().contains("Oracle does not support the datatype CHAR with the length 0"),
        e.getMessage());
  }

  @Test
  void testTimeIsRejected() {
    // Oracle has no time-of-day type; its DATE carries the time down to the second instead.
    TrinoException e =
        Assertions.assertThrows(
            TrinoException.class, () -> transformer.getGravitinoType(TimeType.createTimeType(3)));
    Assertions.assertTrue(
        e.getMessage().contains("Oracle does not support the datatype TIME"), e.getMessage());
  }

  @Test
  void testTimestampWithinOraclePrecision() {
    Assertions.assertEquals(
        Types.TimestampType.withoutTimeZone(9),
        transformer.getGravitinoType(TimestampType.createTimestampType(9)));
    Assertions.assertEquals(
        Types.TimestampType.withTimeZone(9),
        transformer.getGravitinoType(TimestampWithTimeZoneType.createTimestampWithTimeZoneType(9)));
  }

  @Test
  void testTimestampBeyondOraclePrecisionIsRejected() {
    // Trino accepts a timestamp precision up to 12, which would render as an invalid
    // TIMESTAMP(12) in Oracle DDL.
    TrinoException e =
        Assertions.assertThrows(
            TrinoException.class,
            () -> transformer.getGravitinoType(TimestampType.createTimestampType(12)));
    Assertions.assertTrue(
        e.getMessage().contains("Oracle does not support the datatype TIMESTAMP"), e.getMessage());

    e =
        Assertions.assertThrows(
            TrinoException.class,
            () ->
                transformer.getGravitinoType(
                    TimestampWithTimeZoneType.createTimestampWithTimeZoneType(10)));
    Assertions.assertTrue(
        e.getMessage().contains("Oracle does not support the datatype TIMESTAMP WITH TIME ZONE"),
        e.getMessage());
  }

  @Test
  void testOracleDateSurfacesAsSecondPrecisionTimestamp() {
    // The Oracle catalog reads Oracle DATE as a timestamp with precision 0 because the type stores
    // a time of day, and Trino's own Oracle connector exposes it the same way.
    Type oracleDate = Types.TimestampType.withoutTimeZone(0);
    Assertions.assertEquals(
        TimestampType.createTimestampType(0), transformer.getTrinoType(oracleDate));
  }

  @Test
  void testTimestampWithTimeZoneSurfacesWithItsPrecision() {
    Assertions.assertEquals(
        TimestampWithTimeZoneType.createTimestampWithTimeZoneType(6),
        transformer.getTrinoType(Types.TimestampType.withTimeZone(6)));
  }
}
