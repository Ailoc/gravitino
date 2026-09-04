---
title: "Trino Connector: SQL Server Catalog"
slug: "/trino-connector/catalog-sqlserver"
keyword: "gravitino connector trino"
license: "This software is licensed under the Apache License version 2."
---

## Introduction

The SQL Server catalog allows querying and creating tables in an external Microsoft SQL Server database.
This can be used to join data between different systems like SQL Server and Hive, or between different SQL Server instances.

## Requirements

To connect to SQL Server, you need:
- SQL Server 2019 or higher, which is what the Trino SQL Server connector requires. The Gravitino SQL Server catalog itself supports SQL Server 2017 and later.
- Network access from the Trino coordinator and workers to SQL Server. Port 1433 is the default port.
- The Trino SQL Server connector plugin installed on every Trino node, which registers the catalog under the name `sqlserver`.
- The Microsoft JDBC driver in the Gravitino server's `catalogs/jdbc-sqlserver/libs` directory. The driver is not distributed with Gravitino.

## Case Sensitivity

Identifier matching follows the collation of the database. The default SQL Server collations are case
insensitive, so `MyTable`, `mytable` and `MYTABLE` all refer to the same table. If the database uses a
case-sensitive collation, names must be referenced exactly as they were created.

Every statement the Gravitino SQL Server catalog generates quotes its identifiers with brackets, so a name
keeps the exact case it was created with even under a case-insensitive collation. Prefer lower-case names to
stay portable across engines.

## Create Table

At present, the Apache Gravitino Trino connector only supports basic SQL Server table creation statements, which involve fields, null allowances, and comments. However, it does not support advanced features like primary keys, indexes, default values, and auto-increment.
The Gravitino Trino connector supports `CREATE TABLE AS SELECT`.

:::note
`CREATE OR REPLACE TABLE AS SELECT` is not supported. Use `DROP TABLE` followed by `CREATE TABLE AS SELECT` as an alternative.
:::

A new column is always appended at the end of the table. SQL Server cannot place a column at a given position.

## Alter Table

Gravitino Trino connector supports the following alter table operations:
- Rename table
- Add a column
- Drop a column
- Rename a column
- Change a column type
- Set a table comment
- Set a column comment

:::note
Setting a table property is not supported. SQL Server tables carry no properties that Gravitino can alter, so
`ALTER TABLE ... SET PROPERTIES` is rejected by the SQL Server catalog.

SQL Server cannot add or drop `IDENTITY` on an existing column, so auto-increment cannot be changed after a
table is created. Recreate the table instead.
:::

## Select

The Gravitino Trino connector supports most SELECT statements, allowing the execution of queries successfully.
It doesn't support certain query optimizations, such as indexes and pushdowns.

## Update

Only `UPDATE` statements with constant assignments and predicates are supported. See also [UPDATE limitation](https://trino.io/docs/current/connector/sqlserver.html#update-limitation).

## Delete

If the `WHERE` clause is specified, only the matching rows are deleted. Otherwise, all rows from the table are deleted. See also [DELETE limitation](https://trino.io/docs/current/connector/sqlserver.html#delete-limitation).

## Merge

Not support.

## Table and Schema Properties

SQL Server's tables and schemas cannot support properties.

Gravitino stores its entity id in an `MS_Description` extended property on the schemas and tables it creates,
and keeps that message when a comment is set. Do not edit or remove it, or Gravitino loses track of the entity.

## Data Types

SQL Server's `BIT` surfaces in Trino as `BOOLEAN`, and its unsigned `TINYINT` as `SMALLINT`. The
fractional-second precision of `TIME`, `DATETIME2` and `DATETIMEOFFSET` is capped at 7, which is lower than
the 12 Trino allows. A Gravitino string type maps to an unbounded Trino `VARCHAR`, which SQL Server stores as
an `NVARCHAR(MAX)`.

For the full mapping, refer to the [SQL Server catalog](../jdbc-sqlserver-catalog.md#table-column-types).

## Examples

Complete the following steps before you can use the SQL Server catalog in Trino through Gravitino:

- Create a metalake and catalog in Gravitino. Assuming that the metalake name is `test` and the catalog name is `sqlserver_test`, then you can use the following code to create them in Gravitino:

```bash
curl -X POST -H "Content-Type: application/json" \
-d '{
  "name": "test",
  "comment": "comment",
  "properties": {}
}' http://gravitino-host:8090/api/metalakes

curl -X POST -H "Content-Type: application/json" \
-d '{
  "name": "sqlserver_test",
  "type": "RELATIONAL",
  "comment": "comment",
  "provider": "jdbc-sqlserver",
  "properties": {
    "jdbc-url": "jdbc:sqlserver://sqlserver-host:1433;databaseName=mydb",
    "jdbc-user": "<user>",
    "jdbc-password": "<password>",
    "jdbc-database": "mydb",
    "jdbc-driver": "com.microsoft.sqlserver.jdbc.SQLServerDriver"
  }
}' http://gravitino-host:8090/api/metalakes/test/catalogs
```

The `jdbc-database` property is mandatory and must name the same database as the `jdbc-url`. SQL Server
qualifies every statement with the database name, and the catalog fails to initialize without it. A SQL Server
instance holds several databases, but one Gravitino catalog maps to exactly one of them.

For more information about the SQL Server catalog, refer to [SQL Server catalog](../jdbc-sqlserver-catalog.md).

- Set the value of configuration `gravitino.metalake` to the metalake you have created, named 'test', and start the Trino container.

Use the Trino CLI to connect to the Trino container and run a query.

Listing all Gravitino managed catalogs:

```sql
SHOW CATALOGS;
```

The results are similar to:

```text
    Catalog
----------------
 gravitino
 jmx
 system
 sqlserver_test
(4 rows)

Query 20231017_082503_00018_6nt3n, FINISHED, 1 node
```

The `gravitino` catalog is a catalog defined By Trino catalog configuration.
The `sqlserver_test` catalog is the catalog created by you in Gravitino.
Other catalogs are regular user-configured Trino catalogs.

### Create Tables and Schemas

Create a new schema named `schema_01` in `sqlserver_test` catalog.

```sql
CREATE SCHEMA sqlserver_test.schema_01;
```

The default `dbo` schema is surfaced as a Gravitino schema too, so it can be used like any other. The `sys`,
`information_schema` and `guest` schemas and the fixed database roles are filtered out.

Create a new table named `table_01` in schema `sqlserver_test.schema_01`.

```sql
CREATE TABLE sqlserver_test.schema_01.table_01
(
name varchar,
salary int
);
```

### Write Data

Insert data into the table `table_01`:

```sql
INSERT INTO sqlserver_test.schema_01.table_01 (name, salary) VALUES ('ice', 12);
```

Insert data into the table `table_01` from select:

```sql
INSERT INTO sqlserver_test.schema_01.table_01 (name, salary) SELECT * FROM sqlserver_test.schema_01.table_01;
```

Update data into the table `table_01`:

```sql
UPDATE sqlserver_test.schema_01.table_01 SET name = 'ice_update' WHERE salary = 12;
```

Delete data from the table `table_01`:

```sql
DELETE FROM sqlserver_test.schema_01.table_01 WHERE salary = 12;
DELETE FROM sqlserver_test.schema_01.table_01;
```

### Query Data

Query the `table_01` table:

```sql
SELECT * FROM sqlserver_test.schema_01.table_01;
```

### Modify a Table

Add a new column `age` to the `table_01` table:

```sql
ALTER TABLE sqlserver_test.schema_01.table_01 ADD COLUMN age int;
```

Drop a column `age` from the `table_01` table:

```sql
ALTER TABLE sqlserver_test.schema_01.table_01 DROP COLUMN age;
```

Rename the `table_01` table to `table_02`:

```sql
ALTER TABLE sqlserver_test.schema_01.table_01 RENAME TO sqlserver_test.schema_01.table_02;
```

### Drop

Drop a schema:

```sql
DROP SCHEMA sqlserver_test.schema_01;
```

Drop a table:

```sql
DROP TABLE sqlserver_test.schema_01.table_01;
```
