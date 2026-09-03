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

import com.google.common.collect.ImmutableSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.gravitino.catalog.jdbc.converter.JdbcColumnDefaultValueConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.expressions.Expression;
import org.apache.gravitino.rel.expressions.UnparsedExpression;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.types.Decimal;
import org.apache.gravitino.rel.types.Types;

/**
 * Converts SQL Server column defaults into Gravitino expressions.
 *
 * <p>The driver reports a default constraint's definition verbatim, wrapped in the parentheses SQL
 * Server adds when the constraint is created, for example {@code ((10))} or {@code (N'text')}. The
 * wrapping and the Unicode literal prefix are stripped before the value is parsed.
 */
public class SqlServerColumnDefaultValueConverter extends JdbcColumnDefaultValueConverter {

  /**
   * Function defaults that are semantically equivalent to {@code CURRENT_TIMESTAMP}. Only functions
   * returning local server time belong here; {@code sysutcdatetime} returns UTC and would change
   * the meaning of the default. Names are lower case and carry no argument list.
   */
  private static final Set<String> CURRENT_TIME_FUNCTIONS =
      ImmutableSet.of("current_timestamp", "getdate", "sysdatetime", "localtimestamp");

  /** Empty argument list SQL Server keeps on a niladic function default, e.g. {@code getdate()}. */
  private static final String EMPTY_ARGUMENTS = "()";

  /** A bare numeric default, which SQL Server stores unquoted, e.g. {@code -1.5E+10}. */
  private static final Pattern NUMERIC_LITERAL =
      Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

  /** Shares the column type mapping, so a decimal default carries its own column's precision. */
  private static final SqlServerTypeConverter TYPE_CONVERTER = new SqlServerTypeConverter();

  @Override
  public Expression toGravitino(
      JdbcTypeConverter.JdbcTypeBean type,
      String columnDefaultValue,
      boolean isExpression,
      boolean nullable) {
    if (columnDefaultValue == null) {
      return nullable ? Literals.NULL : DEFAULT_VALUE_NOT_SET;
    }

    if (isExpression) {
      // Computed columns can hold arbitrary T-SQL; they are surfaced unparsed.
      return UnparsedExpression.of(columnDefaultValue);
    }

    String value = unwrap(columnDefaultValue);
    if (NULL.equalsIgnoreCase(value)) {
      return Literals.NULL;
    }

    String quoted = unquote(value);
    if (quoted != null) {
      // Date, time and character defaults are all stored as quoted strings.
      return parseOrUnparsed(type, quoted, columnDefaultValue);
    }

    if (NUMERIC_LITERAL.matcher(value).matches()) {
      return parseOrUnparsed(type, value, columnDefaultValue);
    }

    if (CURRENT_TIME_FUNCTIONS.contains(normalizeFunctionName(value))) {
      return DEFAULT_VALUE_OF_CURRENT_TIMESTAMP;
    }

    // A function call such as newid(), or a computed expression; keep the original text.
    return UnparsedExpression.of(columnDefaultValue);
  }

  private Expression parseOrUnparsed(
      JdbcTypeConverter.JdbcTypeBean type, String literal, String columnDefaultValue) {
    try {
      return parseLiteral(type, literal);
    } catch (RuntimeException e) {
      return UnparsedExpression.of(columnDefaultValue);
    }
  }

  /**
   * Reduces a function default to the bare lower case function name, so that {@code getdate()} and
   * {@code GETDATE} both match {@link #CURRENT_TIME_FUNCTIONS}.
   */
  private static String normalizeFunctionName(String value) {
    String result = value.toLowerCase(Locale.ROOT);
    return result.endsWith(EMPTY_ARGUMENTS)
        ? result.substring(0, result.length() - EMPTY_ARGUMENTS.length())
        : result;
  }

  /** Removes the parentheses SQL Server wraps around a default constraint definition. */
  private static String unwrap(String value) {
    String result = value.trim();
    while (result.length() > 1 && result.startsWith("(") && result.endsWith(")")) {
      result = result.substring(1, result.length() - 1).trim();
    }
    return result;
  }

  /**
   * Returns the contents of a quoted string literal, or {@code null} if the value is not quoted.
   */
  private static String unquote(String value) {
    String result = value;
    if (result.length() > 1
        && (result.charAt(0) == 'N' || result.charAt(0) == 'n')
        && result.charAt(1) == '\'') {
      result = result.substring(1);
    }
    if (result.length() < 2
        || result.charAt(0) != '\''
        || result.charAt(result.length() - 1) != '\'') {
      return null;
    }
    return result.substring(1, result.length() - 1).replace("''", "'");
  }

  private Expression parseLiteral(JdbcTypeConverter.JdbcTypeBean type, String value) {
    switch (type.getTypeName().toLowerCase(Locale.ROOT)) {
      case SqlServerTypeConverter.BIT:
        return Literals.booleanLiteral("1".equals(value) || Boolean.parseBoolean(value));
      case SqlServerTypeConverter.TINYINT:
      case SqlServerTypeConverter.SMALLINT:
        return Literals.shortLiteral(Short.valueOf(value));
      case SqlServerTypeConverter.INT:
        return Literals.integerLiteral(Integer.valueOf(value));
      case SqlServerTypeConverter.BIGINT:
        return Literals.longLiteral(Long.valueOf(value));
      case SqlServerTypeConverter.REAL:
        return Literals.floatLiteral(Float.valueOf(value));
      case SqlServerTypeConverter.FLOAT:
        return Literals.doubleLiteral(Double.valueOf(value));
      case SqlServerTypeConverter.MONEY:
      case SqlServerTypeConverter.SMALLMONEY:
      case SqlServerTypeConverter.DECIMAL:
      case SqlServerTypeConverter.NUMERIC:
        return decimalLiteral(type, value);
      case JdbcTypeConverter.DATE:
        return Literals.dateLiteral(LocalDate.parse(value, DATE_FORMATTER));
      case JdbcTypeConverter.TIME:
        return Literals.timeLiteral(LocalTime.parse(value, TIME_FORMATTER));
      case SqlServerTypeConverter.DATETIME:
      case SqlServerTypeConverter.SMALLDATETIME:
      case SqlServerTypeConverter.DATETIME2:
      case SqlServerTypeConverter.DATETIMEOFFSET:
        return Literals.timestampLiteral(LocalDateTime.parse(value, DATE_TIME_FORMATTER));
      case SqlServerTypeConverter.CHAR:
      case SqlServerTypeConverter.NCHAR:
        return Literals.stringLiteral(value);
      case JdbcTypeConverter.VARCHAR:
      case SqlServerTypeConverter.NVARCHAR:
        Integer columnSize = type.getColumnSize();
        return columnSize == null || columnSize < 0
            ? Literals.stringLiteral(value)
            : Literals.varcharLiteral(columnSize, value);
      case JdbcTypeConverter.TEXT:
      case SqlServerTypeConverter.NTEXT:
        return Literals.stringLiteral(value);
      default:
        throw new IllegalArgumentException("Unknown data type for literal: " + type);
    }
  }

  /**
   * Builds a decimal literal carrying the precision and scale {@link SqlServerTypeConverter}
   * resolves for the column, so a default value cannot disagree with the type of the column holding
   * it. {@code money} and {@code smallmoney} are fixed-precision decimals despite their float-like
   * storage, which reading the raw JDBC metadata alone would miss.
   */
  private static Expression decimalLiteral(JdbcTypeConverter.JdbcTypeBean type, String value) {
    Types.DecimalType columnType = (Types.DecimalType) TYPE_CONVERTER.toGravitino(type);
    return Literals.decimalLiteral(Decimal.of(value, columnType.precision(), columnType.scale()));
  }
}
