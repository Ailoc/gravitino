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

import com.google.common.annotations.VisibleForTesting;
import java.util.Locale;
import java.util.Optional;
import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;

/** Type converter between SQL Server types and Gravitino types. */
public class SqlServerTypeConverter extends JdbcTypeConverter {

  static final String BIT = "bit";
  static final String TINYINT = "tinyint";
  static final String SMALLINT = "smallint";
  static final String INT = "int";
  static final String BIGINT = "bigint";
  static final String REAL = "real";
  static final String FLOAT = "float";
  static final String DECIMAL = "decimal";
  static final String NUMERIC = "numeric";
  static final String MONEY = "money";
  static final String SMALLMONEY = "smallmoney";
  static final String CHAR = "char";
  static final String NCHAR = "nchar";
  static final String VARCHAR = "varchar";
  static final String NVARCHAR = "nvarchar";
  static final String NTEXT = "ntext";
  static final String BINARY = "binary";
  static final String VARBINARY = "varbinary";
  static final String IMAGE = "image";
  static final String DATETIME = "datetime";
  static final String SMALLDATETIME = "smalldatetime";
  static final String DATETIME2 = "datetime2";
  static final String DATETIMEOFFSET = "datetimeoffset";
  static final String UNIQUEIDENTIFIER = "uniqueidentifier";

  /**
   * SQL Server's {@code timestamp} type is a row version (an opaque binary counter), not a point in
   * time. It is mapped to {@link Types.BinaryType} rather than a timestamp type.
   */
  static final String ROWVERSION = "timestamp";

  /**
   * Marker the driver appends to the type name of an identity column, reporting {@code bigint
   * identity} rather than {@code bigint}. Identity is a property of the column and not a type of
   * its own, and Gravitino already carries it as the column's auto-increment flag, so it is
   * stripped before the name is matched.
   */
  @VisibleForTesting static final String IDENTITY_MARKER = " identity";

  /**
   * Maximum length accepted by {@code nvarchar(n)} / {@code nchar(n)} before {@code max} is needed.
   */
  @VisibleForTesting static final int MAX_UNICODE_LENGTH = 4000;

  /** The driver reports {@code -1} for the column size of {@code varchar(max)} style types. */
  @VisibleForTesting static final int UNSIZED_LENGTH = -1;

  @VisibleForTesting static final int DEFAULT_MONEY_PRECISION = 19;
  @VisibleForTesting static final int DEFAULT_SMALLMONEY_PRECISION = 10;
  @VisibleForTesting static final int MONEY_SCALE = 4;

  @Override
  public Type toGravitino(JdbcTypeBean typeBean) {
    String typeName = stripIdentityMarker(typeBean.getTypeName().toLowerCase(Locale.ROOT));
    switch (typeName) {
      case BIT:
        return Types.BooleanType.get();
      case TINYINT:
        // SQL Server TINYINT is unsigned (0-255) and does not fit Gravitino's signed ByteType.
        return Types.ShortType.get();
      case SMALLINT:
        return Types.ShortType.get();
      case INT:
        return Types.IntegerType.get();
      case BIGINT:
        return Types.LongType.get();
      case REAL:
        return Types.FloatType.get();
      case FLOAT:
        return Types.DoubleType.get();
      case MONEY:
        return toDecimal(typeBean, DEFAULT_MONEY_PRECISION, MONEY_SCALE);
      case SMALLMONEY:
        return toDecimal(typeBean, DEFAULT_SMALLMONEY_PRECISION, MONEY_SCALE);
      case DECIMAL:
      case NUMERIC:
        return toDecimal(typeBean, null, null);
      case DATE:
        return Types.DateType.get();
      case TIME:
        return Optional.ofNullable(typeBean.getDatetimePrecision())
            .map(Types.TimeType::of)
            .orElseGet(Types.TimeType::get);
      case DATETIME:
      case SMALLDATETIME:
      case DATETIME2:
        return Optional.ofNullable(typeBean.getDatetimePrecision())
            .map(Types.TimestampType::withoutTimeZone)
            .orElseGet(Types.TimestampType::withoutTimeZone);
      case DATETIMEOFFSET:
        return Optional.ofNullable(typeBean.getDatetimePrecision())
            .map(Types.TimestampType::withTimeZone)
            .orElseGet(Types.TimestampType::withTimeZone);
      case CHAR:
      case NCHAR:
        return Types.FixedCharType.of(typeBean.getColumnSize());
      case VARCHAR:
      case NVARCHAR:
        return isUnsized(typeBean.getColumnSize())
            ? Types.StringType.get()
            : Types.VarCharType.of(typeBean.getColumnSize());
      case TEXT:
      case NTEXT:
        return Types.StringType.get();
      case BINARY:
      case VARBINARY:
      case IMAGE:
      case ROWVERSION:
        return Types.BinaryType.get();
      case UNIQUEIDENTIFIER:
        return Types.UUIDType.get();
      default:
        // xml, sql_variant, geography, geometry, hierarchyid and user-defined types.
        return Types.ExternalType.of(typeBean.getTypeName());
    }
  }

  @Override
  public String fromGravitino(Type type) {
    if (type instanceof Types.BooleanType) {
      return BIT;
    } else if (type instanceof Types.ByteType || type instanceof Types.ShortType) {
      // Gravitino's byte/short are signed; SQL Server TINYINT is unsigned, so use SMALLINT.
      return SMALLINT;
    } else if (type instanceof Types.IntegerType) {
      return INT;
    } else if (type instanceof Types.LongType) {
      return BIGINT;
    } else if (type instanceof Types.FloatType) {
      return REAL;
    } else if (type instanceof Types.DoubleType) {
      return FLOAT;
    } else if (type instanceof Types.DecimalType) {
      Types.DecimalType decimalType = (Types.DecimalType) type;
      return DECIMAL + "(" + decimalType.precision() + "," + decimalType.scale() + ")";
    } else if (type instanceof Types.StringType) {
      return NVARCHAR + "(max)";
    } else if (type instanceof Types.VarCharType) {
      int length = ((Types.VarCharType) type).length();
      return length > MAX_UNICODE_LENGTH ? NVARCHAR + "(max)" : NVARCHAR + "(" + length + ")";
    } else if (type instanceof Types.FixedCharType) {
      int length = ((Types.FixedCharType) type).length();
      return length > MAX_UNICODE_LENGTH ? NVARCHAR + "(max)" : NCHAR + "(" + length + ")";
    } else if (type instanceof Types.BinaryType) {
      return VARBINARY + "(max)";
    } else if (type instanceof Types.DateType) {
      return DATE;
    } else if (type instanceof Types.TimeType) {
      Types.TimeType timeType = (Types.TimeType) type;
      return timeType.hasPrecisionSet() ? TIME + "(" + timeType.precision() + ")" : TIME;
    } else if (type instanceof Types.TimestampType) {
      Types.TimestampType timestampType = (Types.TimestampType) type;
      String baseType = timestampType.hasTimeZone() ? DATETIMEOFFSET : DATETIME2;
      return timestampType.hasPrecisionSet()
          ? String.format("%s(%d)", baseType, timestampType.precision())
          : baseType;
    } else if (type instanceof Types.UUIDType) {
      return UNIQUEIDENTIFIER;
    } else if (type instanceof Types.ExternalType) {
      return ((Types.ExternalType) type).catalogString();
    }
    throw new IllegalArgumentException(
        String.format(
            "Couldn't convert Gravitino type %s to SQL Server type", type.simpleString()));
  }

  private Type toDecimal(JdbcTypeBean typeBean, Integer fallbackPrecision, Integer fallbackScale) {
    Integer columnSize = typeBean.getColumnSize();
    Integer scale = typeBean.getScale();
    if (isUnsized(columnSize) || columnSize == 0) {
      if (fallbackPrecision == null) {
        throw new IllegalArgumentException(
            "SQL Server DECIMAL/NUMERIC without precision cannot be mapped to a Gravitino type");
      }
      return Types.DecimalType.of(fallbackPrecision, fallbackScale);
    }
    return Types.DecimalType.of(columnSize, scale == null ? 0 : scale);
  }

  /**
   * Removes the identity marker the driver folds into a column's type name, so that {@code bigint
   * identity} maps as {@code bigint} rather than falling through to an external type.
   *
   * @param typeName the lower case type name as reported by the driver
   * @return the type name without the identity marker
   */
  private static String stripIdentityMarker(String typeName) {
    int marker = typeName.indexOf(IDENTITY_MARKER);
    return marker < 0 ? typeName : typeName.substring(0, marker).trim();
  }

  /**
   * Reports whether a column size means "unbounded" rather than a count of characters.
   *
   * <p>The driver spells {@code varchar(max)} and its siblings inconsistently, as {@code -1} in
   * some paths and as {@link Integer#MAX_VALUE} in others, so both have to be recognised. Shared
   * with {@link SqlServerColumnDefaultValueConverter}, which would otherwise read a default on a
   * {@code nvarchar(max)} column back as a varchar of length {@code 2147483647} against a column
   * that maps to {@link Types.StringType}.
   *
   * @param columnSize the size the driver reported, possibly null
   * @return true if the column is unbounded
   */
  static boolean isUnsized(Integer columnSize) {
    return columnSize == null || columnSize == UNSIZED_LENGTH || columnSize == Integer.MAX_VALUE;
  }
}
