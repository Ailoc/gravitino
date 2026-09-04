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

import com.google.common.collect.ImmutableSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.gravitino.catalog.jdbc.converter.JdbcColumnDefaultValueConverter;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.expressions.Expression;
import org.apache.gravitino.rel.expressions.FunctionExpression;
import org.apache.gravitino.rel.expressions.UnparsedExpression;
import org.apache.gravitino.rel.expressions.literals.Literal;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.types.Decimal;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;

/**
 * Converts Oracle column defaults into Gravitino expressions, and back.
 *
 * <p>Two Oracle behaviours matter here. The dictionary reports a default verbatim in a {@code LONG}
 * column, padded with trailing whitespace, so it is trimmed before being parsed. And Oracle treats
 * the empty string as {@code NULL}, so a default written as {@code ''} is stored and read back as
 * {@code NULL} and cannot be distinguished from an explicit null default.
 *
 * <p>Writing needs an override rather than the shared implementation because a bare quoted date or
 * timestamp would be implicitly converted using the session's {@code NLS} settings, which fails or
 * silently shifts the value on most installations. Defaults are therefore emitted as explicit
 * {@code TO_DATE} and {@code TO_TIMESTAMP} calls.
 */
public class OracleColumnDefaultValueConverter extends JdbcColumnDefaultValueConverter {

  /**
   * Function defaults equivalent to {@code CURRENT_TIMESTAMP}. All of these return the current
   * local server date and time; {@code SYSTIMESTAMP} additionally carries an offset, which
   * Gravitino's single current-timestamp sentinel cannot express, so the offset is dropped.
   */
  private static final Set<String> CURRENT_TIME_FUNCTIONS =
      ImmutableSet.of(
          "sysdate", "systimestamp", "current_date", "current_timestamp", "localtimestamp");

  /** Format mask Oracle needs to parse a date literal without relying on session NLS settings. */
  private static final String DATE_MASK = "'YYYY-MM-DD'";

  /** Format mask Oracle needs to parse a timestamp literal, tolerating an absent fraction. */
  private static final String TIMESTAMP_MASK = "'YYYY-MM-DD HH24:MI:SS.FF'";

  /** A bare numeric default, which Oracle stores unquoted. */
  private static final Pattern NUMERIC_LITERAL =
      Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

  /** Shares the column type mapping, so a decimal default carries its own column's precision. */
  private static final OracleTypeConverter TYPE_CONVERTER = new OracleTypeConverter();

  @Override
  public String fromGravitino(Expression defaultValue) {
    if (DEFAULT_VALUE_NOT_SET.equals(defaultValue)) {
      return null;
    }

    if (defaultValue instanceof FunctionExpression) {
      FunctionExpression functionExpression = (FunctionExpression) defaultValue;
      if (functionExpression.functionName().equalsIgnoreCase(CURRENT_TIMESTAMP)) {
        // CURRENT_TIMESTAMP is a keyword and must not be wrapped in parentheses.
        return CURRENT_TIMESTAMP;
      }
      return String.format("(%s)", functionExpression);
    }

    if (!(defaultValue instanceof Literal)) {
      throw new IllegalArgumentException("Not a supported column default value: " + defaultValue);
    }

    Literal<?> literal = (Literal<?>) defaultValue;
    if (defaultValue.equals(Literals.NULL)) {
      return NULL;
    }

    Type type = literal.dataType();
    if (type instanceof Types.BooleanType) {
      // NUMBER(1) is the stand-in for a boolean column, so the literal has to be numeric too.
      return Boolean.parseBoolean(String.valueOf(literal.value())) ? "1" : "0";
    }
    if (type instanceof Type.NumericType) {
      return String.valueOf(literal.value());
    }
    if (type instanceof Types.DateType) {
      return String.format("TO_DATE('%s', %s)", formatDate(literal.value()), DATE_MASK);
    }
    if (type instanceof Types.TimestampType) {
      return String.format(
          "TO_TIMESTAMP('%s', %s)", formatTimestamp(literal.value()), TIMESTAMP_MASK);
    }
    if (type instanceof Types.TimeType) {
      throw new UnsupportedOperationException(
          "Oracle has no time-of-day type, so a TIME column cannot carry a default value.");
    }
    return quoteLiteral(String.valueOf(literal.value()));
  }

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
      // Virtual columns hold arbitrary SQL and are surfaced unparsed.
      return UnparsedExpression.of(columnDefaultValue);
    }

    String value = columnDefaultValue.trim();
    if (value.isEmpty() || NULL.equalsIgnoreCase(value)) {
      return Literals.NULL;
    }

    String unquoted = unquote(value);
    if (unquoted != null) {
      if (unquoted.isEmpty()) {
        // Oracle stores the empty string as NULL, so DEFAULT '' is a null default.
        return Literals.NULL;
      }
      return parseOrUnparsed(type, unquoted, columnDefaultValue);
    }

    if (NUMERIC_LITERAL.matcher(value).matches()) {
      return parseOrUnparsed(type, value, columnDefaultValue);
    }

    if (CURRENT_TIME_FUNCTIONS.contains(normalizeFunctionName(value))) {
      return DEFAULT_VALUE_OF_CURRENT_TIMESTAMP;
    }

    // A sequence NEXTVAL, a TO_DATE call, or any other expression; keep the original text.
    return UnparsedExpression.of(columnDefaultValue);
  }

  /**
   * Escapes a value and wraps it as an Oracle string literal. Only the quote needs doubling; unlike
   * MySQL, Oracle does not treat the backslash as an escape character.
   */
  private static String quoteLiteral(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  private static String formatDate(Object value) {
    return value instanceof LocalDate
        ? ((LocalDate) value).format(DATE_FORMATTER)
        : String.valueOf(value);
  }

  private static String formatTimestamp(Object value) {
    if (value instanceof LocalDateTime) {
      return ((LocalDateTime) value).format(DATE_TIME_FORMATTER);
    }
    // LocalDateTime#toString separates the date from the time with a 'T'.
    return String.valueOf(value).replace("T", " ");
  }

  private Expression parseOrUnparsed(
      JdbcTypeConverter.JdbcTypeBean type, String literal, String columnDefaultValue) {
    try {
      return parseLiteral(type, literal);
    } catch (RuntimeException e) {
      return UnparsedExpression.of(columnDefaultValue);
    }
  }

  /** Reduces a function default to its bare lower case name, so {@code sysdate} matches. */
  private static String normalizeFunctionName(String value) {
    return value.toLowerCase(Locale.ROOT).replaceAll("\\s*\\(\\s*\\)$", "");
  }

  /**
   * Returns the contents of a quoted string literal, or {@code null} if the value is not quoted.
   */
  private static String unquote(String value) {
    if (value.length() < 2 || value.charAt(0) != '\'' || value.charAt(value.length() - 1) != '\'') {
      return null;
    }
    return value.substring(1, value.length() - 1).replace("''", "'");
  }

  private Expression parseLiteral(JdbcTypeConverter.JdbcTypeBean type, String value) {
    switch (OracleTypeConverter.normalize(type.getTypeName())) {
      case OracleTypeConverter.BINARY_FLOAT:
        return Literals.floatLiteral(Float.valueOf(value));
      case OracleTypeConverter.BINARY_DOUBLE:
      case OracleTypeConverter.FLOAT:
        return Literals.doubleLiteral(Double.valueOf(value));
      case OracleTypeConverter.NUMBER:
      case OracleTypeConverter.DECIMAL:
      case OracleTypeConverter.NUMERIC:
      case OracleTypeConverter.INTEGER:
      case OracleTypeConverter.INT:
      case OracleTypeConverter.SMALLINT:
        return decimalLiteral(type, value);
      case OracleTypeConverter.DATE:
        return Literals.timestampLiteral(parseDateValue(value));
      case OracleTypeConverter.CHAR:
      case OracleTypeConverter.NCHAR:
      case OracleTypeConverter.CLOB:
      case OracleTypeConverter.NCLOB:
      case OracleTypeConverter.LONG:
        return Literals.stringLiteral(value);
      case JdbcTypeConverter.VARCHAR:
      case OracleTypeConverter.VARCHAR2:
      case OracleTypeConverter.NVARCHAR2:
        Integer columnSize = type.getColumnSize();
        return columnSize == null || columnSize < 0
            ? Literals.stringLiteral(value)
            : Literals.varcharLiteral(columnSize, value);
      default:
        // A timestamp family name carries an embedded precision, so it cannot be matched here.
        if (OracleTypeConverter.normalize(type.getTypeName())
            .startsWith(JdbcTypeConverter.TIMESTAMP)) {
          return Literals.timestampLiteral(LocalDateTime.parse(value, DATE_TIME_FORMATTER));
        }
        throw new IllegalArgumentException("Unknown data type for literal: " + type.getTypeName());
    }
  }

  /**
   * Builds a decimal literal carrying the precision and scale {@link OracleTypeConverter} resolves
   * for the column, so a default value cannot disagree with the type of the column holding it. The
   * raw metadata cannot express this: an unsized {@code NUMBER} is reported as precision 0 with
   * scale -127, and {@code NUMBER(p,-s)} reports a negative scale, both of which {@link Decimal}
   * rejects outright.
   */
  private static Expression decimalLiteral(JdbcTypeConverter.JdbcTypeBean type, String value) {
    Types.DecimalType columnType = (Types.DecimalType) TYPE_CONVERTER.toGravitino(type);
    return Literals.decimalLiteral(Decimal.of(value, columnType.precision(), columnType.scale()));
  }

  /**
   * Oracle reports a {@code DATE} default as either a bare date or a date and time, depending on
   * how it was written, so both forms are accepted.
   */
  private static LocalDateTime parseDateValue(String value) {
    String trimmed = value.trim();
    if (trimmed.length() == 10) {
      return LocalDate.parse(trimmed, DATE_FORMATTER).atStartOfDay();
    }
    return LocalDateTime.parse(trimmed, DATE_TIME_FORMATTER);
  }
}
