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
package org.apache.gravitino.catalog.sqlserver.converter;

import static org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter.DATE;
import static org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter.TEXT;
import static org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter.TIME;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.BIGINT;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.BINARY;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.BIT;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.CHAR;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.DATETIME;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.DATETIME2;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.DATETIMEOFFSET;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.DECIMAL;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.DEFAULT_MONEY_PRECISION;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.DEFAULT_SMALLMONEY_PRECISION;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.FLOAT;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.IDENTITY_MARKER;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.IMAGE;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.INT;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.MONEY;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.MONEY_SCALE;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.NCHAR;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.NTEXT;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.NUMERIC;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.NVARCHAR;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.REAL;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.ROWVERSION;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.SMALLDATETIME;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.SMALLINT;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.SMALLMONEY;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.TINYINT;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.UNIQUEIDENTIFIER;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.UNSIZED_LENGTH;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.VARBINARY;
import static org.apache.gravitino.catalog.sqlserver.converter.SqlServerTypeConverter.VARCHAR;

import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit test for {@link SqlServerTypeConverter}. */
public class TestSqlServerTypeConverter {

  private static final SqlServerTypeConverter CONVERTER = new SqlServerTypeConverter();

  private static final String XML_TYPE = "xml";

  @Test
  public void testNumericTypesToGravitino() {
    checkToGravitino(Types.BooleanType.get(), BIT, null, null);
    checkToGravitino(Types.ShortType.get(), SMALLINT, null, null);
    checkToGravitino(Types.IntegerType.get(), INT, null, null);
    checkToGravitino(Types.LongType.get(), BIGINT, null, null);
    checkToGravitino(Types.FloatType.get(), REAL, null, null);
    checkToGravitino(Types.DoubleType.get(), FLOAT, null, null);
  }

  /**
   * The driver folds the identity property into the type name, reporting {@code bigint identity}
   * rather than {@code bigint}. Identity is a property of the column and not a type of its own, and
   * Gravitino carries it as the column's auto-increment flag, so the marker has to be stripped
   * before the name is matched or the column degrades to an external type.
   */
  @Test
  public void testIdentityMarkerIsStrippedFromTypeName() {
    Assertions.assertEquals(" identity", IDENTITY_MARKER);
    checkToGravitino(Types.IntegerType.get(), INT + IDENTITY_MARKER, null, null);
    checkToGravitino(Types.LongType.get(), BIGINT + IDENTITY_MARKER, null, null);
    checkToGravitino(Types.ShortType.get(), SMALLINT + IDENTITY_MARKER, null, null);
    Assertions.assertNotEquals(
        Types.ExternalType.of(BIGINT + IDENTITY_MARKER),
        CONVERTER.toGravitino(typeBean(BIGINT + IDENTITY_MARKER)));
  }

  /**
   * SQL Server TINYINT is unsigned (0-255), so it cannot round-trip through Gravitino's signed
   * ByteType and has to widen to ShortType.
   */
  @Test
  public void testTinyintIsUnsignedAndWidensToShort() {
    checkToGravitino(Types.ShortType.get(), TINYINT, null, null);
    Assertions.assertEquals(SMALLINT, CONVERTER.fromGravitino(Types.ByteType.get()));
    Assertions.assertEquals(SMALLINT, CONVERTER.fromGravitino(Types.ShortType.get()));
  }

  @Test
  public void testDecimalTypesToGravitino() {
    checkToGravitino(Types.DecimalType.of(10, 2), DECIMAL, 10, 2);
    checkToGravitino(Types.DecimalType.of(38, 0), NUMERIC, 38, 0);
    checkToGravitino(Types.DecimalType.of(18, 3), NUMERIC, 18, 3);
    checkToGravitino(Types.DecimalType.of(DEFAULT_MONEY_PRECISION, MONEY_SCALE), MONEY, null, null);
    checkToGravitino(
        Types.DecimalType.of(DEFAULT_SMALLMONEY_PRECISION, MONEY_SCALE), SMALLMONEY, null, null);
    // money reports its own precision through column metadata when available.
    checkToGravitino(Types.DecimalType.of(19, 4), MONEY, 19, 4);
  }

  @Test
  public void testTemporalTypesToGravitino() {
    checkToGravitino(Types.DateType.get(), DATE, null, null);
    checkTemporal(Types.TimeType.of(0), TIME, 0);
    checkTemporal(Types.TimeType.of(7), TIME, 7);

    // datetime has a fixed ~3.33ms resolution, which calculateDatetimePrecision reports as 3.
    checkTemporal(Types.TimestampType.withoutTimeZone(3), DATETIME, 3);
    checkTemporal(Types.TimestampType.withoutTimeZone(0), SMALLDATETIME, 0);
    checkTemporal(Types.TimestampType.withoutTimeZone(7), DATETIME2, 7);
    checkTemporal(Types.TimestampType.withTimeZone(7), DATETIMEOFFSET, 7);

    // Without a reported precision the types stay precision-less rather than defaulting to 0.
    Assertions.assertEquals(
        Types.TimestampType.withoutTimeZone(), CONVERTER.toGravitino(typeBean(DATETIME2)));
    Assertions.assertEquals(
        Types.TimestampType.withTimeZone(), CONVERTER.toGravitino(typeBean(DATETIMEOFFSET)));
    Assertions.assertEquals(Types.TimeType.get(), CONVERTER.toGravitino(typeBean(TIME)));
  }

  @Test
  public void testCharacterTypesToGravitino() {
    checkToGravitino(Types.FixedCharType.of(10), CHAR, 10, null);
    checkToGravitino(Types.FixedCharType.of(10), NCHAR, 10, null);
    checkToGravitino(Types.VarCharType.of(255), VARCHAR, 255, null);
    checkToGravitino(Types.VarCharType.of(255), NVARCHAR, 255, null);
    checkToGravitino(Types.StringType.get(), TEXT, null, null);
    checkToGravitino(Types.StringType.get(), NTEXT, null, null);
  }

  /** The driver reports {@code -1} as the column size of the {@code max} length types. */
  @Test
  public void testUnsizedCharacterTypesToGravitino() {
    checkToGravitino(Types.StringType.get(), VARCHAR, UNSIZED_LENGTH, null);
    checkToGravitino(Types.StringType.get(), NVARCHAR, UNSIZED_LENGTH, null);
  }

  @Test
  public void testBinaryTypesToGravitino() {
    checkToGravitino(Types.BinaryType.get(), BINARY, null, null);
    checkToGravitino(Types.BinaryType.get(), VARBINARY, 100, null);
    checkToGravitino(Types.BinaryType.get(), IMAGE, null, null);
    // The unsized fallback to StringType is character specific; varbinary(max) stays binary.
    checkToGravitino(Types.BinaryType.get(), VARBINARY, UNSIZED_LENGTH, null);
  }

  /**
   * SQL Server's {@code timestamp} is a row version counter, not a point in time. Mapping it to a
   * timestamp type would silently corrupt reads.
   */
  @Test
  public void testRowVersionIsBinaryNotTimestamp() {
    Assertions.assertEquals("timestamp", ROWVERSION);
    checkToGravitino(Types.BinaryType.get(), ROWVERSION, 8, null);
    Assertions.assertNotEquals(
        Types.TimestampType.withoutTimeZone(), CONVERTER.toGravitino(typeBean(ROWVERSION)));
  }

  @Test
  public void testOtherTypesToGravitino() {
    checkToGravitino(Types.UUIDType.get(), UNIQUEIDENTIFIER, null, null);
    checkToGravitino(Types.ExternalType.of(XML_TYPE), XML_TYPE, null, null);
  }

  @Test
  public void testFromGravitino() {
    checkFromGravitino(BIT, Types.BooleanType.get());
    checkFromGravitino(INT, Types.IntegerType.get());
    checkFromGravitino(BIGINT, Types.LongType.get());
    checkFromGravitino(REAL, Types.FloatType.get());
    checkFromGravitino(FLOAT, Types.DoubleType.get());
    checkFromGravitino("decimal(10,2)", Types.DecimalType.of(10, 2));
    checkFromGravitino(DATE, Types.DateType.get());
    checkFromGravitino(UNIQUEIDENTIFIER, Types.UUIDType.get());
    checkFromGravitino(XML_TYPE, Types.ExternalType.of(XML_TYPE));
  }

  @Test
  public void testStringTypesPreferUnicode() {
    checkFromGravitino("nvarchar(max)", Types.StringType.get());
    checkFromGravitino("nvarchar(100)", Types.VarCharType.of(100));
    checkFromGravitino("nchar(10)", Types.FixedCharType.of(10));
    checkFromGravitino("varbinary(max)", Types.BinaryType.get());
  }

  /**
   * {@code nvarchar(n)} tops out at 4000 characters; anything longer has to spill over to {@code
   * nvarchar(max)}.
   */
  @Test
  public void testLongCharacterTypesFallBackToMax() {
    checkFromGravitino("nvarchar(4000)", Types.VarCharType.of(4000));
    checkFromGravitino("nvarchar(max)", Types.VarCharType.of(4001));
    checkFromGravitino("nchar(4000)", Types.FixedCharType.of(4000));
    checkFromGravitino("nvarchar(max)", Types.FixedCharType.of(4001));
  }

  @Test
  public void testTemporalTypesFromGravitino() {
    checkFromGravitino("time(3)", Types.TimeType.of(3));
    checkFromGravitino("datetime2(6)", Types.TimestampType.withoutTimeZone(6));
    checkFromGravitino("datetimeoffset(6)", Types.TimestampType.withTimeZone(6));
  }

  @Test
  public void testUnsupportedGravitinoTypeThrows() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> CONVERTER.fromGravitino(Types.UnparsedType.of("unknown")));
  }

  private static void checkToGravitino(
      Type expected, String typeName, Integer columnSize, Integer scale) {
    JdbcTypeConverter.JdbcTypeBean typeBean = typeBean(typeName);
    typeBean.setColumnSize(columnSize);
    typeBean.setScale(scale);
    Assertions.assertEquals(expected, CONVERTER.toGravitino(typeBean), "type: " + typeName);
  }

  private static void checkTemporal(Type expected, String typeName, Integer datetimePrecision) {
    JdbcTypeConverter.JdbcTypeBean typeBean = typeBean(typeName);
    typeBean.setDatetimePrecision(datetimePrecision);
    Assertions.assertEquals(expected, CONVERTER.toGravitino(typeBean), "type: " + typeName);
  }

  private static void checkFromGravitino(String expected, Type type) {
    Assertions.assertEquals(expected, CONVERTER.fromGravitino(type));
  }

  private static JdbcTypeConverter.JdbcTypeBean typeBean(String typeName) {
    return new JdbcTypeConverter.JdbcTypeBean(typeName);
  }
}
