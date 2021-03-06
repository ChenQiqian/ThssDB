package cn.edu.thssdb.schema;

import cn.edu.thssdb.exception.*;
import cn.edu.thssdb.index.BPlusTree;
import cn.edu.thssdb.common.Global;
import cn.edu.thssdb.common.Pair;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static cn.edu.thssdb.type.ColumnType.STRING;

public class Table implements Iterable<Row> {
  public ReentrantReadWriteLock lock;
  private String databaseName;
  public String tableName;
  public ArrayList<Column> columns;
  public BPlusTree<Cell, Row> index;
  private int primaryIndex;

  public class TableHandler implements AutoCloseable {
    private Table table;
    private Boolean haveReadLock;
    private Boolean haveWriteLock;
    public TableHandler(Table table, Boolean read, Boolean write){
      this.table = table;
      this.haveReadLock = read;
      this.haveWriteLock = write;
      if(read){
        //System.out.println("get read lock" + this.table.tableName);
        this.table.lock.readLock().lock();
      }
      if(write) {
        //System.out.println("get write lock" + this.table.tableName);

        this.table.lock.writeLock().lock();
      }
    }
    public Boolean setWriteLock() {
      if(this.haveReadLock){
        //System.out.println("release read lock" + this.table.tableName);
        this.table.lock.readLock().unlock();
        this.haveReadLock = false;
      }
      if(this.table.lock.isWriteLockedByCurrentThread()){
        return false;
      }
      //System.out.println("get write lock " + this.table.tableName);

      this.table.lock.writeLock().lock();
      this.haveWriteLock = true;
      return true;
    }
    public Table getTable(){ return this.table; }
    @Override
    public void close() {
      // 这里可以根据不同的隔离级别选择不同的 Close 方式
      // 目前只支持 Read Committed
      if(this.haveReadLock) {
        // System.out.println("release read lock " + this.table.tableName);
        this.table.lock.readLock().unlock();
        this.haveReadLock = false;
      }
    }
  }

  public TableHandler getTableHandler() {
    return new TableHandler(this, true, false);
  }


  // Initiate: Table, recover
  public Table(String databaseName, String tableName, Column[] columns) {
    this.lock = new ReentrantReadWriteLock();
    this.databaseName = databaseName;
    this.tableName = tableName;
    this.columns = new ArrayList<>(Arrays.asList(columns));
    this.index = new BPlusTree<>();
    this.primaryIndex = -1;

    for (int i=0;i<this.columns.size();i++)
    {
      if(this.columns.get(i).isPrimary()){
        if(this.primaryIndex >= 0)
          throw new MultiPrimaryKeyException(this.tableName);
        this.primaryIndex = i;
      }
    }
    if(this.primaryIndex < 0)
      throw new MultiPrimaryKeyException(this.tableName);

    recover();
  }

  public void recover() {
    // read from disk for recovering
    ArrayList<Row> rowsOnDisk = deserialize();
    for(Row row: rowsOnDisk)
      this.index.put(row.getEntries().get(this.primaryIndex), row);
  }

  public int getPrimaryIndex(){
    return this.primaryIndex;
  }

  // Operations: get, insert, delete, update, dropTable, you can add other operations.

  public Row get(Cell primaryCell){
    return this.index.get(primaryCell);
  }
  public void insert(Row row) {
    this.checkRowValidInTable(row);
    if(this.containsRow(row))
      throw new DuplicateKeyException();
    this.index.put(row.getEntries().get(this.primaryIndex), row);
  }

  public void delete(Row row) {
    this.checkRowValidInTable(row);
    if(!this.containsRow(row))
      throw new KeyNotExistException();
    this.index.remove(row.getEntries().get(this.primaryIndex));
  }

  public void update(Cell primaryCell, Row newRow) {
    this.checkRowValidInTable(newRow);
    Row oldRow = this.get(primaryCell);
    /* 感觉这里有问题，按这个就只能修改主键了，所以我给他注释了
     if(this.containsRow(newRow))
     throw new DuplicateKeyException();   // 要么删并插入，要么抛出异常
     */
    this.index.remove(primaryCell);
    this.index.put(newRow.getEntries().get(this.primaryIndex), newRow);
  }


  /**
   * 将表的列名换为tableName_columnName的形式
   * 这里似乎不应该加锁
   * @return 一个新表
   */
  public Table getColumnFullNameTable(){
    ArrayList<Column> newColumns = new ArrayList<>();
    for (Column column: columns) {
      String newColumnName = this.tableName+"_"+column.getColumnName();
      Column newColumn = new Column(newColumnName,column.getColumnType(),column.getPrimary(),column.cantBeNull(),column.getMaxLength());
      newColumns.add(newColumn);
    }
    Column[] newColumn = newColumns.toArray(new Column[0]);
    Table newTable = new Table(this.databaseName,this.tableName,newColumn);
    newTable.index = this.index;
    //newTable.lock = this.lock;?
    return newTable;
  }

  private void serialize() {
    try {
      File tableFolder = new File(this.getTableFolderPath());
      if (!tableFolder.exists() ? !tableFolder.mkdirs() : !tableFolder.isDirectory())
        throw new FileIOException(this.getTableFolderPath() + " on serializing table in folder");
      File tableFile = new File(this.getTablePath());
      if (!tableFile.exists() ? !tableFile.createNewFile() : !tableFile.isFile())
        throw new FileIOException(this.getTablePath() + " on serializing table to file");
      FileOutputStream fileOutputStream = new FileOutputStream(this.getTablePath());
      ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream);
      for(Row row: this)
        objectOutputStream.writeObject(row);
      objectOutputStream.close();
      fileOutputStream.close();
    }catch (IOException e){
      throw new FileIOException(this.getTablePath() + " on serializing");
    }
  }

  private ArrayList<Row> deserialize() {
    try {
      File tableFolder = new File(this.getTableFolderPath());
      if (!tableFolder.exists() ? !tableFolder.mkdirs() : !tableFolder.isDirectory())
        throw new FileIOException(this.getTableFolderPath() + " when deserialize");
      File tableFile = new File(this.getTablePath());
      if(!tableFile.exists())
        return new ArrayList<>();
      FileInputStream fileInputStream = new FileInputStream(this.getTablePath());
      ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
      ArrayList<Row> rowsOnDisk = new ArrayList<>();
      Object tmpObj;
      while(fileInputStream.available() > 0){
        tmpObj = objectInputStream.readObject();
        rowsOnDisk.add((Row) tmpObj);
      }
      objectInputStream.close();
      fileInputStream.close();
      return rowsOnDisk;
    }catch (IOException e){
      throw new FileIOException(this.getTablePath() + " when deserialize");
    }catch (ClassNotFoundException e){
      throw new FileIOException(this.getTablePath() + " when deserialize(serialized object cannot be found)");
    }
  }

  public void persist(){
    serialize();
  }

  public void dropTable(){ // remove table data file
    File tableFolder = new File(this.getTableFolderPath());
    if (!tableFolder.exists() ? !tableFolder.mkdirs() : !tableFolder.isDirectory())
      throw new FileIOException(this.getTableFolderPath() + " when dropTable");
    File tableFile = new File(this.getTablePath());
    if(tableFile.exists() && !tableFile.delete())
      throw new FileIOException(this.getTablePath() + " when dropTable");
  }

  public int Column2Index(String columnName){
    ArrayList<String> columnNames = new ArrayList<>();
    for (Column column:this.columns) {
      columnNames.add(column.getColumnName());
    }
    return columnNames.indexOf(columnName);
  }
  // Operations involving logic expressions.




  // Operations

  private class TableIterator implements Iterator<Row> {
    private Iterator<Pair<Cell, Row>> iterator;

    TableIterator(Table table) {
      this.iterator = table.index.iterator();
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public Row next() {
      return iterator.next().right;
    }
  }

  @Override
  public Iterator<Row> iterator() {
    return new TableIterator(this);
  }

  private void checkRowValidInTable(Row row){
    if(row.getEntries().size()!=this.columns.size())
      throw new SchemaLengthMismatchException(this.columns.size(), row.getEntries().size(), "when check Row Valid In table");
    for(int i=0;i<row.getEntries().size();i++) {
      String entryValueType = row.getEntries().get(i).getValueType();
      Column column = this.columns.get(i);
      if(entryValueType.equals(Global.ENTRY_NULL)){
        if(column.cantBeNull()) throw new NullValueException(column.getColumnName());
      }
      else{
        if (!entryValueType.equals(column.getColumnType().name()))
          throw new ValueFormatInvalidException("(when check row valid in table)");
        Comparable entryValue = row.getEntries().get(i).value;
        if(entryValueType.equals(STRING.name()) && ((String) entryValue).length()>column.getMaxLength())
          throw new ValueExceedException(column.getColumnName(), ((String) entryValue).length(), column.getMaxLength(), "(when check row valid in table)");
      }
    }
  }

  private Boolean containsRow(Row row){
    return this.index.contains(row.getEntries().get(this.primaryIndex));
  }

  public String getTableFolderPath(){
    return Global.DBMS_DIR + File.separator + "data" + File.separator + databaseName + File.separator + "tables";
  }
  public String getTablePath(){
    return this.getTableFolderPath() + File.separator + this.tableName;
  }
  public String getTableMetaPath(){
    return this.getTablePath() + Global.META_SUFFIX;
  }

  public String toString(){
    StringBuilder s = new StringBuilder("Table " + this.tableName + ": ");
    for (Column column : this.columns) s.append("\t(").append(column.toString()).append(')');
    return s.toString() + "\n";
  }

}
