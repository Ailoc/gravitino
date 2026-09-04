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

import com.google.common.annotations.VisibleForTesting;
import java.util.Locale;
import java.util.Optional;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;

/**
 * Type converter between Oracle types and Gravitino types.
 *
 * <p>Two Oracle quirks drive the shape of this class. First, the driver reports the fractional
 * seconds of a {@code TIMESTAMP} inside the type name itself, for example {@code TIMESTAMP(6) WITH
 * TIME ZONE}, so the timestamp family is matched on a prefix rather than by equality. Second,
 * Oracle has no pure date type: {@code DATE} carries a time of day down to the second, so it maps
 * to a timestamp with precision zero. Gravitino's {@link Types.DateType} is written as {@code DATE}
 * but therefore reads back as {@link Types.TimestampType}. That asymmetry is unavoidable, and is
 * the same one the Trino and Spark Oracle connectors have.
 */
public class OracleTypeConverter extends JdbcTypeConverter {

  static final String NUMBER = "number";
  static final String DECIMAL = "decimal";
  static final String NUMERIC = "numeric";
  static final String INTEGER = "integer";
  static final String INT = "int";
  static final String SMALLINT = "smallint";
  static final String FLOAT = "float";
  static final String BINARY_FLOAT = "binary_float";
  static final String BINARY_DOUBLE = "binary_double";
  static final String CHAR = "char";
  static final String NCHAR = "nchar";
  static final String VARCHAR2 = "varchar2";
  static final String NVARCHAR2 = "nvarchar2";
  static final String CLOB = "clob";
  static final String NCLOB = "nclob";
  static final String LONG = "long";
  static final String LONG_RAW = "long raw";
  static final String RAW = "raw";
  static final String BLOB = "blob";

  /** Suffix Oracle puts on a timestamp that keeps its offset. Always lower case, matching input. */
  static final String WITH_TIME_ZONE = " with time zone";

  /** Suffix Oracle puts on a timestamp rendered in the session time zone. */
  static final String WITH_LOCAL_TIME_ZONE = " with local time zone";

  /** Length semantics qualifier that makes a declared length count characters rather than bytes. */
  private static final String CHAR_LENGTH_QUALIFIER = " CHAR";

  /**
   * Largest precision Oracle's {@code NUMBER} accepts, which is also the widest Gravitino allows.
   */
  @VisibleForTesting static final int MAX_NUMBER_PRECISION = 38;

  /**
   * Scale assumed for a {@code NUMBER} declared without precision. Oracle stores such a column as a
   * floating decimal of up to 38 significant digits, so the scale has to be chosen rather than
   * read.
   */
  @VisibleForTesting static final int FLOATING_NUMBER_SCALE = 18;

  /**
   * The scale the driver reports for a {@code NUMBER} declared without precision, paired with a
   * precision of zero. It is a marker rather than a real scale, so it never reaches Gravitino.
   */
  private static final int FLOATING_SCALE_MARKER = -127;

  /**
   * Largest {@code n} accepted by {@code VARCHAR2(n)} under the default {@code MAX_STRING_SIZE}.
   */
  @VisibleForTesting static final int MAX_VARCHAR2_LENGTH = 4000;

  /** Largest {@code n} accepted by {@code CHAR(n)} under the default {@code MAX_STRING_SIZE}. */
  @VisibleForTesting static final int MAX_CHAR_LENGTH = 2000;

  /** Number of bytes a UUID occupies, which is what Oracle's {@code RAW(16)} is sized to hold. */
  private static final int UUID_BYTE_LENGTH = 16;

  @Override
  public Type toGravitino(JdbcTypeBean typeBean) {
    String typeName = normalize(typeBean.getTypeName());

    if (typeName.startsWith(TIMESTAMP)) {
      return toTimestamp(typeBean, typeName);
    }

    switch (typeName) {
      case DATE:
        // Oracle DATE holds a date and a time of day to the second.
        return Types.TimestampType.withoutTimeZone(0);
      case BINARY_FLOAT:
        return Types.FloatType.get();
      case BINARY_DOUBLE:
      case FLOAT:
        return Types.DoubleType.get();
      case NUMBER:
      case DECIMAL:
      case NUMERIC:
      case INTEGER:
      case INT:
      case SMALLINT:
        // In Oracle these are all spellings of NUMBER, so they share one mapping.
        return toDecimal(typeBean);
      case CHAR:
      case NCHAR:
        return Types.FixedCharType.of(typeBean.getColumnSize());
      case VARCHAR:
      case VARCHAR2:
      case NVARCHAR2:
        return Types.VarCharType.of(typeBean.getColumnSize());
      case CLOB:
      case NCLOB:
      case LONG:
        return Types.StringType.get();
      case RAW:
      case LONG_RAW:
      case BLOB:
        return Types.BinaryType.get();
      default:
        // interval, rowid, urowid, xmltype, bfile and user-defined types.
        return Types.ExternalType.of(typeBean.getTypeName());
    }
  }

  @Override
  public String fromGravitino(Type type) {
    if (type instanceof Types.BooleanType) {
      // Oracle has no boolean column type before 23c; NUMBER(1) is the conventional stand-in.
      return NUMBER + "(1)";
    } else if (type instanceof Types.ByteType) {
      return NUMBER + "(3)";
    } else if (type instanceof Types.ShortType) {
      return NUMBER + "(5)";
    } else if (type instanceof Types.IntegerType) {
      return NUMBER + "(10)";
    } else if (type instanceof Types.LongType) {
      return NUMBER + "(19)";
    } else if (type instanceof Types.FloatType) {
      return BINARY_FLOAT;
    } else if (type instanceof Types.DoubleType) {
      return BINARY_DOUBLE;
    } else if (type instanceof Types.DecimalType) {
      Types.DecimalType decimalType = (Types.DecimalType) type;
      return NUMBER + "(" + decimalType.precision() + "," + decimalType.scale() + ")";
    } else if (type instanceof Types.StringType) {
      return CLOB;
    } else if (type instanceof Types.VarCharType) {
      int length = ((Types.VarCharType) type).length();
      return length > MAX_VARCHAR2_LENGTH ? CLOB : VARCHAR2 + qualifiedLength(length);
    } else if (type instanceof Types.FixedCharType) {
      int length = ((Types.FixedCharType) type).length();
      if (length > MAX_VARCHAR2_LENGTH) {
        return CLOB;
      }
      return (length > MAX_CHAR_LENGTH ? VARCHAR2 : CHAR) + qualifiedLength(length);
    } else if (type instanceof Types.BinaryType) {
      return BLOB;
    } else if (type instanceof Types.DateType) {
      return DATE;
    } else if (type instanceof Types.TimeType) {
      throw new UnsupportedOperationException(
          "Oracle has no time-of-day type, so a Gravitino TIME column cannot be stored. "
              + "Use a TIMESTAMP, or store the value as a string.");
    } else if (type instanceof Types.TimestampType) {
      return toTimestampDefinition((Types.TimestampType) type);
    } else if (type instanceof Types.UUIDType) {
      // Reads back as BINARY, since Oracle has no distinct UUID type.
      return RAW + "(" + UUID_BYTE_LENGTH + ")";
    } else if (type instanceof Types.ExternalType) {
      return ((Types.ExternalType) type).catalogString();
    }
    throw new IllegalArgumentException(
        String.format("Couldn't convert Gravitino type %s to Oracle type", type.simpleString()));
  }

  /**
   * Emits a timestamp definition, placing any precision between the base name and the time zone
   * suffix so that the result reads {@code timestamp(6) with time zone} rather than putting the
   * precision at the end, which Oracle rejects.
   */
  private static String toTimestampDefinition(Types.TimestampType timestampType) {
    String precision = timestampType.hasPrecisionSet() ? "(" + timestampType.precision() + ")" : "";
    return timestampType.hasTimeZone()
        ? TIMESTAMP + precision + WITH_TIME_ZONE
        : TIMESTAMP + precision;
  }

  /**
   * Appends the {@code CHAR} length qualifier so a declared length counts characters, which is what
   * a Gravitino length means. Without it Oracle counts bytes and multi-byte values are rejected.
   */
  private static String qualifiedLength(int length) {
    return "(" + length + CHAR_LENGTH_QUALIFIER + ")";
  }

  private Type toTimestamp(JdbcTypeBean typeBean, String typeName) {
    int precision = Optional.ofNullable(typeBean.getDatetimePrecision()).orElse(0);
    // A "with local time zone" column is stored in UTC and rendered in the session time zone, so no
    // offset survives the round trip and it maps to a plain timestamp.
    boolean withTimeZone =
        typeName.contains(WITH_TIME_ZONE) && !typeName.contains(WITH_LOCAL_TIME_ZONE);
    return withTimeZone
        ? Types.TimestampType.withTimeZone(precision)
        : Types.TimestampType.withoutTimeZone(precision);
  }

  private Type toDecimal(JdbcTypeBean typeBean) {
    int precision = typeBean.getColumnSize() == null ? 0 : typeBean.getColumnSize();
    int scale = typeBean.getScale() == null ? 0 : typeBean.getScale();

    if (precision <= 0 || scale == FLOATING_SCALE_MARKER) {
      return Types.DecimalType.of(MAX_NUMBER_PRECISION, FLOATING_NUMBER_SCALE);
    }
    if (scale < 0) {
      // NUMBER(p,-s) rounds to a multiple of 10^s, so it holds p + |s| integer digits.
      return Types.DecimalType.of(Math.min(precision - scale, MAX_NUMBER_PRECISION), 0);
    }
    int cappedPrecision = Math.min(precision, MAX_NUMBER_PRECISION);
    // Gravitino requires scale <= precision, which Oracle does not.
    return Types.DecimalType.of(cappedPrecision, Math.min(scale, cappedPrecision));
  }

  /**
   * Lower cases the reported type name and collapses the runs of whitespace the driver leaves in
   * names such as {@code LONG RAW} and {@code TIMESTAMP(6) WITH LOCAL TIME ZONE}, so that matching
   * on the result is reliable. Shared with the default value converter and the table operations so
   * that all three match type names on the same form.
   *
   * @param typeName the type name as reported by the driver
   * @return the normalized lower case type name
   */
  public static String normalize(String typeName) {
    return typeName.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }
}
