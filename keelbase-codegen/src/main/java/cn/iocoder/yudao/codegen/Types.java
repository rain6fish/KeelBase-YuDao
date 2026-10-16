// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.codegen;

/**
 * 规格里的类型词表到三种目标的映射，集中在这一处：DDL 用的 PostgreSQL 列型、工具面用的参数种类与实参转换、
 * 以及 Java 字段类型。三份产出都从这里取，故换一种类型词只改这里。
 *
 * <p>规格里的类型是宿主中立的中文/英文词（`string` / `int` / `bool` / `date` / `text` / `enum`），而三份
 * 产物各有自己的一门语言——把这个映射散在三处，就会出现「DDL 认了、工具面没认」那类不一致。
 *
 * <p>The Spec's type words, mapped to the three target vocabularies this generator writes: the
 * PostgreSQL column type for the DDL, the tool-parameter kind and the argument conversion for the tool
 * face, and the Java field type. The vocabulary lives here and nowhere else, so all three outputs are
 * changed by one edit rather than three that can drift apart.
 */
final class Types {

    // **PostgreSQL**, because that is where this line put its datastore (D27). The spike's DDL was MySQL —
    // written before that decision and runnable there — and it does not run here: `auto_increment` is not
    // PostgreSQL, and neither is an inline `comment '…'`. Comments become separate `COMMENT ON` statements,
    // which is what the host's generator reads when it imports the table.
    // **PostgreSQL**——因为本线的数据存储放在那里（D27）。Spike 那份 DDL 是 MySQL 的：写在那条决定之前、
    // 在那边跑得动，**在这里跑不动**：`auto_increment` 不是 PostgreSQL 的，行内 `comment '…'` 也不是。
    // 注释改写成独立的 `COMMENT ON` 语句——那正是宿主生成器导入这张表时所读的东西。
    private static final String PG_STRING = "varchar(255)";
    private static final String PG_TEXT = "text";
    private static final String PG_INT = "bigint";
    private static final String PG_BOOL = "boolean";
    private static final String PG_DATE = "date";

    private Types() {
    }

    /** The Spec's type word to a PostgreSQL column type. An unknown word is a Spec bug, not a fallback. */
    static String pgType(String type) {
        return switch (type) {
            case "string" -> PG_STRING;
            case "text" -> PG_TEXT;
            case "int" -> PG_INT;
            case "bool" -> PG_BOOL;
            case "date" -> PG_DATE;
            case "enum" -> PG_STRING;
            default -> throw new IllegalArgumentException("field has unknown type `" + type + "`");
        };
    }

    /** The Spec's type word to the generated DO's Java field type. Kept for parity with the other two
     * mappings even though no current template reads it; a future one will. */
    static String javaType(String type) {
        return switch (type) {
            case "int" -> "Long";
            case "bool" -> "Boolean";
            case "date" -> "LocalDate";
            default -> "String";
        };
    }

    /** The Spec's type word to the tool face's parameter kind. */
    static String toolParamType(String type) {
        return switch (type) {
            case "int" -> "NUMBER";
            case "bool" -> "BOOLEAN";
            default -> "STRING";
        };
    }

    /** How a tool turns the incoming argument into the DO's setter argument. */
    static String convert(String type) {
        return switch (type) {
            case "int" -> "asLong";
            case "bool" -> "asBool";
            default -> "str";
        };
    }
}
