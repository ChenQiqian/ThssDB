package cn.edu.thssdb.schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class LockManager {
    private Database database;
    public HashMap<Long, ArrayList<ReentrantReadWriteLock.ReadLock>> readLockList;
    public HashMap<Long, ArrayList<ReentrantReadWriteLock.WriteLock>> writeLockList;

    public LockManager(Database database) {
        this.database = database;
        this.readLockList = new HashMap<>();
        this.writeLockList = new HashMap<>();
    }

//    public void getReadLock(Long session, String tableName) {
//        Table table = this.database.get(tableName);
//        ReentrantReadWriteLock.ReadLock readLock = table.lock.readLock();
//        readLock.lock();
//        if(!readLockList.containsKey(session)) {
//            readLockList.put(session, new ArrayList<>());
//        }
//        ArrayList<ReentrantReadWriteLock.ReadLock> currentSessionReadLockList = readLockList.get(session);
//        currentSessionReadLockList.add(readLock);
//        readLockList.put(session, currentSessionReadLockList);
//        System.out.println(readLockList.toString());
//    }
//
//    public void releaseReadLock(Long session, String tableName) {
//        Table table = this.database.get(tableName);
//        ReentrantReadWriteLock.ReadLock readLock = table.lock.readLock();
////        System.out.println(readLockList.toString());
//        if(!readLockList.containsKey(session) && readLockList.get(session).remove(readLock)) {
//            readLock.unlock();
//        }
//        else {
//            System.out.println("Failed to release ReadLock:" + session.toString() + "\n");
//        }
//    }

    public void getWriteLock(Long session, Table.TableHandler tb) {
        Boolean newSetLock = tb.setWriteLock();
        if(!newSetLock) return;
        if(!writeLockList.containsKey(session)) {
            writeLockList.put(session, new ArrayList<>());
        }
        ArrayList<ReentrantReadWriteLock.WriteLock> currentSessionWriteLockList = writeLockList.get(session);
        currentSessionWriteLockList.add(tb.getTable().lock.writeLock());
        writeLockList.put(session, currentSessionWriteLockList);
    }

    public void releaseWriteLock(Long session, Table.TableHandler tb) {
        ReentrantReadWriteLock.WriteLock writeLock = tb.getTable().lock.writeLock();
        System.out.println(writeLockList.toString());
        if(writeLockList.containsKey(session) && writeLockList.get(session).remove(writeLock)) {
            writeLock.unlock();
        }
    }

    public void releaseSessionAllWriteLock(Long session) {
        if(!writeLockList.containsKey(session)){
            return;
        }
        ArrayList<ReentrantReadWriteLock.WriteLock> sessionLockList = writeLockList.get(session);
        for(ReentrantReadWriteLock.WriteLock lock : sessionLockList){
            lock.unlock();
            // System.out.println("release write lock from lock manager" + lock.toString());
        }
        sessionLockList.clear();
        writeLockList.put(session, sessionLockList);
        if(writeLockList.get(session).size() > 0){
            System.out.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!wrong size");
        }
    }
    public void releaseSessionAllReadLock(Long session) {
        if(!readLockList.containsKey(session)){
            return;
        }
        ArrayList<ReentrantReadWriteLock.ReadLock> sessionLockList = readLockList.get(session);
        for(ReentrantReadWriteLock.ReadLock lock : sessionLockList){
            lock.unlock();
        }
        sessionLockList.clear();
        readLockList.put(session, sessionLockList);
    }
    public void releaseSessionAllLock(Long session) {
        releaseSessionAllWriteLock(session);
        releaseSessionAllReadLock(session);
    }
}
