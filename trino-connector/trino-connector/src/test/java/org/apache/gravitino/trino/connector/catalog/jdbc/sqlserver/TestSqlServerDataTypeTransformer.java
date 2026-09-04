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

package org.apache.gravitino.trino.connector.catalog.jdbc.sqlserver;

import io.trino.spi.TrinoException;
import io.trino.spi.type.BigintType;
import io.trino.spi.type.BooleanType;
import io.trino.spi.type.CharType;
import io.trino.spi.type.SmallintType;
import io.trino.spi.type.TimeType;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.TimestampWithTimeZoneType;
import io.trino.spi.type.VarcharType;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SqlServerDataTypeTransformer}. */
public class TestSqlServerDataTypeTransformer {

  private final SqlServerDataTypeTransformer transformer = new SqlServerDataTypeTransformer();

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
  void testCharacterLengthsAboveSqlServerLimitsAreNotRejected() {
    // SQL Server caps NCHAR and NVARCHAR at 4000 characters, but the SQL Server catalog widens
    // longer values to NVARCHAR(MAX) itself, so rejecting them here would lose the ability to
    // create an NVARCHAR(MAX)-backed column from Trino.
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
        e.getMessage().contains("SQL Server does not support the datatype CHAR with the length 0"),
        e.getMessage());
  }

  @Test
  void testTemporalTypesWithinSqlServerPrecision() {
    Assertions.assertEquals(
        Types.TimeType.of(7), transformer.getGravitinoType(TimeType.createTimeType(7)));
    Assertions.assertEquals(
        Types.TimestampType.withoutTimeZone(7),
        transformer.getGravitinoType(TimestampType.createTimestampType(7)));
    Assertions.assertEquals(
        Types.TimestampType.withTimeZone(7),
        transformer.getGravitinoType(TimestampWithTimeZoneType.createTimestampWithTimeZoneType(7)));
  }

  @Test
  void testTemporalPrecisionBeyondSqlServerIsRejected() {
    // Trino accepts a temporal precision up to 12, which would render as an invalid time(9) or
    // datetime2(9) in SQL Server DDL.
    TrinoException e =
        Assertions.assertThrows(
            TrinoException.class, () -> transformer.getGravitinoType(TimeType.createTimeType(9)));
    Assertions.assertTrue(
        e.getMessage().contains("SQL Server does not support the datatype TIME"), e.getMessage());

    e =
        Assertions.assertThrows(
            TrinoException.class,
            () -> transformer.getGravitinoType(TimestampType.createTimestampType(12)));
    Assertions.assertTrue(
        e.getMessage().contains("SQL Server does not support the datatype TIMESTAMP"),
        e.getMessage());

    e =
        Assertions.assertThrows(
            TrinoException.class,
            () ->
                transformer.getGravitinoType(
                    TimestampWithTimeZoneType.createTimestampWithTimeZoneType(8)));
    Assertions.assertTrue(
        e.getMessage()
            .contains("SQL Server does not support the datatype TIMESTAMP WITH TIME ZONE"),
        e.getMessage());
  }

  @Test
  void testDatetimeoffsetSurfacesAsTimestampWithTimeZone() {
    // The SQL Server catalog reads DATETIMEOFFSET as a timestamp with a time zone.
    Assertions.assertEquals(
        TimestampWithTimeZoneType.createTimestampWithTimeZoneType(7),
        transformer.getTrinoType(Types.TimestampType.withTimeZone(7)));
  }

  @Test
  void testBitAndTinyintMatchTheTrinoSqlServerConnector() {
    // SQL Server BIT is boolean, and its unsigned TINYINT does not fit a signed byte, so the SQL
    // Server catalog reports it as a short. Trino's own SQL Server connector maps both the same
    // way, which keeps the types stable whether or not Gravitino sits in the middle.
    Assertions.assertEquals(BooleanType.BOOLEAN, transformer.getTrinoType(Types.BooleanType.get()));
    Assertions.assertEquals(SmallintType.SMALLINT, transformer.getTrinoType(Types.ShortType.get()));
    Assertions.assertEquals(BigintType.BIGINT, transformer.getTrinoType(Types.LongType.get()));
  }
}
