package cn.edu.thssdb.query;

import cn.edu.thssdb.common.Pair;
import cn.edu.thssdb.exception.KeyNotExistException;
import cn.edu.thssdb.schema.Cell;
import cn.edu.thssdb.schema.Column;
import cn.edu.thssdb.schema.Row;
import cn.edu.thssdb.schema.Table;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Designed for the select query with join/filtering...
 * hasNext() looks up whether the select result contains a next row
 * next() returns a row, plz keep an iterator.
 */

public class QueryTable implements Iterator<Row> {
  public List<Row> results;
  public List<Column> columns;
  public QueryTable(Table table) {
    results = new ArrayList<>();
    columns = new ArrayList<>();
    columns.addAll(table.columns);
    for (Row row : table) {
      results.add(row);
    }
  }
  public QueryTable(List<Row> rows,List<Column> columns){
    this.results = new ArrayList<>();
    this.columns = new ArrayList<>();
    this.results.addAll(rows);
    this.columns.addAll(columns);
  }

  @Override
  public boolean hasNext() {
    return results.iterator().hasNext();
  }

  @Override
  public Row next() {
    return results.iterator().next();
  }

  public int Column2Index(String columnName){
    ArrayList<String> columnNames = new ArrayList<>();
    for (Column column:this.columns) {
      columnNames.add(column.getColumnName());
    }
    return columnNames.indexOf(columnName);
  }

  public QueryTable combineQueryTable(QueryTable queryTable){
    List<Row> newRowList = new ArrayList<>();
    List<Column> newColumnList = new ArrayList<>(this.columns);
    newColumnList.addAll(queryTable.columns);
    for (Row row1: this.results) {
      for(Row row2: queryTable.results){
        Row newRow = new Row(row1);
        newRow.appendEntries(row2.getEntries());
        newRowList.add(newRow);
      }
    }
    return new QueryTable(newRowList,newColumnList);
  }
}