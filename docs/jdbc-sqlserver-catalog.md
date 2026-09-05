---
title: "SQL Server Catalog"
slug: "/jdbc-sqlserver-catalog"
keywords:
- jdbc
- SQL Server
- metadata
license: "This software is licensed under the Apache License version 2."
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

## Introduction

Apache Gravitino provides the ability to manage Microsoft SQL Server metadata.

:::caution
Gravitino saves some system information in schema and table comment, like `(From Gravitino, DO NOT EDIT: gravitino.v1.uid1078334182909406185)`, do not change or remove this message.
:::

## Catalog

### Catalog Capabilities

- Gravitino catalog corresponds to the SQL Server database.
- Supports metadata management of SQL Server 2017 and later.
- Supports DDL operation for SQL Server schemas and tables.
- Supports table index.
- Supports [column default value](./tables-and-views.md#table-column-default-value) and [auto-increment](./tables-and-views.md#table-column-auto-increment) on table creation.

### Catalog Properties

Any property that isn't defined by Gravitino can pass to the SQL Server data source by adding `gravitino.bypass.` prefix as a catalog property. For example, catalog property `gravitino.bypass.maxWaitMillis` will pass `maxWaitMillis` to the data source property.
Check the relevant data source configuration in [data source properties](https://commons.apache.org/proper/commons-dbcp/configuration.html)

When using Gravitino with Trino, pass the Trino SQL Server connector configuration using the `trino.bypass.` prefix. For example, using `trino.bypass.join-pushdown.strategy` to pass the `join-pushdown.strategy` to the Gravitino SQL Server catalog in Trino runtime.

If you use JDBC catalog, you must provide `jdbc-url`, `jdbc-driver`, `jdbc-database`, `jdbc-user` and `jdbc-password` to catalog properties.
Besides the [common catalog properties](./gravitino-server-config.md#catalog-properties-configuration), the SQL Server catalog has the following properties:

| Configuration item      | Description                                                                                                                                                       | Default value | Required |
|-------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------|----------|
| `jdbc-url`              | JDBC URL for connecting to the database. You need to specify the database in the URL. For example `jdbc:sqlserver://localhost:1433;databaseName=your_database;encrypt=false`. | (none)        | Yes      |
| `jdbc-driver`           | The driver of the JDBC connection. For example `com.microsoft.sqlserver.jdbc.SQLServerDriver`.                                                                     | (none)        | Yes      |
| `jdbc-database`         | The database of the JDBC connection. Configure it with the same value as the database in the `jdbc-url`. For example `your_database`.                              | (none)        | Yes      |
| `jdbc-user`             | The JDBC user name.                                                                                                                                               | (none)        | Yes      |
| `jdbc-password`         | The JDBC password.                                                                                                                                                | (none)        | Yes      |
| `jdbc.pool.min-size`    | The minimum number of connections in the pool. `2` by default.                                                                                                    | `2`           | No       |
| `jdbc.pool.max-size`    | The maximum number of connections in the pool. `10` by default.                                                                                                   | `10`          | No       |
| `jdbc.pool.max-wait-ms` | The maximum Duration that the pool will wait for a connection to be returned. `30000` by default.                                                                 | `30000`       | No       |

:::caution
Download the Microsoft JDBC driver to the `catalogs/jdbc-sqlserver/libs` directory. The driver is not distributed with Gravitino.
Explicitly specify the database in both `jdbc-url` and `jdbc-database`. `jdbc-database` is mandatory: SQL Server qualifies every statement with the database name, and the catalog fails to initialize without it.
:::
:::caution
mssql-jdbc 10.2 and later default to `encrypt=true`, so a server presenting a self-signed certificate fails the connection with `PKIX path building failed`. Add `trustServerCertificate=true` to the `jdbc-url` to keep the link encrypted while accepting that certificate, which is what a development or containerized SQL Server needs. `encrypt=false` sends the traffic unencrypted and is only appropriate on a network you already trust.
:::
:::info
In SQL Server, the database corresponds to the Gravitino catalog, and the schema corresponds to the Gravitino schema. A SQL Server instance holds several databases, but one Gravitino catalog maps to exactly one of them.
:::

### Catalog Operations

Refer to [Manage Catalogs and Schemas](./manage-catalogs-and-schemas.md#catalog-operations) for more details.

## Schema

### Schema Capabilities

- Gravitino schema corresponds to the SQL Server schema.
- Supports creating schema with comments, stored as an `MS_Description` extended property.
- Supports dropping schema.
- Supports cascade dropping schema.

The `sys`, `information_schema` and `guest` schemas are filtered out of `listSchemas`, as are the fixed database roles such as `db_owner` and `db_datareader`, which the SQL Server driver also reports through `DatabaseMetaData#getSchemas`. Those names are rejected when creating a schema.

:::info
The default `dbo` schema is not filtered, so it is surfaced as a Gravitino schema and can be used like any other.
:::

### Schema Properties

- Doesn't support any schema property settings.

### Schema Operations

Refer to [Manage Catalogs and Schemas](./manage-catalogs-and-schemas.md#schema-operations) for more details.

## Table

### Table Capabilities

- The Gravitino table corresponds to the SQL Server table.
- Supports DDL operation for SQL Server tables.
- Supports index.
- Supports [column default value](./tables-and-views.md#table-column-default-value).
- Doesn't support table property settings.
- Doesn't support table partitioning, distribution or sort orders.

### Naming Rules

Gravitino rejects a SQL Server name that does not match `^[a-zA-Z_\p{L}@#][\w\p{L}$@#]{0,127}$`: it must start with a letter of any language, `_`, `@` or `#`, continue with letters, digits, `_`, `$`, `@` or `#`, and stay within the 128 character limit. Every statement this catalog generates brackets its identifiers, so a name keeps the exact case it was created with.

### Table Column Types

| Gravitino Type    | SQL Server Type                      |
|-------------------|--------------------------------------|
| `Boolean`         | `BIT`                                |
| `Byte`            | `SMALLINT`                           |
| `Short`           | `SMALLINT`                           |
| `Integer`         | `INT`                                |
| `Long`            | `BIGINT`                              |
| `Float`           | `REAL`                               |
| `Double`          | `FLOAT`                              |
| `Decimal(p,s)`    | `DECIMAL(p,s)`                       |
| `String`          | `NVARCHAR(MAX)`                      |
| `VarChar(n)`      | `NVARCHAR(n)`, `NVARCHAR(MAX)` when n &gt; 4000 |
| `FixedChar(n)`    | `NCHAR(n)`, `NVARCHAR(MAX)` when n &gt; 4000 |
| `Binary`          | `VARBINARY(MAX)`                     |
| `Date`            | `DATE`                               |
| `Time(p)`         | `TIME(p)`                            |
| `Timestamp(p)`    | `DATETIME2(p)`                       |
| `Timestamp_tz(p)` | `DATETIMEOFFSET(p)`                  |
| `UUID`            | `UNIQUEIDENTIFIER`                   |

Reading types back from SQL Server:

| SQL Server Type                        | Gravitino Type    |
|----------------------------------------|-------------------|
| `TINYINT`                              | `Short`           |
| `MONEY`                                | `Decimal(19,4)`   |
| `SMALLMONEY`                           | `Decimal(10,4)`   |
| `DATETIME`, `SMALLDATETIME`            | `Timestamp(p)`    |
| `TEXT`, `NTEXT`                        | `String`          |
| `VARCHAR(MAX)`, `NVARCHAR(MAX)`        | `String`          |
| `IMAGE`, `ROWVERSION`                  | `Binary`          |
| `xml`, `sql_variant`, `geography`, `geometry`, `hierarchyid`, user-defined types | [External Type](./tables-and-views.md#external-type) |

:::info
SQL Server doesn't support the Gravitino `Fixed`, `Struct`, `Map`, `IntervalDay`, `IntervalYear` and `Union` types.
:::
:::caution
SQL Server `TINYINT` is unsigned and holds 0 to 255, which does not fit Gravitino's signed `Byte`, so it reads back as `Short`. Gravitino's `Byte` and `Short` both write `SMALLINT` for the same reason.
`TIME`, `DATETIME2` and `DATETIMEOFFSET` accept a fractional-second precision from 0 to 7 only.
:::

### Table Column Auto-Increment

- Supports setting auto-increment on table creation, which SQL Server implements as `IDENTITY(1,1)`.
- An identity column is always `NOT NULL` and cannot also carry a default value.

:::caution
SQL Server cannot add or drop `IDENTITY` on an existing column, so the `UpdateColumnAutoIncrement` operation is rejected. Recreate the table to change auto-increment.
:::

### Table Properties

- Doesn't support table properties.

### Table Indexes

- Supports PRIMARY_KEY and UNIQUE_KEY.

<Tabs groupId='language' queryString>
<TabItem value="json" label="JSON">

```json
{
  "indexes": [
    {
      "indexType": "primary_key",
      "name": "id_pk",
      "fieldNames": [["id"]]
    },
    {
      "indexType": "unique_key",
      "name": "id_name_uk",
      "fieldNames": [["id"] ,["name"]]
    }
  ]
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
Index[] indexes = new Index[] {
    Indexes.of(IndexType.PRIMARY_KEY, "id_pk", new String[][]{{"id"}}, Map.of()),
    Indexes.of(IndexType.UNIQUE_KEY, "id_name_uk", new String[][]{{"id"} , {"name"}}, Map.of()),
}
```

</TabItem>
</Tabs>

### Table Operations

Refer to [Manage Relational Metadata Using Gravitino](./manage-relational-metadata-using-gravitino.md#table-operations) for more details.

#### Alter Table Operations

Supports operations:

- `RenameTable`
- `UpdateComment`
- `AddColumn`
- `DeleteColumn`
- `RenameColumn`
- `UpdateColumnType`
- `UpdateColumnNullability`
- `UpdateColumnComment`
- `UpdateColumnDefaultValue`
- `AddIndex`
- `DeleteIndex`

:::info
You can't submit the `RenameTable` operation at the same time as other operations.
:::

:::caution
SQL Server doesn't support the `UpdateColumnPosition` operation, so you can only use `ColumnPosition.defaultPosition()` when `AddColumn`.
`ALTER COLUMN` replaces the whole column definition, so changing a column type, its nullability or its default value restates the attributes this catalog knows about. If you update a nullable column to non-nullable, existing `NULL` rows make the statement fail.
SQL Server binds a column default through a named constraint object and refuses to rewrite or delete a column that still carries one, so `UpdateColumnType`, `UpdateColumnNullability`, `UpdateColumnDefaultValue` and `DeleteColumn` drop that constraint first. `UpdateColumnNullability` attaches it again afterwards; `UpdateColumnType` leaves the column without a default, because the previous expression was written against the previous type.
SQL Server has no recycle bin, so dropping a table cannot be undone.
:::
