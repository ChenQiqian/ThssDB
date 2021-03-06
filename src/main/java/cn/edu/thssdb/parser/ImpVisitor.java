package cn.edu.thssdb.parser;


import cn.edu.thssdb.common.Global;
import cn.edu.thssdb.exception.*;
import cn.edu.thssdb.parser.item.*;
import cn.edu.thssdb.query.QueryResult;
import cn.edu.thssdb.query.QueryTable;
import cn.edu.thssdb.schema.Database;
import cn.edu.thssdb.schema.Manager;
import cn.edu.thssdb.schema.Table;
import cn.edu.thssdb.schema.Column;
import cn.edu.thssdb.schema.Row;
import cn.edu.thssdb.schema.Cell;
import cn.edu.thssdb.type.ColumnType;
import cn.edu.thssdb.type.ComparerType;


import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * When use SQL sentence, e.g., "SELECT avg(A) FROM TableX;"
 * the parser will generate a grammar tree according to the rules defined in SQL.g4.
 * The corresponding terms, e.g., "select_stmt" is a root of the parser tree, given the rules
 * "select_stmt :
 *     K_SELECT ( K_DISTINCT | K_ALL )? result_column ( ',' result_column )*
 *         K_FROM table_query ( ',' table_query )* ( K_WHERE multiple_condition )? ;"
 *
 * This class "ImpVisit" is used to convert a tree rooted at e.g. "select_stmt"
 * into the collection of tuples inside the database.
 *
 */

public class ImpVisitor extends SQLBaseVisitor<Object> {
    private Manager manager;
    private long session;

    public ImpVisitor(Manager manager, long session) {
        super();
        this.manager = manager;
        this.session = session;
    }

    public QueryResult visitSql_stmt(SQLParser.Sql_stmtContext ctx) {
        if (ctx.create_db_stmt() != null) return new QueryResult(visitCreate_db_stmt(ctx.create_db_stmt()));
        if (ctx.drop_db_stmt() != null) return new QueryResult(visitDrop_db_stmt(ctx.drop_db_stmt()));
        if (ctx.use_db_stmt() != null)  return new QueryResult(visitUse_db_stmt(ctx.use_db_stmt()));
        if (ctx.create_table_stmt() != null) return new QueryResult(visitCreate_table_stmt(ctx.create_table_stmt()));
        if (ctx.drop_table_stmt() != null) return new QueryResult(visitDrop_table_stmt(ctx.drop_table_stmt()));
        if (ctx.insert_stmt() != null) return new QueryResult(visitInsert_stmt(ctx.insert_stmt()));
        if (ctx.delete_stmt() != null) return new QueryResult(visitDelete_stmt(ctx.delete_stmt()));
        if (ctx.update_stmt() != null) return new QueryResult(visitUpdate_stmt(ctx.update_stmt()));
        if (ctx.select_stmt() != null) return visitSelect_stmt(ctx.select_stmt());
        if (ctx.quit_stmt() != null) return new QueryResult(visitQuit_stmt(ctx.quit_stmt()));
        if (ctx.show_meta_stmt()!=null) return new QueryResult(visitShow_meta_stmt(ctx.show_meta_stmt()));
        if (ctx.show_table_stmt()!=null) return new QueryResult(visitShow_table_stmt(ctx.show_table_stmt()));
        if (ctx.show_db_stmt()!=null) return new QueryResult(visitShow_db_stmt(ctx.show_db_stmt()));
        return null;
    }
    /**
     * ????????????????????? {@code create database <database_name>}????????? {@link Manager#createDatabaseIfNotExists}
     * @param ctx ??????????????????
     * @return ?????????????????????????????????????????????????????????
     */
    @Override
    public String visitCreate_db_stmt(SQLParser.Create_db_stmtContext ctx) {
        try {
            manager.createDatabaseIfNotExists(ctx.database_name().getText().toLowerCase());
            manager.persist();
        } catch (Exception e) {
            return e.getMessage();
        }
        return "Create database " + ctx.database_name().getText() + ".";
    }

    /**
     * ????????????????????? {@code delete database <database_name>}????????? {@link Manager#deleteDatabase}
     * @param ctx ??????????????????
     * @return ?????????????????????????????????????????????????????????
     */
    @Override
    public String visitDrop_db_stmt(SQLParser.Drop_db_stmtContext ctx) {
        try {
            manager.deleteDatabase(ctx.database_name().getText().toLowerCase());
        } catch (Exception e) {
            return e.getMessage();
        }
        return "Drop database " + ctx.database_name().getText() + ".";
    }

    /**
     * ????????????????????? {@code use database <database_name>}????????? {@link Manager#switchDatabase}
     * @param ctx ??????????????????
     * @return ?????????????????????????????????????????????????????????
     */
    @Override
    public String visitUse_db_stmt(SQLParser.Use_db_stmtContext ctx) {
        try {
            manager.switchDatabase(ctx.database_name().getText().toLowerCase());
        } catch (Exception e) {
            return e.getMessage();
        }
        return "Switch to database " + ctx.database_name().getText() + ".";
    }

    /**
     * ?????????????????? {@code drop table <table_name>}, ?????? {@link Database#drop}
     * @param ctx ??????????????????
     * @return ?????????????????????????????????????????????????????????
     */
    @Override
    public String visitDrop_table_stmt(SQLParser.Drop_table_stmtContext ctx) {
        try (Database.DatabaseHandler db = manager.getCurrentDatabase(false,true)) {
            String tableName = ctx.table_name().getText().toLowerCase();
            db.getDatabase().drop(session, tableName);
        } catch(Exception e) {
            return e.getMessage();
        }
        return "Drop table " + ctx.table_name().getText() + ".";
    }

    /**
     * ?????????????????? {@code create table <database_name>}????????? {@link Database#create(String, Column[])}
     * @param ctx ??????????????????
     * @return ?????????????????????????????????????????????????????????
     */
    @Override
    public String visitCreate_table_stmt(SQLParser.Create_table_stmtContext ctx) {

        String tableName = ctx.table_name().IDENTIFIER().getSymbol().getText().toLowerCase();
        ArrayList<Column> columnList = new ArrayList<>();
        //??????columnItem?????????columnList
        for(int i = 0; i < ctx.column_def().size();i++){
            SQLParser.Column_defContext columnDefItem = ctx.column_def(i);
            String columnName = columnDefItem.column_name().getText().toLowerCase();

            String typeName = columnDefItem.type_name().getChild(0).getText().toUpperCase();
            ColumnType columntype = ColumnType.valueOf(typeName);
            //?????????String???????????????maxLength
            int maxLength = 0;
            if(columnDefItem.type_name().getChildCount()>1){
                maxLength = Integer.parseInt(columnDefItem.type_name().getChild(2).getText());
            }
            boolean notNull = false;
            int primary = 0;
            for(int j = 0 ; j < columnDefItem.column_constraint().size();j++){
                if(columnDefItem.column_constraint(j).getChild(1).getText().equalsIgnoreCase("NULL")){
                    notNull = true;
                }
                else if(columnDefItem.column_constraint(j).getChild(1).getText().equalsIgnoreCase("KEY")){
                    primary = 1;
                }
            }
            //maxLength:????????????????????????????????????????????????30??????????????????
            Column column = new Column(columnName,columntype,primary,notNull,maxLength);
            columnList.add(column);
        }
        //??????????????????column???notNull???
        for (Column column : columnList) {
            if (column.isPrimary()) {
                column.setNotNull(true);
            }
        }
        //??????Table constraints????????????????????????primary???notNull
        for (int i = 0;i<ctx.table_constraint().column_name().size();i++){
            String primary_column = ctx.table_constraint().column_name(i).getText();
            for (Column column : columnList) {
                if (primary_column.equalsIgnoreCase(column.getColumnName())) {
                    column.setPrimary(1);
                    column.setNotNull(true);
                }
            }
        }
        //???ArrayList???????????????
        Column[] columns = columnList.toArray(new Column[0]);

        try (Database.DatabaseHandler db = manager.getCurrentDatabase(false, true)){
            db.getDatabase().create(tableName, columns); //??????
        }catch(Exception e){
            return e.getMessage();
        }
        return "Create table " + ctx.table_name().getText() + ".";
    }

    /**
     *
     ???????????????:  {@code K_INSERT K_INTO table_name ( '(' column_name ( ',' column_name )* ')' )?
     K_VALUES value_entry ( ',' value_entry )* }}
     @implNote ?????? {@link Database#tableInsert}
     @apiNote Insert_into ????????????mysql????????????????????????
        * column_name?????????????????????????????????????????????
        * ?????????column_name??????value_entry??????????????????????????????????????????????????????????????? null
        * column_name????????????????????????

     */
    @Override
    public String visitInsert_stmt(SQLParser.Insert_stmtContext ctx) {
        try(Database.DatabaseHandler db = manager.getCurrentDatabase(true, false)){// ???????????????????????????????????????
            String tableName = ctx.table_name().getText();
            try(Table.TableHandler tb = db.getDatabase().get(tableName)) {
                Table table = tb.getTable();
                //????????????value_entry????????????
                ArrayList<ArrayList<String>> valueEntryList_str_List = new ArrayList<>();
                for (SQLParser.Value_entryContext value_entry_ctx : ctx.value_entry()) {
                    ArrayList<String> strArray = new ArrayList<>();
                    for (SQLParser.Literal_valueContext literal_value_ctx : value_entry_ctx.literal_value()) {
                        String str = literal_value_ctx.getText();
                        strArray.add(str);
                    }
                    valueEntryList_str_List.add(strArray);
                }

                for (ArrayList<String> valueEntryList_str : valueEntryList_str_List) {
                    ArrayList<Cell> value_entry = new ArrayList<>();
                    //???????????????????????????
                    if (ctx.column_name().size() == 0) {
                        //?????????????????????table.columns????????????Table.CheckValidRow?????????
                        //????????????????????????????????????Cell?????????Row
                        for (int i = 0; i < valueEntryList_str.size(); i++) {
                            String columnType = table.columns.get(i).getColumnType().name().toUpperCase();
                            switch (columnType) {
                                case "INT": {
                                    int num = Integer.parseInt(valueEntryList_str.get(i));
                                    Cell cell = new Cell(num);
                                    value_entry.add(cell);
                                    break;
                                }
                                case "LONG": {
                                    long num = Long.parseLong(valueEntryList_str.get(i));
                                    Cell cell = new Cell(num);
                                    value_entry.add(cell);
                                    break;
                                }
                                case "FLOAT": {
                                    float f = Float.parseFloat(valueEntryList_str.get(i));
                                    Cell cell = new Cell(f);
                                    value_entry.add(cell);
                                    break;
                                }
                                case "DOUBLE": {
                                    double d = Double.parseDouble(valueEntryList_str.get(i));
                                    Cell cell = new Cell(d);
                                    value_entry.add(cell);
                                    break;
                                }
                                case "STRING": {
                                    Cell cell = new Cell(valueEntryList_str.get(i));
                                    value_entry.add(cell);
                                    break;
                                }
                                default:
                                    throw new ValueFormatInvalidException(". column type parser fault");
                            }
                        }
                    } else {
                        //??????column name???value entry????????????
                        if (ctx.column_name().size() != valueEntryList_str.size()) {
                            int expectedLen = ctx.column_name().size();
                            int realLen = valueEntryList_str.size();
                            throw new SchemaLengthMismatchException(expectedLen, realLen, " column name mismatch to value entry.");
                        }
                        //??????column name????????????????????????
                        for (int i = 0; i < ctx.column_name().size() - 1; i++) {
                            for (int j = i + 1; j < ctx.column_name().size(); j++) {
                                if (ctx.column_name(i).getText().equals(ctx.column_name(j).getText())) {
                                    throw new DuplicateKeyException();
                                }
                            }
                        }
                        //?????????value_entry
                        for (int i = 0; i < table.columns.size(); i++) {
                            Cell cell = new Cell();
                            value_entry.add(cell);
                        }
                        for (int i = 0; i < ctx.column_name().size(); i++) {
                            //?????????column_name?????????????????????index???type
                            int index = -1;
                            String targetType = "null";
                            for (int j = 0; j < table.columns.size(); j++) {
                                if (table.columns.get(j).getColumnName().equalsIgnoreCase(ctx.column_name(i).getText())) {
                                    index = j;
                                    targetType = table.columns.get(j).getColumnType().name().toUpperCase();
                                    break;
                                }
                            }
                            if (index < 0) {
                                throw new KeyNotExistException();
                            }
                            switch (targetType) {
                                case "INT": {
                                    int num = Integer.parseInt(valueEntryList_str.get(i));
                                    Cell cell = new Cell(num);
                                    value_entry.set(index, cell);
                                    break;
                                }
                                case "LONG": {
                                    long num = Long.parseLong(valueEntryList_str.get(i));
                                    Cell cell = new Cell(num);
                                    value_entry.set(index, cell);
                                    break;
                                }
                                case "FLOAT": {
                                    float f = Float.parseFloat(valueEntryList_str.get(i));
                                    Cell cell = new Cell(f);
                                    value_entry.set(index, cell);
                                    break;
                                }
                                case "DOUBLE": {
                                    double d = Double.parseDouble(valueEntryList_str.get(i));
                                    Cell cell = new Cell(d);
                                    value_entry.set(index, cell);
                                    break;
                                }
                                case "STRING": {
                                    Cell cell = new Cell(valueEntryList_str.get(i));
                                    value_entry.set(index, cell);
                                    break;
                                }
                                default:
                                    throw new ValueFormatInvalidException(". Target type is " + targetType);
                            }
                        }
                    }
                    //???value_entry??????row
                    Row rowToInsert = new Row(value_entry);
                    //?????? Database ????????????????????????
                    db.getDatabase().tableInsert(session, tb, rowToInsert);

                }
            }
        } catch(Exception e) {
            return e.getMessage();
        }
        return "Insert into " + ctx.table_name().getText() + " successfully";
    }

    /**
     * ?????? Table ???
     * @implNote ????????? {@link Database#tableDelete}
     */
    @Override
    public String visitDelete_stmt(SQLParser.Delete_stmtContext ctx) {
        try(Database.DatabaseHandler db = manager.getCurrentDatabase(true, false)){
            String tableName = ctx.table_name().getText().toLowerCase();
            try(Table.TableHandler tb = db.getDatabase().get(tableName)) {
                Table table = tb.getTable();
                if (ctx.K_WHERE() == null) {
                    return "Exception: Delete without where";
                }

                /*
                String operator = ctx.multiple_condition().condition().comparator().getText();

                String judgeName = ctx.multiple_condition().condition().expression(0).comparer().column_full_name().column_name().getText().toLowerCase();
                String compareValue = ctx.multiple_condition().condition().expression(1).comparer().literal_value().getText();

                Cell judgeValue = parseEntry(compareValue, table.columns.get(table.columnFind(judgeName)));
                Iterator<Row> iterator = table.iterator();
                ArrayList<Row> delete_list = to_operate_rows(operator,iterator, table.columnFind(judgeName), judgeValue);

                for (Row delete_row:delete_list) {
                    table.delete(delete_row);
                }*/


                MultipleConditionItem whereItem = null;
                if (ctx.multiple_condition() != null) {
                    whereItem = visitMultiple_condition(ctx.multiple_condition());
                }
                ArrayList<String> columnNames = new ArrayList<>();
                ArrayList<Column> columns = table.columns;
                for (Column c : columns) {
                    columnNames.add(c.getColumnName());
                }
                if (whereItem == null) {
                    return "Exception: Delete without where";
                } else {
                    for (Row row : table) {
                        if (whereItem.evaluate(row, columnNames)) {
                            db.getDatabase().tableDelete(session, tb, row);
                        }
                    }
                }
            }
        }
        return "Delete from " + ctx.table_name().getText() + " successfully";
    }

    /**
     * ???????????????(update)
     * @implNote ?????? {@link Database#tableUpdate}
     */
    @Override
    public String visitUpdate_stmt(SQLParser.Update_stmtContext ctx) {
        try(Database.DatabaseHandler db = manager.getCurrentDatabase(true, false)) {
            String tableName = ctx.table_name().getText();
            try(Table.TableHandler tb = db.getDatabase().get(tableName)) {
                Table table = tb.getTable();
                String columnName = ctx.column_name().getText();

                //??????columnNames
                ArrayList<String> columnNames = new ArrayList<>();
                ArrayList<Column> columns = table.columns;
                for (Column c : columns) {
                    columnNames.add(c.getColumnName());
                }

                //????????????????????????
                MultipleConditionItem whereItem = null;
                ArrayList<Row> rowToUpdate = new ArrayList<>();
                if (ctx.multiple_condition() != null) {
                    whereItem = visitMultiple_condition(ctx.multiple_condition());
                }

                Iterator<Row> rowIterator = table.iterator();
                if (whereItem == null) {
                    while (rowIterator.hasNext()) {
                        Row row = rowIterator.next();
                        rowToUpdate.add(row);
                    }
                } else {
                    while (rowIterator.hasNext()) {
                        Row row = rowIterator.next();
                        if (whereItem.evaluate(row, columnNames)) {
                            rowToUpdate.add(row);
                        }
                    }
                }

                //????????????????????????
                int index = table.Column2Index(columnName);
                ComparerItem expr = visitExpression(ctx.expression());
                Cell newCell = new Cell((Comparable) expr.getValue());
                for (Row row : rowToUpdate) {
                    Row newRow = new Row();
                    ArrayList<Cell> entries = row.getEntries();
                    for (int i = 0; i < entries.size(); i++) {
                        if (i == index) {
                            newRow.getEntries().add(newCell);
                        } else {
                            newRow.getEntries().add(entries.get(i));
                        }
                    }
                    Cell primaryCell = entries.get(table.getPrimaryIndex());
                    //System.out.println("primaryCell = " + primaryCell.toString());
                    //System.out.println("oldRow = " + row.toString());
                    //System.out.println("newRow = " + newRow.toString());

                    db.getDatabase().tableUpdate(session, tb, primaryCell, newRow);
                }
                return "Update " + rowToUpdate.size() + " rows";
            }
        }
        catch (Exception e){
            return "Error when updating.\n";
        }
    }

    /**
     * ??????????????????{@code SELECT tableName1.AttrName1, tableName1.AttrName2???, tableName2.AttrName1, tableName2.AttrName2,???
     FROM  tableName1 [JOIN tableName2 [ON  tableName1.attrName1 = tableName2.attrName2]]
     [ WHERE  attrName1 = attrValue ]}
     * ?????????????????????????????????table_query?????????????????????
     * @implNote
     */
    @Override
    public QueryResult visitSelect_stmt(SQLParser.Select_stmtContext ctx) {
        try(Database.DatabaseHandler db = manager.getCurrentDatabase(true, false)){
            //?????????????????????????????????tableQuery,????????????????????????????????????
            SQLParser.Table_queryContext tableQuery = ctx.table_query().get(0);
            String firstTableName = tableQuery.table_name(0).getText();
            try(Table.TableHandler firsttb = db.getDatabase().get(firstTableName)) {
                Table firstTable = firsttb.getTable();
                // ??????from?????????????????? targetTable
                // select from ???????????????,???????????????????????????????????????targetTable
                QueryTable targetTable = new QueryTable(firstTable);
                if (tableQuery.table_name().size() > 1) {
                    Table newFirstTable = firstTable.getColumnFullNameTable();
                    targetTable = new QueryTable(newFirstTable);
                    for (int i = 1; i < tableQuery.table_name().size(); i++) {
                        String nowTableName = tableQuery.table_name(i).getText();
                        try(Table.TableHandler nowTable = db.getDatabase().get(nowTableName)) {
                            targetTable = targetTable.join(nowTable.getTable());
                        }
                    }
                    System.out.println(targetTable.toString());
                    //??? On ?????????????????????????????????????????????
                    if (tableQuery.multiple_condition() != null) {
                        MultipleConditionItem onItem = visitMultiple_condition(tableQuery.multiple_condition());
                        Iterator<Row> rowIterator = targetTable.results.iterator();
                        ArrayList<String> columnNames = new ArrayList<>();
                        for (Column column : targetTable.columns) {
                            columnNames.add(column.getColumnName());
                        }
                        List<Row> rowToDelete = new ArrayList<>();
                        while (rowIterator.hasNext()) {
                            Row row = rowIterator.next();
                            if (!onItem.evaluate(row, columnNames)) {
                                rowToDelete.add(row);
                            }
                        }
                        targetTable.results.removeAll(rowToDelete);
                    }
                }
                // ??? where ??????????????????????????????????????????
                if (ctx.multiple_condition() != null) {
                    MultipleConditionItem whereItem = visitMultiple_condition(ctx.multiple_condition());
                    Iterator<Row> rowIterator = targetTable.results.iterator();
                    ArrayList<String> columnNames = new ArrayList<>();
                    for (Column column : targetTable.columns) {
                        columnNames.add(column.getColumnName());
                    }
                    List<Row> rowToDelete = new ArrayList<>();
                    while (rowIterator.hasNext()) {
                        Row row = rowIterator.next();
                        if (!whereItem.evaluate(row, columnNames)) {
                            rowToDelete.add(row);
                        }
                    }
                    targetTable.results.removeAll(rowToDelete);
                }
                //???select??????????????????
                ArrayList<Column> selectColumns = new ArrayList<>();
                ArrayList<Row> rowList = new ArrayList<>();
                if (ctx.result_column().get(0).getText().equals("*")) {
                    selectColumns.addAll(targetTable.columns);
                    rowList.addAll(targetTable.results);
                } else {
                    //?????????????????????
                    ArrayList<String> selectColumnName = new ArrayList<>();
                    for (SQLParser.Result_columnContext columnContext : ctx.result_column()) {
                        if (columnContext.column_full_name() != null) {//?????????????????????????????????????????????column_full_name
                            String columnName = columnContext.column_full_name().column_name().getText();
                            if (columnContext.column_full_name().table_name() != null && tableQuery.table_name().size() > 1) {
                                columnName = columnContext.column_full_name().table_name().getText() + "_" + columnName;
                            }
                            selectColumnName.add(columnName);
                        }
                    }
                    /*
                    System.out.print("selectColumnsName:");
                    for(String columnName:selectColumnName){
                        System.out.print(columnName + " ");
                    }
                    System.out.println(" ");
                    */
                    //??????selectColumnName?????????index
                    ArrayList<Integer> selectColumnIndex = new ArrayList<>();
                    for (String columnName : selectColumnName) {
                        int index = targetTable.Column2Index(columnName);
                        selectColumns.add(targetTable.columns.get(index));
                        selectColumnIndex.add(index);
                    }
                    /*
                    System.out.print("selectColumnIndex:");
                    for(Integer index:selectColumnIndex){
                        System.out.print(index + " ");
                    }
                    */

                    //?????????????????????
                    for (Row row : targetTable.results) {
                        ArrayList<Cell> Entries = row.getEntries();
                        ArrayList<Cell> newEntries = new ArrayList<>();
                        for (int i = 0; i < Entries.size(); i++) {
                            if (selectColumnIndex.contains(i)) {
                                //System.out.println(i+"is in selectColumnIndex");
                                newEntries.add(Entries.get(i));
                            }
                        }
                        Row newRow = new Row(newEntries);
                        rowList.add(newRow);
                    }
                }
                //??????ArrayList<Column> selectColumns ??????
                //??????ArrayList<Row> rowList ??????
                //?????????????????????
                /*
                for (Column column:selectColumns) {
                    System.out.print(column.toString() + " ");
                }
                System.out.println(" ");
                for(Row row:rowList){
                    System.out.println(row.toString());
                }
                 */
                QueryTable queryTable = new QueryTable(rowList, selectColumns);
                QueryTable[] queryTables = {queryTable};
                return new QueryResult(queryTables);
            }
        }
        catch(Exception e) {
            return new QueryResult(e.getMessage());
        }
    }

    /**
     * Finished and Tested
     *
     * ?????????(?????????) SHOW TABLE tableName
     */
    @Override
    public String visitShow_meta_stmt(SQLParser.Show_meta_stmtContext ctx){
        try(Database.DatabaseHandler db = manager.getCurrentDatabase(true, false)){
            String tableName = ctx.table_name().getText();
            try(Table.TableHandler tb = db.getDatabase().get(tableName)) {
                Table table = tb.getTable();
                return table.toString();
            }
        }
        catch(Exception e){
            return e.getMessage();
        }
    }
    /**
     *
     * ??????????????????????????????
     * SHOW DATABASE database_name
     */
    @Override
    public String visitShow_table_stmt(SQLParser.Show_table_stmtContext ctx){
        String databaseName = ctx.database_name().getText();
        try(Database.DatabaseHandler db = manager.get(databaseName, true, false)){
            return db.getDatabase().getTableInfo();
        }
        catch(Exception e){
            return e.getMessage();
        }
    }
    /**
     *
     * ??????????????? SHOW DATABASES
     */
    @Override
    public String visitShow_db_stmt(SQLParser.Show_db_stmtContext ctx){
        try{
            return manager.getDatabaseInfo();
        }
        catch(Exception e){
            return e.getMessage();
        }
    }
    /**
     *
     ?????? manager
     */
    @Override
    public String visitQuit_stmt(SQLParser.Quit_stmtContext ctx) {
        try {
            manager.quit();
        } catch (Exception e) {
            return e.getMessage();
        }
        return "Quit.";
    }
    @Override
    public Object visitParse(SQLParser.ParseContext ctx) {
        return visitSql_stmt_list(ctx.sql_stmt_list());
    }

    @Override
    public Object visitSql_stmt_list(SQLParser.Sql_stmt_listContext ctx) {
        ArrayList<QueryResult> ret = new ArrayList<>();
        for (SQLParser.Sql_stmtContext subCtx : ctx.sql_stmt()) ret.add(visitSql_stmt(subCtx));
        return ret;
    }

    @Override
    public ComparerItem visitComparer(SQLParser.ComparerContext ctx){
        if(ctx.column_full_name()!=null){
            String tableName = null;
            if(ctx.column_full_name().table_name() != null){
                tableName = ctx.column_full_name().table_name().IDENTIFIER().getText();
            }
            String columnName = ctx.column_full_name().column_name().IDENTIFIER().getText();
            return new ComparerItem(ComparerType.COLUMN,tableName,columnName);
        }
        else if(ctx.literal_value()!=null){
            String literalValue = "null";
            if(ctx.literal_value().NUMERIC_LITERAL()!=null){
                literalValue = ctx.literal_value().NUMERIC_LITERAL().getText();
                return new ComparerItem(ComparerType.NUMBER,literalValue);
            }
            else if(ctx.literal_value().STRING_LITERAL()!=null){
                literalValue = ctx.literal_value().STRING_LITERAL().getText();
                return new ComparerItem(ComparerType.STRING,literalValue);
            }
            return new ComparerItem(ComparerType.NULL,literalValue);
        }
        //???????????????null?????????return null
        return null;
    }
    @Override
    public ComparerItem visitExpression(SQLParser.ExpressionContext ctx){
        if(ctx.comparer()!=null){
            return (ComparerItem) visit(ctx.comparer());
        }
        else if (ctx.expression().size()==1){
            return (ComparerItem) visit(ctx.getChild(1));
        }
        else {
            ComparerItem compItem1 = (ComparerItem) visit(ctx.getChild(0));
            ComparerItem compItem2 = (ComparerItem) visit(ctx.getChild(2));

            if ((compItem1.type != ComparerType.NUMBER && compItem1.type!=ComparerType.COLUMN) ||
                    (compItem2.type != ComparerType.NUMBER && compItem2.type!=ComparerType.COLUMN)) {
                throw new TypeNotMatchException(compItem1.type, ComparerType.NUMBER);
            }
            /*
            String newLiteralValue;

            Double itemValue1 = Double.parseDouble(compItem1.literalValue);
            Double itemValue2 = Double.parseDouble(compItem2.literalValue);
            Double newValue=0.0;
            String op = ctx.getChild(1).getText();
            switch(op){
                case "+": newValue = itemValue1+itemValue2;break;
                case "-": newValue = itemValue1-itemValue2;break;
                case "*": newValue = itemValue1*itemValue2;break;
                case "/": newValue = itemValue1/itemValue2;break;
                default:
            }
            if(newValue.intValue() == newValue.doubleValue()){
                newLiteralValue=String.valueOf(newValue.intValue());
            }
            else{
                newLiteralValue = newValue.toString();
            }
             */
            ComparerItem newComparerItem = new ComparerItem(compItem1,compItem2,ctx.getChild(1).getText());
            newComparerItem.type = ComparerType.NUMBER;
            //newComparerItem.literalValue=newLiteralValue;

            return newComparerItem;
        }
    }

    @Override
    public ConditionItem visitCondition(SQLParser.ConditionContext ctx){
        ComparerItem comparerItem1 = (ComparerItem) visit(ctx.getChild(0));
        ComparerItem comparerItem2 = (ComparerItem) visit(ctx.getChild(2));
        return new ConditionItem(comparerItem1,comparerItem2,ctx.getChild(1).getText());
    }

    @Override
    public MultipleConditionItem visitMultiple_condition(SQLParser.Multiple_conditionContext ctx){
        if(ctx.getChildCount() == 1) {
            return new MultipleConditionItem((ConditionItem) visit(ctx.getChild(0)));
        }

        MultipleConditionItem m1 = (MultipleConditionItem) visit(ctx.getChild(0));
        MultipleConditionItem m2 = (MultipleConditionItem) visit(ctx.getChild(2));
        return new MultipleConditionItem(m1, m2, ctx.getChild(1).getText());
    }

}


