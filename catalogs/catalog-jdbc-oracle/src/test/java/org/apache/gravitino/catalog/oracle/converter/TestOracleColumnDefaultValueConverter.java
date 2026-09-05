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

import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_NOT_SET;
import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_OF_CURRENT_TIMESTAMP;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.expressions.Expression;
import org.apache.gravitino.rel.expressions.UnparsedExpression;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.types.Decimal;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit test for {@link OracleColumnDefaultValueConverter}. */
public class TestOracleColumnDefaultValueConverter {

  private static final OracleColumnDefaultValueConverter CONVERTER =
      new OracleColumnDefaultValueConverter();

  private static final String DATE_MASK = "'YYYY-MM-DD'";
  private static final String TIMESTAMP_MASK = "'YYYY-MM-DD HH24:MI:SS.FF'";

  private static JdbcTypeConverter.JdbcTypeBean type(String typeName) {
    return new JdbcTypeConverter.JdbcTypeBean(typeName);
  }

  private static JdbcTypeConverter.JdbcTypeBean sized(String typeName, Integer columnSize) {
    JdbcTypeConverter.JdbcTypeBean typeBean = type(typeName);
    typeBean.setColumnSize(columnSize);
    return typeBean;
  }

  private static JdbcTypeConverter.JdbcTypeBean decimal(String typeName, int size, int scale) {
    JdbcTypeConverter.JdbcTypeBean typeBean = type(typeName);
    typeBean.setColumnSize(size);
    typeBean.setScale(scale);
    return typeBean;
  }

  /**
   * Carries the fractional seconds precision the table operations resolve before the default value
   * converter sees the bean. Oracle reports it inside the type name, which {@link
   * OracleTypeConverter} does not parse back out of it.
   */
  private static JdbcTypeConverter.JdbcTypeBean timestamp(String typeName, int precision) {
    JdbcTypeConverter.JdbcTypeBean typeBean = type(typeName);
    typeBean.setDatetimePrecision(precision);
    return typeBean;
  }

  private Expression convert(
      JdbcTypeConverter.JdbcTypeBean typeBean, String defaultValue, boolean isExpression) {
    return CONVERTER.toGravitino(typeBean, defaultValue, isExpression, false);
  }

  @Test
  public void testAbsentDefault() {
    Assertions.assertSame(Literals.NULL, CONVERTER.toGravitino(type("number"), null, false, true));
    Assertions.assertSame(
        DEFAULT_VALUE_NOT_SET, CONVERTER.toGravitino(type("number"), null, false, false));
  }

  /** A virtual column holds arbitrary SQL, so it is surfaced verbatim rather than parsed. */
  @Test
  public void testVirtualColumnIsUnparsed() {
    Assertions.assertEquals(
        UnparsedExpression.of("(\"A\"+\"B\")"), convert(type("number"), "(\"A\"+\"B\")", true));
  }

  /** The data dictionary reports a default in a LONG column, padded with trailing whitespace. */
  @Test
  public void testNumericDefaultsAreTrimmed() {
    Assertions.assertEquals(
        Literals.decimalLiteral(Decimal.of("10", 10, 0)),
        convert(decimal("number", 10, 0), "  10   ", false));
    Assertions.assertEquals(
        Literals.decimalLiteral(Decimal.of("-1", 10, 0)),
        convert(decimal("number", 10, 0), "-1", false));
    Assertions.assertEquals(
        Literals.decimalLiteral(Decimal.of("3.14", 10, 2)),
        convert(decimal("number", 10, 2), "3.14", false));
    // Oracle stores a numeric default unquoted, with no parentheses around it.
    Assertions.assertEquals(
        Literals.decimalLiteral(Decimal.of("1.5", 10, 1)),
        convert(decimal("decimal", 10, 1), "1.5", false));
    Assertions.assertEquals(
        Literals.floatLiteral(1.5f), convert(type("binary_float"), "1.5", false));
    Assertions.assertEquals(
        Literals.doubleLiteral(2.5d), convert(type("binary_double"), "2.5", false));
  }

  /** Oracle has no boolean column type, so the NUMBER(1) stand-in holds a numeric default. */
  @Test
  public void testBooleanDefaultIsNumeric() {
    Assertions.assertEquals(
        Literals.decimalLiteral(Decimal.of("1", 1, 0)),
        convert(decimal("number", 1, 0), "1", false));
  }

  /**
   * An unsized {@code NUMBER} is reported as precision 0 with scale -127, which {@link Decimal}
   * rejects outright; the column resolves to {@code Decimal(38,18)} and its default must match.
   */
  @Test
  public void testUnsizedNumberDefault() {
    Assertions.assertEquals(
        Literals.decimalLiteral(Decimal.of("10", 38, 18)), convert(type("number"), "10", false));
  }

  /** {@code NUMBER(p,-s)} rounds to a multiple of 10^s, so it holds p + |s| integer digits. */
  @Test
  public void testNegativeScaleNumberDefault() {
    Assertions.assertEquals(
        Literals.decimalLiteral(Decimal.of("1000", 12, 0)),
        convert(decimal("number", 10, -2), "1000", false));
  }

  /** {@code INTEGER} is only another spelling of {@code NUMBER}, so it shares the mapping. */
  @Test
  public void testIntegerSpellingDefault() {
    Assertions.assertEquals(
        Literals.decimalLiteral(Decimal.of("10", 38, 0)),
        convert(decimal("integer", 38, 0), "10", false));
  }

  @Test
  public void testNullDefault() {
    Assertions.assertSame(Literals.NULL, convert(type("number"), "NULL", false));
    Assertions.assertSame(Literals.NULL, convert(type("number"), "null", false));
    Assertions.assertSame(Literals.NULL, convert(type("number"), "  NULL  ", false));
  }

  /**
   * Oracle treats the empty string as NULL, so a default written as {@code ''} is a null default.
   */
  @Test
  public void testEmptyStringDefaultIsNull() {
    Assertions.assertSame(Literals.NULL, convert(sized("varchar2", 20), "''", false));
    Assertions.assertSame(Literals.NULL, convert(sized("varchar2", 20), "''   ", false));
  }

  @Test
  public void testCharacterDefaults() {
    Assertions.assertEquals(
        Literals.varcharLiteral(20, "abc"), convert(sized("varchar2", 20), "'abc'", false));
    Assertions.assertEquals(
        Literals.varcharLiteral(20, "abc"), convert(sized("nvarchar2", 20), "'abc'", false));
    // A doubled quote inside the literal is an escaped quote, not the end of it.
    Assertions.assertEquals(
        Literals.varcharLiteral(20, "it's"), convert(sized("varchar2", 20), "'it''s'", false));
    // CHAR is blank padded, and its default carries the length of the column holding it.
    Assertions.assertEquals(
        Literals.of("abc", Types.FixedCharType.of(10)), convert(sized("char", 10), "'abc'", false));
    Assertions.assertEquals(
        Literals.of("abc", Types.FixedCharType.of(10)),
        convert(sized("nchar", 10), "'abc'", false));
    // The large object types are unsized in Gravitino, whatever the driver reports as their size.
    Assertions.assertEquals(Literals.stringLiteral("abc"), convert(type("clob"), "'abc'", false));
    Assertions.assertEquals(
        Literals.stringLiteral("abc"), convert(sized("clob", 4000), "'abc'", false));
    Assertions.assertEquals(
        Literals.stringLiteral("abc"), convert(sized("long", -1), "'abc'", false));
    // Without a reported size there is nothing to build a sized literal from.
    Assertions.assertEquals(
        Literals.stringLiteral("abc"), convert(type("varchar2"), "'abc'", false));
    Assertions.assertEquals(
        Literals.stringLiteral("abc"), convert(sized("char", -1), "'abc'", false));
  }

  /**
   * Oracle {@code DATE} carries a time of day, so its default is a timestamp literal. Both the bare
   * date and the date and time form are accepted, depending on how the default was written. The
   * literal carries the precision of the column holding it, zero for a {@code DATE} column, instead
   * of leaving the precision unset.
   */
  @Test
  public void testDateDefaults() {
    Assertions.assertEquals(
        Literals.of(LocalDateTime.of(2020, 1, 1, 0, 0), Types.TimestampType.withoutTimeZone(0)),
        convert(type("date"), "'2020-01-01'", false));
    Assertions.assertEquals(
        Literals.of(
            LocalDateTime.of(2020, 1, 1, 12, 30, 0), Types.TimestampType.withoutTimeZone(0)),
        convert(type("date"), "'2020-01-01 12:30:00'", false));
  }

  /**
   * The driver embeds the fractional seconds precision in the timestamp type name, so the literal
   * takes its precision from the column and a {@code WITH TIME ZONE} column keeps the offset
   * marker.
   */
  @Test
  public void testTimestampDefaults() {
    Assertions.assertEquals(
        Literals.of(
            LocalDateTime.of(2020, 1, 1, 12, 30, 0), Types.TimestampType.withoutTimeZone(6)),
        convert(timestamp("timestamp(6)", 6), "'2020-01-01 12:30:00'", false));
    Assertions.assertEquals(
        Literals.of(
            LocalDateTime.of(2020, 1, 1, 12, 30, 0, 123000000),
            Types.TimestampType.withoutTimeZone(6)),
        convert(timestamp("timestamp(6)", 6), "'2020-01-01 12:30:00.123000'", false));
    Assertions.assertEquals(
        Literals.of(LocalDateTime.of(2020, 1, 1, 12, 30, 0), Types.TimestampType.withTimeZone(3)),
        convert(timestamp("timestamp(3) with time zone", 3), "'2020-01-01 12:30:00'", false));
  }

  /**
   * All of these return the current local server date and time. {@code SYSTIMESTAMP} additionally
   * carries an offset, which Gravitino's single current-timestamp sentinel cannot express.
   */
  @Test
  public void testCurrentTimeFunctionDefaults() {
    for (String function :
        new String[] {
          "sysdate",
          "SYSDATE",
          "systimestamp",
          "SYSTIMESTAMP",
          "current_date",
          "CURRENT_TIMESTAMP",
          "localtimestamp"
        }) {
      Assertions.assertSame(
          DEFAULT_VALUE_OF_CURRENT_TIMESTAMP,
          convert(type("date"), function, false),
          "function: " + function);
    }
  }

  /** A sequence, a conversion call or any other expression keeps its original text. */
  @Test
  public void testOtherExpressionsStayUnparsed() {
    Assertions.assertEquals(
        UnparsedExpression.of("\"SEQ_ORDERS\".NEXTVAL"),
        convert(type("number"), "\"SEQ_ORDERS\".NEXTVAL", false));
    // SYS_EXTRACT_UTC returns UTC rather than local server time, so folding it into Gravitino's
    // CURRENT_TIMESTAMP would silently change the meaning of the default.
    Assertions.assertEquals(
        UnparsedExpression.of("SYS_EXTRACT_UTC(SYSTIMESTAMP)"),
        convert(type("timestamp(6)"), "SYS_EXTRACT_UTC(SYSTIMESTAMP)", false));
  }

  /**
   * The dictionary reports the {@code TO_DATE} and {@code TO_TIMESTAMP} calls {@link
   * OracleColumnDefaultValueConverter#fromGravitino} writes, so a temporal default has to survive
   * the round trip rather than coming back unparsed. Oracle {@code DATE} carries a time of day, so
   * what reads back is the timestamp the column really holds.
   */
  @Test
  public void testTemporalConversionCallDefaults() {
    Assertions.assertEquals(
        Literals.of(
            LocalDate.of(2020, 1, 1).atStartOfDay(), Types.TimestampType.withoutTimeZone(0)),
        convert(type("date"), String.format("TO_DATE('2020-01-01', %s)", DATE_MASK), false));
    Assertions.assertEquals(
        Literals.of(
            LocalDateTime.of(2020, 1, 1, 12, 30, 0, 123000000),
            Types.TimestampType.withoutTimeZone(6)),
        convert(
            timestamp("timestamp(6)", 6),
            String.format("TO_TIMESTAMP('2020-01-01 12:30:00.123', %s)", TIMESTAMP_MASK),
            false));
    // The dictionary reports a default in a LONG column, padded with trailing whitespace.
    Assertions.assertEquals(
        Literals.of(
            LocalDateTime.of(2020, 1, 1, 12, 30, 0), Types.TimestampType.withoutTimeZone(6)),
        convert(
            timestamp("timestamp(6)", 6),
            String.format("TO_TIMESTAMP('2020-01-01 12:30:00', %s)", TIMESTAMP_MASK) + "   ",
            false));
  }

  /**
   * An identity column's default is the {@code NEXTVAL} of a sequence Oracle names after the
   * table's object id. The user never chose it and the name is not stable across a table rebuild,
   * so it reads back as no default and the auto-increment flag carries the information instead.
   */
  @Test
  public void testIdentitySequenceDefaultIsNotSet() {
    Assertions.assertSame(
        DEFAULT_VALUE_NOT_SET, convert(type("number"), "\"APP\".\"ISEQ$$_73189\".nextval", false));
    Assertions.assertSame(
        DEFAULT_VALUE_NOT_SET, convert(type("number"), "\"ISEQ$$_73189\".nextval", false));
    Assertions.assertSame(
        DEFAULT_VALUE_NOT_SET, convert(type("number"), "ISEQ$$_1.NEXTVAL", false));
    // A sequence the user named is a deliberate default, so it stays visible.
    Assertions.assertEquals(
        UnparsedExpression.of("\"SEQ_ORDERS\".NEXTVAL"),
        convert(type("number"), "\"SEQ_ORDERS\".NEXTVAL", false));
  }

  /** A value that cannot be parsed for the column type keeps its original text. */
  @Test
  public void testUnparsableDefaultsFallBackToUnparsed() {
    Assertions.assertEquals(
        UnparsedExpression.of("'abc'"), convert(decimal("number", 10, 2), "'abc'", false));
    // An unknown column type has no literal representation.
    Assertions.assertEquals(
        UnparsedExpression.of("'abc'"), convert(type("xmltype"), "'abc'", false));
    // An arithmetic expression is not a numeric literal.
    Assertions.assertEquals(
        UnparsedExpression.of("1+1"), convert(decimal("number", 10, 2), "1+1", false));
  }

  /** A backslash is an ordinary character in an Oracle string literal. */
  @Test
  public void testBackslashIsNotAnEscapeCharacter() {
    Assertions.assertEquals(
        Literals.varcharLiteral(20, "C:\\data"),
        convert(sized("varchar2", 20), "'C:\\data'", false));
  }

  @Test
  public void testFromGravitinoLiterals() {
    Assertions.assertNull(CONVERTER.fromGravitino(DEFAULT_VALUE_NOT_SET));
    Assertions.assertEquals("NULL", CONVERTER.fromGravitino(Literals.NULL));
    // NUMBER(1) is the stand-in for a boolean column, so the literal has to be numeric too.
    Assertions.assertEquals("1", CONVERTER.fromGravitino(Literals.booleanLiteral(true)));
    Assertions.assertEquals("0", CONVERTER.fromGravitino(Literals.booleanLiteral(false)));
    Assertions.assertEquals("10", CONVERTER.fromGravitino(Literals.integerLiteral(10)));
    Assertions.assertEquals(
        "3.14", CONVERTER.fromGravitino(Literals.decimalLiteral(Decimal.of("3.14", 10, 2))));
    Assertions.assertEquals("'abc'", CONVERTER.fromGravitino(Literals.stringLiteral("abc")));
  }

  /**
   * A bare quoted date or timestamp would be implicitly converted using the session's NLS settings,
   * which fails or silently shifts the value on most installations, so both are emitted as explicit
   * conversion calls.
   */
  @Test
  public void testFromGravitinoTemporalLiterals() {
    Assertions.assertEquals(
        String.format("TO_DATE('2020-01-01', %s)", DATE_MASK),
        CONVERTER.fromGravitino(Literals.dateLiteral(LocalDate.of(2020, 1, 1))));
    Assertions.assertEquals(
        String.format("TO_TIMESTAMP('2020-01-01 12:30:00', %s)", TIMESTAMP_MASK),
        CONVERTER.fromGravitino(Literals.timestampLiteral(LocalDateTime.of(2020, 1, 1, 12, 30))));
    // The fraction is rendered without trailing zeros, which Oracle's FF mask accepts as is.
    Assertions.assertEquals(
        String.format("TO_TIMESTAMP('2020-01-01 12:30:00.123', %s)", TIMESTAMP_MASK),
        CONVERTER.fromGravitino(
            Literals.timestampLiteral(LocalDateTime.of(2020, 1, 1, 12, 30, 0, 123000000))));
  }

  /** Only the quote needs doubling, since Oracle does not treat the backslash as an escape. */
  @Test
  public void testFromGravitinoEscapesQuoteOnly() {
    Assertions.assertEquals("'it''s'", CONVERTER.fromGravitino(Literals.stringLiteral("it's")));
    Assertions.assertEquals(
        "'C:\\data\\notes'", CONVERTER.fromGravitino(Literals.stringLiteral("C:\\data\\notes")));
  }

  /** CURRENT_TIMESTAMP is a keyword and must not be wrapped in parentheses. */
  @Test
  public void testFromGravitinoCurrentTimestamp() {
    Assertions.assertEquals(
        "CURRENT_TIMESTAMP", CONVERTER.fromGravitino(DEFAULT_VALUE_OF_CURRENT_TIMESTAMP));
  }

  /** Oracle has no time-of-day type, so a TIME column cannot carry a default at all. */
  @Test
  public void testFromGravitinoRejectsTime() {
    Assertions.assertThrows(
        UnsupportedOperationException.class,
        () -> CONVERTER.fromGravitino(Literals.timeLiteral(LocalTime.of(12, 30))));
  }

  @Test
  public void testFromGravitinoRejectsUnsupportedExpression() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> CONVERTER.fromGravitino(UnparsedExpression.of("1+1")));
  }
}
