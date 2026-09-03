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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Unit test for {@link SqlServerColumnDefaultValueConverter}. */
public class TestSqlServerColumnDefaultValueConverter {

  private static final SqlServerColumnDefaultValueConverter CONVERTER =
      new SqlServerColumnDefaultValueConverter();

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

  private Expression convert(
      JdbcTypeConverter.JdbcTypeBean typeBean, String defaultValue, boolean isExpression) {
    return CONVERTER.toGravitino(typeBean, defaultValue, isExpression, false);
  }

  @Test
  public void testAbsentDefault() {
    Assertions.assertSame(Literals.NULL, CONVERTER.toGravitino(type("int"), null, false, true));
    Assertions.assertSame(
        DEFAULT_VALUE_NOT_SET, CONVERTER.toGravitino(type("int"), null, false, false));
  }

  /** A computed column can hold arbitrary T-SQL, so it is surfaced verbatim rather than parsed. */
  @Test
  public void testComputedColumnIsUnparsed() {
    Expression result = convert(type("int"), "([a]+[b])", true);

    Assertions.assertEquals(UnparsedExpression.of("([a]+[b])"), result);
  }

  /**
   * SQL Server stores a numeric default unquoted and wrapped in the parentheses it adds when the
   * constraint is created.
   */
  @Test
  public void testNumericDefaults() {
    Assertions.assertEquals(Literals.integerLiteral(10), convert(type("int"), "((10))", false));
    Assertions.assertEquals(Literals.integerLiteral(-1), convert(type("int"), "((-1))", false));
    Assertions.assertEquals(
        Literals.longLiteral(100000000000L), convert(type("bigint"), "((100000000000))", false));
    Assertions.assertEquals(
        Literals.shortLiteral((short) 7), convert(type("smallint"), "((7))", false));
    Assertions.assertEquals(Literals.floatLiteral(1.5f), convert(type("real"), "((1.5))", false));
    Assertions.assertEquals(Literals.doubleLiteral(2.5d), convert(type("float"), "((2.5))", false));
    Assertions.assertEquals(
        Literals.decimalLiteral(Decimal.of("3.14", 10, 2)),
        convert(decimal("decimal", 10, 2), "((3.14))", false));
    // Scientific notation survives as well.
    Assertions.assertEquals(
        Literals.doubleLiteral(1.0E10d), convert(type("float"), "((1.0E+10))", false));
  }

  /**
   * {@code money} and {@code smallmoney} are fixed-precision decimals, so their defaults carry the
   * column's own type rather than the {@code double} their storage resembles.
   */
  @Test
  public void testMoneyDefaultsAreDecimal() {
    Assertions.assertEquals(
        Literals.decimalLiteral(Decimal.of("1234.5678", 19, 4)),
        convert(type("money"), "((1234.5678))", false));
    Assertions.assertEquals(
        Literals.decimalLiteral(Decimal.of("1234.5678", 10, 4)),
        convert(type("smallmoney"), "((1234.5678))", false));
    // The driver may equally report the documented precision and scale outright.
    Assertions.assertEquals(
        Literals.decimalLiteral(Decimal.of("0.5", 19, 4)),
        convert(decimal("money", 19, 4), "((0.5))", false));
  }

  /** A decimal column whose scale the driver omits still yields a literal matching its own type. */
  @Test
  public void testDecimalDefaultWithAbsentScale() {
    Assertions.assertEquals(
        Literals.decimalLiteral(Decimal.of("42", 10, 0)),
        convert(sized("decimal", 10), "((42))", false));
  }

  /** SQL Server TINYINT is unsigned, so its default still fits Gravitino's signed short. */
  @Test
  public void testTinyintDefault() {
    Assertions.assertEquals(
        Literals.shortLiteral((short) 255), convert(type("tinyint"), "((255))", false));
  }

  @Test
  public void testBitDefault() {
    Assertions.assertEquals(Literals.booleanLiteral(true), convert(type("bit"), "((1))", false));
    Assertions.assertEquals(Literals.booleanLiteral(false), convert(type("bit"), "((0))", false));
  }

  @Test
  public void testCharacterDefaults() {
    // The driver prefixes a Unicode literal with N.
    Assertions.assertEquals(
        Literals.stringLiteral("text"), convert(sized("nvarchar", -1), "(N'text')", false));
    Assertions.assertEquals(
        Literals.varcharLiteral(10, "abc"), convert(sized("varchar", 10), "('abc')", false));
    Assertions.assertEquals(
        Literals.stringLiteral("abc"), convert(sized("nchar", 10), "(N'abc')", false));
    // A doubled quote inside the literal is an escaped quote, not the end of it.
    Assertions.assertEquals(
        Literals.varcharLiteral(10, "it's"), convert(sized("varchar", 10), "('it''s')", false));
    Assertions.assertEquals(
        Literals.varcharLiteral(10, ""), convert(sized("varchar", 10), "('')", false));
  }

  @Test
  public void testNullDefault() {
    Assertions.assertSame(Literals.NULL, convert(type("int"), "(NULL)", false));
    Assertions.assertSame(Literals.NULL, convert(type("int"), "((NULL))", false));
  }

  /** Date and time defaults are stored as quoted strings. */
  @Test
  public void testTemporalDefaults() {
    Assertions.assertEquals(
        Literals.dateLiteral(LocalDate.of(2020, 1, 1)),
        convert(type("date"), "('2020-01-01')", false));
    Assertions.assertEquals(
        Literals.timeLiteral(LocalTime.of(12, 30, 0)),
        convert(type("time"), "('12:30:00')", false));
    Assertions.assertEquals(
        Literals.timestampLiteral(LocalDateTime.of(2020, 1, 1, 12, 30, 0)),
        convert(type("datetime2"), "('2020-01-01 12:30:00')", false));
  }

  /**
   * A niladic function default keeps its empty argument list, so {@code (getdate())} has to be
   * reduced to {@code getdate} before it can be recognised.
   */
  @Test
  public void testCurrentTimeFunctionDefaults() {
    Assertions.assertSame(
        DEFAULT_VALUE_OF_CURRENT_TIMESTAMP, convert(type("datetime2"), "(getdate())", false));
    Assertions.assertSame(
        DEFAULT_VALUE_OF_CURRENT_TIMESTAMP, convert(type("datetime2"), "(GETDATE())", false));
    Assertions.assertSame(
        DEFAULT_VALUE_OF_CURRENT_TIMESTAMP, convert(type("datetime2"), "(sysdatetime())", false));
    Assertions.assertSame(
        DEFAULT_VALUE_OF_CURRENT_TIMESTAMP,
        convert(type("datetime2"), "(CURRENT_TIMESTAMP)", false));
  }

  /**
   * {@code sysutcdatetime} returns UTC rather than local server time, so folding it into
   * Gravitino's CURRENT_TIMESTAMP would silently change the meaning of the default.
   */
  @Test
  public void testUtcAndOtherFunctionsStayUnparsed() {
    Assertions.assertEquals(
        UnparsedExpression.of("(sysutcdatetime())"),
        convert(type("datetime2"), "(sysutcdatetime())", false));
    Assertions.assertEquals(
        UnparsedExpression.of("(newid())"), convert(type("uniqueidentifier"), "(newid())", false));
  }

  /** A value that cannot be parsed for the column type keeps its original text. */
  @Test
  public void testUnparsableDefaultsFallBackToUnparsed() {
    Assertions.assertEquals(
        UnparsedExpression.of("('abc')"), convert(type("int"), "('abc')", false));
    // An arithmetic expression is not a numeric literal.
    Assertions.assertEquals(
        UnparsedExpression.of("(([1]+[2]))"), convert(type("int"), "(([1]+[2]))", false));
    // An unknown column type has no literal representation.
    Assertions.assertEquals(UnparsedExpression.of("((10))"), convert(type("xml"), "((10))", false));
  }

  /** A backslash is an ordinary character in a SQL Server string literal. */
  @Test
  public void testBackslashIsNotAnEscapeCharacter() {
    Assertions.assertEquals(
        Literals.varcharLiteral(20, "C:\\data"),
        convert(sized("varchar", 20), "('C:\\data')", false));
  }
}
