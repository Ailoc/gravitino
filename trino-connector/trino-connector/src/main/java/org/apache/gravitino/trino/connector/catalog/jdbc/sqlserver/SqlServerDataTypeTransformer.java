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
import io.trino.spi.type.CharType;
import io.trino.spi.type.TimeType;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.TimestampWithTimeZoneType;
import io.trino.spi.type.VarcharType;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Type.Name;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.trino.connector.GravitinoErrorCode;
import org.apache.gravitino.trino.connector.util.GeneralDataTypeTransformer;

/** Type transformer between SQL Server and Trino */
public class SqlServerDataTypeTransformer extends GeneralDataTypeTransformer {

  // SQL Server's TIME, DATETIME2 and DATETIMEOFFSET accept a fractional-second precision from 0
  // to 7. Trino allows up to 12, so the excess has to be rejected rather than passed through as
  // invalid DDL.
  private static final int SQLSERVER_MAX_TEMPORAL_PRECISION = 7;

  @Override
  public io.trino.spi.type.Type getTrinoType(Type type) {
    if (type.name() == Name.STRING) {
      // Gravitino's string type stands for SQL Server's NVARCHAR(MAX), which has no length bound.
      return VarcharType.createUnboundedVarcharType();
    }

    return super.getTrinoType(type);
  }

  @Override
  public Type getGravitinoType(io.trino.spi.type.Type type) {
    Class<? extends io.trino.spi.type.Type> typeClass = type.getClass();
    if (typeClass == CharType.class) {
      int length = ((CharType) type).getLength();
      if (length == 0) {
        throw new TrinoException(
            GravitinoErrorCode.GRAVITINO_ILLEGAL_ARGUMENT,
            "SQL Server does not support the datatype CHAR with the length 0");
      }

      // SQL Server caps NCHAR at 4000 characters, but the SQL Server catalog widens longer values
      // to NVARCHAR(MAX) on its own, so the length is passed through unchanged here.
      return Types.FixedCharType.of(length);
    } else if (typeClass == VarcharType.class) {
      VarcharType varcharType = (VarcharType) type;

      // An unbounded VARCHAR stands for SQL Server's NVARCHAR(MAX), which Gravitino models as a
      // string type.
      if (varcharType.getLength().isEmpty()) {
        return Types.StringType.get();
      }

      return Types.VarCharType.of(varcharType.getLength().get());
    } else if (TimeType.class.isAssignableFrom(typeClass)) {
      int precision = ((TimeType) type).getPrecision();
      checkTemporalPrecision(precision, "TIME");
      return Types.TimeType.of(precision);
    } else if (TimestampType.class.isAssignableFrom(typeClass)) {
      int precision = ((TimestampType) type).getPrecision();
      checkTemporalPrecision(precision, "TIMESTAMP");
      return Types.TimestampType.withoutTimeZone(precision);
    } else if (TimestampWithTimeZoneType.class.isAssignableFrom(typeClass)) {
      int precision = ((TimestampWithTimeZoneType) type).getPrecision();
      checkTemporalPrecision(precision, "TIMESTAMP WITH TIME ZONE");
      return Types.TimestampType.withTimeZone(precision);
    }

    return super.getGravitinoType(type);
  }

  private static void checkTemporalPrecision(int precision, String typeName) {
    if (precision > SQLSERVER_MAX_TEMPORAL_PRECISION) {
      throw new TrinoException(
          GravitinoErrorCode.GRAVITINO_UNSUPPORTED_TRINO_DATATYPE,
          String.format(
              "SQL Server does not support the datatype %s with the precision greater than %d",
              typeName, SQLSERVER_MAX_TEMPORAL_PRECISION));
    }
  }
}
