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
import java.util.regex.Matcher;
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

  /**
   * The {@code TO_DATE} call {@link #fromGravitino} writes for a date default, matched so the value
   * survives the round trip. The format mask is captured but deliberately not interpreted.
   */
  private static final Pattern TO_DATE_CALL =
      Pattern.compile(
          "TO_DATE\\s*\\(\\s*'((?:[^']|'')*)'\\s*,\\s*'[^']*'\\s*\\)", Pattern.CASE_INSENSITIVE);

  /** The {@code TO_TIMESTAMP} call {@link #fromGravitino} writes for a timestamp default. */
  private static final Pattern TO_TIMESTAMP_CALL =
      Pattern.compile(
          "TO_TIMESTAMP\\s*\\(\\s*'((?:[^']|'')*)'\\s*,\\s*'[^']*'\\s*\\)",
          Pattern.CASE_INSENSITIVE);

  /**
   * The default Oracle attaches to an identity column: the {@code NEXTVAL} of a sequence it names
   * {@code ISEQ$$_} followed by the table's object id, usually qualified by the schema.
   */
  private static final Pattern IDENTITY_SEQUENCE_DEFAULT =
      Pattern.compile(
          "(?:\"?[\\w$#]+\"?\\.)?\"?ISEQ\\$\\$_\\d+\"?\\.NEXTVAL", Pattern.CASE_INSENSITIVE);

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
    return quoteLiteral(literal.value());
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

    if (IDENTITY_SEQUENCE_DEFAULT.matcher(value).matches()) {
      // An identity column's default is the NEXTVAL of a sequence Oracle names after the table's
      // object id. The user never chose it and the name is not stable across a table rebuild, so it
      // reads back as no default; the auto-increment flag carries the information instead.
      return DEFAULT_VALUE_NOT_SET;
    }

    String temporalArgument = temporalLiteralArgument(value);
    if (temporalArgument != null) {
      // Parsed by the column's own type, so a DATE column yields a timestamp of precision zero and
      // a TIMESTAMP column yields the precision it was declared with.
      return parseOrUnparsed(type, temporalArgument, columnDefaultValue);
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

    // A user sequence NEXTVAL, a virtual column expression, or anything else; keep the text.
    return UnparsedExpression.of(columnDefaultValue);
  }

  /**
   * Returns the value inside the {@code TO_DATE} or {@code TO_TIMESTAMP} call {@link
   * #fromGravitino} writes, or {@code null} if the default is neither.
   *
   * <p>The format mask is deliberately ignored. The argument goes to {@link #parseLiteral}, which
   * parses it with the formatter matching the mask this class emits and dispatches on the column's
   * own type, so a default written by another tool with a different mask fails to parse and is
   * surfaced unparsed rather than being silently misread.
   *
   * @param value the trimmed default text as the dictionary reports it
   * @return the literal argument with its quotes stripped, or {@code null}
   */
  private static String temporalLiteralArgument(String value) {
    Matcher dateMatcher = TO_DATE_CALL.matcher(value);
    if (dateMatcher.matches()) {
      return unescape(dateMatcher.group(1));
    }
    Matcher timestampMatcher = TO_TIMESTAMP_CALL.matcher(value);
    if (timestampMatcher.matches()) {
      return unescape(timestampMatcher.group(1));
    }
    return null;
  }

  /** Collapses the doubled quote an Oracle string literal uses to carry a quote of its own. */
  private static String unescape(String value) {
    return value.replace("''", "'");
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
    return unescape(value.substring(1, value.length() - 1));
  }

  private Expression parseLiteral(JdbcTypeConverter.JdbcTypeBean type, String value) {
    String typeName = OracleTypeConverter.normalize(type.getTypeName());
    switch (typeName) {
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
        return Literals.of(parseDateValue(value), TYPE_CONVERTER.toGravitino(type));
      case OracleTypeConverter.CHAR:
      case OracleTypeConverter.NCHAR:
      case OracleTypeConverter.CLOB:
      case OracleTypeConverter.NCLOB:
      case OracleTypeConverter.LONG:
      case JdbcTypeConverter.VARCHAR:
      case OracleTypeConverter.VARCHAR2:
      case OracleTypeConverter.NVARCHAR2:
        return characterLiteral(type, value);
      default:
        // A timestamp family name carries an embedded precision, so it cannot be matched here.
        if (typeName.startsWith(JdbcTypeConverter.TIMESTAMP)) {
          return Literals.of(
              LocalDateTime.parse(value, DATE_TIME_FORMATTER), TYPE_CONVERTER.toGravitino(type));
        }
        throw new IllegalArgumentException("Unknown data type for literal: " + type.getTypeName());
    }
  }

  /**
   * Builds a character literal carrying the length {@link OracleTypeConverter} resolves for the
   * column, so a default cannot disagree with the type of the column holding it. A driver that
   * reports no usable size leaves the value unsized, which the length bearing types cannot express.
   */
  private static Expression characterLiteral(JdbcTypeConverter.JdbcTypeBean type, String value) {
    Integer columnSize = type.getColumnSize();
    if (columnSize == null || columnSize < 0) {
      return Literals.stringLiteral(value);
    }
    return Literals.of(value, TYPE_CONVERTER.toGravitino(type));
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
