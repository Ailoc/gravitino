---
title: "Oracle Catalog"
slug: "/jdbc-oracle-catalog"
keywords:
- jdbc
- Oracle
- metadata
license: "This software is licensed under the Apache License version 2."
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

## Introduction

Apache Gravitino provides the ability to manage Oracle metadata.

:::caution
Gravitino saves some system information in the table comment, like `(From Gravitino, DO NOT EDIT: gravitino.v1.uid1078334182909406185)`, do not change or remove this message.
Oracle has no `COMMENT ON SCHEMA` statement, so a Gravitino schema carries no such message and cannot store a user-supplied comment.
:::

## Catalog

### Catalog Capabilities

- Gravitino catalog corresponds to the Oracle database (the PDB or service named in `jdbc-url`).
- Supports metadata management of Oracle 12.2 and later. Earlier releases cap an identifier at 30 bytes and reject a longer one with `ORA-00972`.
- Supports DDL operation for Oracle schemas and tables.
- Supports table index.
- Supports [column default value](./tables-and-views.md#table-column-default-value) and [auto-increment](./tables-and-views.md#table-column-auto-increment).

### Catalog Properties

Any property that isn't defined by Gravitino can pass to the Oracle data source by adding `gravitino.bypass.` prefix as a catalog property. For example, catalog property `gravitino.bypass.maxWaitMillis` will pass `maxWaitMillis` to the data source property.
Check the relevant data source configuration in [data source properties](https://commons.apache.org/proper/commons-dbcp/configuration.html)

When using Gravitino with Trino, pass the Trino Oracle connector configuration using the `trino.bypass.` prefix. For example, using `trino.bypass.join-pushdown.strategy` to pass the `join-pushdown.strategy` to the Gravitino Oracle catalog in Trino runtime.

If you use JDBC catalog, you must provide `jdbc-url`, `jdbc-driver`, `jdbc-user` and `jdbc-password` to catalog properties.
Besides the [common catalog properties](./gravitino-server-config.md#catalog-properties-configuration), the Oracle catalog has the following properties:

| Configuration item      | Description                                                                                                                                        | Default value | Required |
|-------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|---------------|----------|
| `jdbc-url`              | JDBC URL for connecting to the database. The Oracle service or PDB is part of the URL, for example `jdbc:oracle:thin:@//localhost:1521/ORCLPDB1`. | (none)        | Yes      |
| `jdbc-driver`           | The driver of the JDBC connection. For example `oracle.jdbc.OracleDriver`.                                                                          | (none)        | Yes      |
| `jdbc-user`             | The JDBC user name.                                                                                                                                 | (none)        | Yes      |
| `jdbc-password`         | The JDBC password.                                                                                                                                  | (none)        | Yes      |
| `jdbc.pool.min-size`    | The minimum number of connections in the pool. `2` by default.                                                                                      | `2`           | No       |
| `jdbc.pool.max-size`    | The maximum number of connections in the pool. `10` by default.                                                                                     | `10`          | No       |
| `jdbc.pool.max-wait-ms` | The maximum Duration that the pool will wait for a connection to be returned. `30000` by default.                                                   | `30000`       | No       |

:::caution
Download the Oracle JDBC driver to the `catalogs/jdbc-oracle/libs` directory. The driver is not distributed with Gravitino.
:::
:::info
In Oracle, the database corresponds to the Gravitino catalog, and the Oracle schema (which Oracle implements as a user) corresponds to the Gravitino schema. Oracle has no intermediate level, so the `jdbc-database` property is not used.
:::

### Catalog Operations

Refer to [Manage Catalogs and Schemas](./manage-catalogs-and-schemas.md#catalog-operations) for more details.

## Schema

### Schema Capabilities

- Gravitino schema corresponds to the Oracle schema.
- Doesn't support creating schema with comments, because Oracle has no `COMMENT ON SCHEMA` statement.
- Supports dropping schema.
- Supports cascade dropping schema.

Creating a schema issues `CREATE USER "<schema>" NO AUTHENTICATION`, which creates a schema-only account that cannot log in, followed by `ALTER USER "<schema>" QUOTA UNLIMITED ON "<default tablespace>"`. The quota grant is required because Oracle checks the tablespace quota of the schema owner rather than the account issuing the DDL, so without it every `CREATE TABLE` in the new schema fails with `ORA-01950`.

:::caution
Oracle commits implicitly around each DDL statement, so a failure between the two statements can leave a schema that exists without a tablespace quota. Grant the quota manually if that happens.
:::

`listSchemas` returns the schemas `ALL_USERS` marks with `ORACLE_MAINTAINED = 'N'`, which is the distinction Oracle itself keeps between a schema a user owns and one Oracle owns. That covers the administrative accounts such as `sys`, `system`, `xdb`, `mdsys`, `ctxsys`, `sysman`, `audsys` and `dbsfwuser`, the versioned Application Express schemas matching `apex_` followed by six digits, and any internal account a later release adds.

Oracle leaves a few of its own accounts unmarked, so a fixed set of reserved names is removed from the result as well. `PDBADMIN`, which creating a pluggable database leaves behind, reports `ORACLE_MAINTAINED = 'N'` on Oracle Database Free release 23.26, and no other `ALL_USERS` column separates it from a schema a user owns. The same set rejects a reserved name when a schema is created or renamed, where a name missing from it costs nothing because Oracle rejects a `CREATE USER` colliding with an existing account anyway.

### Schema Properties

- Doesn't support any schema property settings.

### Schema Operations

Refer to [Manage Catalogs and Schemas](./manage-catalogs-and-schemas.md#schema-operations) for more details.

## Table

### Table Capabilities

- The Gravitino table corresponds to the Oracle table.
- Supports DDL operation for Oracle tables.
- Supports index.
- Supports [column default value](./tables-and-views.md#table-column-default-value) and [auto-increment](./tables-and-views.md#table-column-auto-increment).
- Doesn't support table property settings.
- Doesn't support table partitioning, distribution or sort orders.

### Naming Rules

Gravitino rejects an Oracle name that does not match `^[a-zA-Z][a-zA-Z0-9_$#]{0,127}$`: it must start with an ASCII letter, continue with ASCII letters, digits, `_`, `$` or `#`, and stay within the 128 byte limit. Every statement this catalog generates quotes its identifiers, so a name keeps the exact case it was created with instead of being folded to upper case.

### Table Column Types

| Gravitino Type    | Oracle Type                     |
|-------------------|---------------------------------|
| `Boolean`         | `NUMBER(1)`                     |
| `Byte`            | `NUMBER(3)`                     |
| `Short`           | `NUMBER(5)`                     |
| `Integer`         | `NUMBER(10)`                    |
| `Long`            | `NUMBER(19)`                    |
| `Float`           | `BINARY_FLOAT`                  |
| `Double`          | `BINARY_DOUBLE`                 |
| `Decimal(p,s)`    | `NUMBER(p,s)`                   |
| `String`          | `CLOB`                          |
| `VarChar(n)`      | `VARCHAR2(n CHAR)`, `CLOB` when n &gt; 4000 |
| `FixedChar(n)`    | `CHAR(n CHAR)`, `VARCHAR2(n CHAR)` when n &gt; 2000, `CLOB` when n &gt; 4000 |
| `Binary`          | `BLOB`                          |
| `Date`            | `DATE`                          |
| `Timestamp(p)`    | `TIMESTAMP(p)`                  |
| `Timestamp_tz(p)` | `TIMESTAMP(p) WITH TIME ZONE`   |
| `UUID`            | `RAW(16)`                       |

Reading types back from Oracle:

| Oracle Type                       | Gravitino Type    |
|-----------------------------------|-------------------|
| `NUMBER(p,s)`                     | `Decimal(min(p,38),min(s,min(p,38)))` |
| `NUMBER` without precision        | `Decimal(38,18)`  |
| `NUMBER(p,-s)`                    | `Decimal(min(p+s,38),0)` |
| `DATE`                            | `Timestamp(0)`    |
| `TIMESTAMP(p)`                    | `Timestamp(p)`    |
| `TIMESTAMP(p) WITH TIME ZONE`     | `Timestamp_tz(p)` |
| `TIMESTAMP(p) WITH LOCAL TIME ZONE` | `Timestamp(p)`  |
| `LONG RAW`                        | `Binary`          |
| `XMLTYPE`, `ROWID`, `INTERVAL ...` | [External Type](./tables-and-views.md#external-type) |

:::info
Oracle doesn't support the Gravitino `Time`, `Fixed`, `Struct`, `Map`, `IntervalDay`, `IntervalYear` and `Union` types. Oracle has no time-of-day type; its `DATE` already carries the time down to the second, which is why `DATE` reads back as `Timestamp(0)`.
:::
:::caution
Oracle stores the empty string as `NULL`, so a column default of `''` reads back as a null default.
:::
:::caution
Every `NUMBER` spelling reads back as a `Decimal`. `INTEGER`, `INT` and `SMALLINT` are only alternate names for `NUMBER` in Oracle, so a column created as `Integer` reads back as `Decimal(10,0)` and one created as `Boolean` reads back as `Decimal(1,0)`.
:::

### Table Column Auto-Increment

- Supports setting auto-increment, which Oracle implements as `GENERATED BY DEFAULT AS IDENTITY`.
- An identity column implies `NOT NULL` and cannot also carry a default value.

:::caution
Adding an identity column to a table that already holds rows needs `START WITH LIMIT VALUE`, which this catalog does not emit. Add identity columns to an empty table.
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
- `UpdateColumnAutoIncrement`
- `AddIndex`
- `DeleteIndex`

:::info
You can't submit the `RenameTable` operation at the same time as other operations.
:::

:::caution
Oracle doesn't support the `UpdateColumnPosition` operation, so you can only use `ColumnPosition.defaultPosition()` when `AddColumn`.
Oracle's `ALTER TABLE ... MODIFY` changes one attribute at a time, so updating a column type, its nullability or its default value leaves the other two untouched.
Dropping a table issues `DROP TABLE ... PURGE`, which bypasses the Oracle recycle bin and cannot be undone with `FLASHBACK TABLE`.
:::
