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
package org.apache.gravitino.catalog.oracle.converter;

import static org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter.DATE;
import static org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter.TIMESTAMP;
import static org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter.VARCHAR;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.BINARY_DOUBLE;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.BINARY_FLOAT;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.BLOB;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.CHAR;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.CLOB;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.DECIMAL;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.FLOAT;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.FLOATING_NUMBER_SCALE;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.INTEGER;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.LONG;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.LONG_RAW;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.MAX_CHAR_LENGTH;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.MAX_NUMBER_PRECISION;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.MAX_VARCHAR2_LENGTH;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.NCHAR;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.NCLOB;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.NUMBER;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.NVARCHAR2;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.RAW;
import static org.apache.gravitino.catalog.oracle.converter.OracleTypeConverter.VARCHAR2;

import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit test for {@link OracleTypeConverter}. */
public class TestOracleTypeConverter {

  private static final OracleTypeConverter CONVERTER = new OracleTypeConverter();

  private static final String XMLTYPE = "XMLTYPE";

  @Test
  public void testNumberFamilyToGravitino() {
    // In Oracle these are all spellings of NUMBER, so they share one mapping.
    checkToGravitino(Types.DecimalType.of(10, 2), NUMBER, 10, 2);
    checkToGravitino(Types.DecimalType.of(10, 2), DECIMAL, 10, 2);
    checkToGravitino(Types.DecimalType.of(10, 0), INTEGER, 10, 0);
    checkToGravitino(Types.DecimalType.of(38, 0), NUMBER, 38, 0);
    checkToGravitino(Types.FloatType.get(), BINARY_FLOAT, null, null);
    checkToGravitino(Types.DoubleType.get(), BINARY_DOUBLE, null, null);
    checkToGravitino(Types.DoubleType.get(), FLOAT, 126, null);
  }

  /**
   * A {@code NUMBER} declared without precision is a floating decimal of up to 38 significant
   * digits. The driver reports it as a precision of zero paired with a scale of -127, which is a
   * marker rather than a real scale and must never reach Gravitino.
   */
  @Test
  public void testUnsizedNumberBecomesAFloatingDecimal() {
    checkToGravitino(
        Types.DecimalType.of(MAX_NUMBER_PRECISION, FLOATING_NUMBER_SCALE), NUMBER, 0, -127);
    // A null scale is treated the same way.
    checkToGravitino(
        Types.DecimalType.of(MAX_NUMBER_PRECISION, FLOATING_NUMBER_SCALE), NUMBER, null, null);
  }

  /**
   * Oracle allows a negative scale, which rounds the value to a multiple of {@code 10^|scale|} and
   * so widens the number of integer digits. Gravitino forbids a negative scale, so the digits are
   * folded into the precision instead.
   */
  @Test
  public void testNegativeScaleFoldsIntoPrecision() {
    checkToGravitino(Types.DecimalType.of(7, 0), NUMBER, 5, -2);
    // The fold still has to respect the 38 digit ceiling.
    checkToGravitino(Types.DecimalType.of(MAX_NUMBER_PRECISION, 0), NUMBER, 38, -5);
  }

  /**
   * Gravitino requires precision at most 38 and scale at most the precision, which Oracle does not.
   */
  @Test
  public void testOutOfRangePrecisionAndScaleAreCapped() {
    checkToGravitino(Types.DecimalType.of(38, 5), NUMBER, 40, 5);
    checkToGravitino(Types.DecimalType.of(10, 10), NUMBER, 10, 20);
  }

  /**
   * Oracle has no pure date type: {@code DATE} carries a time of day down to the second, so it maps
   * to a timestamp with precision zero rather than to {@link Types.DateType}.
   */
  @Test
  public void testDateCarriesTimeOfDay() {
    checkToGravitino(Types.TimestampType.withoutTimeZone(0), DATE, null, null);
  }

  @Test
  public void testTimestampFamilyToGravitino() {
    checkTemporal(Types.TimestampType.withoutTimeZone(6), TIMESTAMP + "(6)", 6);
    checkTemporal(Types.TimestampType.withoutTimeZone(0), TIMESTAMP, 0);
    checkTemporal(Types.TimestampType.withTimeZone(3), TIMESTAMP + "(3) with time zone", 3);
    // A local time zone column is stored in UTC and rendered in the session zone, so no offset
    // survives the round trip.
    checkTemporal(
        Types.TimestampType.withoutTimeZone(6), TIMESTAMP + "(6) with local time zone", 6);

    // Without a reported precision the type falls back to zero rather than staying precision-less.
    Assertions.assertEquals(
        Types.TimestampType.withoutTimeZone(0), CONVERTER.toGravitino(typeBean(TIMESTAMP)));
    Assertions.assertEquals(
        Types.TimestampType.withTimeZone(0),
        CONVERTER.toGravitino(typeBean(TIMESTAMP + " with time zone")));
  }

  /** The driver reports the type name in upper case and padded with runs of whitespace. */
  @Test
  public void testTimestampMatchingIgnoresCaseAndWhitespace() {
    checkTemporal(Types.TimestampType.withTimeZone(6), "TIMESTAMP(6)  WITH  TIME  ZONE", 6);
    checkTemporal(Types.TimestampType.withoutTimeZone(6), "  Timestamp(6)  ", 6);
  }

  @Test
  public void testCharacterTypesToGravitino() {
    checkToGravitino(Types.FixedCharType.of(10), CHAR, 10, null);
    checkToGravitino(Types.FixedCharType.of(10), NCHAR, 10, null);
    checkToGravitino(Types.VarCharType.of(100), VARCHAR2, 100, null);
    checkToGravitino(Types.VarCharType.of(100), NVARCHAR2, 100, null);
    checkToGravitino(Types.VarCharType.of(100), VARCHAR, 100, null);
    checkToGravitino(Types.StringType.get(), CLOB, null, null);
    checkToGravitino(Types.StringType.get(), NCLOB, null, null);
    checkToGravitino(Types.StringType.get(), LONG, null, null);
  }

  /**
   * {@code LONG RAW} carries a space in its name, so the runs of whitespace have to be collapsed.
   */
  @Test
  public void testBinaryTypesToGravitino() {
    checkToGravitino(Types.BinaryType.get(), RAW, 16, null);
    checkToGravitino(Types.BinaryType.get(), BLOB, null, null);
    checkToGravitino(Types.BinaryType.get(), LONG_RAW, null, null);
    checkToGravitino(Types.BinaryType.get(), "LONG   RAW", null, null);
  }

  /** An unsupported type keeps the exact name the driver reported, including its case. */
  @Test
  public void testUnsupportedTypesBecomeExternal() {
    checkToGravitino(Types.ExternalType.of(XMLTYPE), XMLTYPE, null, null);
    checkToGravitino(Types.ExternalType.of("ROWID"), "ROWID", null, null);
    checkToGravitino(
        Types.ExternalType.of("INTERVAL YEAR(2) TO MONTH"),
        "INTERVAL YEAR(2) TO MONTH",
        null,
        null);
  }

  @Test
  public void testNumericTypesFromGravitino() {
    // Oracle has no boolean column type before 23c, so NUMBER(1) is the conventional stand-in.
    checkFromGravitino(NUMBER + "(1)", Types.BooleanType.get());
    checkFromGravitino(NUMBER + "(3)", Types.ByteType.get());
    checkFromGravitino(NUMBER + "(5)", Types.ShortType.get());
    checkFromGravitino(NUMBER + "(10)", Types.IntegerType.get());
    checkFromGravitino(NUMBER + "(19)", Types.LongType.get());
    checkFromGravitino(BINARY_FLOAT, Types.FloatType.get());
    checkFromGravitino(BINARY_DOUBLE, Types.DoubleType.get());
    checkFromGravitino(NUMBER + "(10,2)", Types.DecimalType.of(10, 2));
  }

  /**
   * Lengths are emitted with the {@code CHAR} qualifier so that a declared length counts characters
   * rather than bytes, which is what a Gravitino length means.
   */
  @Test
  public void testCharacterTypesFromGravitino() {
    checkFromGravitino(CLOB, Types.StringType.get());
    checkFromGravitino(VARCHAR2 + "(100 CHAR)", Types.VarCharType.of(100));
    checkFromGravitino(
        VARCHAR2 + "(" + MAX_VARCHAR2_LENGTH + " CHAR)", Types.VarCharType.of(MAX_VARCHAR2_LENGTH));
    checkFromGravitino(CHAR + "(10 CHAR)", Types.FixedCharType.of(10));
    checkFromGravitino(
        CHAR + "(" + MAX_CHAR_LENGTH + " CHAR)", Types.FixedCharType.of(MAX_CHAR_LENGTH));
  }

  /**
   * A length Oracle cannot hold spills over to the next wider type: past {@code CHAR}'s limit to
   * {@code VARCHAR2}, and past {@code VARCHAR2}'s limit to {@code CLOB}.
   */
  @Test
  public void testLongCharacterTypesFallBack() {
    checkFromGravitino(VARCHAR2 + "(2001 CHAR)", Types.FixedCharType.of(MAX_CHAR_LENGTH + 1));
    checkFromGravitino(CLOB, Types.VarCharType.of(MAX_VARCHAR2_LENGTH + 1));
    checkFromGravitino(CLOB, Types.FixedCharType.of(MAX_VARCHAR2_LENGTH + 1));
  }

  @Test
  public void testOtherTypesFromGravitino() {
    checkFromGravitino(BLOB, Types.BinaryType.get());
    checkFromGravitino(DATE, Types.DateType.get());
    // Reads back as BINARY, since Oracle has no distinct UUID type.
    checkFromGravitino(RAW + "(16)", Types.UUIDType.get());
    checkFromGravitino(XMLTYPE, Types.ExternalType.of(XMLTYPE));
  }

  @Test
  public void testTimestampTypesFromGravitino() {
    checkFromGravitino(TIMESTAMP + "(6)", Types.TimestampType.withoutTimeZone(6));
    checkFromGravitino(TIMESTAMP + "(6) with time zone", Types.TimestampType.withTimeZone(6));
    // The precision goes between the base name and the time zone suffix; at the end Oracle rejects
    // it.
    checkFromGravitino(TIMESTAMP, Types.TimestampType.withoutTimeZone());
    checkFromGravitino(TIMESTAMP + " with time zone", Types.TimestampType.withTimeZone());
  }

  /** Oracle has no time-of-day type at all, so a Gravitino TIME column cannot be stored. */
  @Test
  public void testTimeTypeIsUnsupported() {
    Assertions.assertThrows(
        UnsupportedOperationException.class, () -> CONVERTER.fromGravitino(Types.TimeType.get()));
    Assertions.assertThrows(
        UnsupportedOperationException.class, () -> CONVERTER.fromGravitino(Types.TimeType.of(3)));
  }

  @Test
  public void testUnsupportedGravitinoTypeThrows() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> CONVERTER.fromGravitino(Types.UnparsedType.of("unknown")));
  }

  @Test
  public void testNormalizeCollapsesWhitespace() {
    Assertions.assertEquals("long raw", OracleTypeConverter.normalize("  LONG   RAW "));
    Assertions.assertEquals(
        "timestamp(6) with time zone",
        OracleTypeConverter.normalize("TIMESTAMP(6) WITH TIME ZONE"));
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
