package com.bishokudev.sheets;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.beehive.*;
import com.atlassian.sal.api.transaction.TransactionCallback;
import com.bishokudev.sheets.ao.Revision;
import net.java.ao.*;
import org.json.*;
import org.junit.Test;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.Assert.*;

/** Fault-injection unit tests; these do not substitute for a real AO database/cluster test. */
public class SheetStoreTest {
    @org.junit.Before public void clearCache(){SheetStore.clearCache();}
    static class TestLock extends ReentrantLock implements ClusterLock {}
    static class Harness implements InvocationHandler {
        List<Map<String,Object>> committed=new ArrayList<>(); boolean failCommit;
        final TestLock lock=new TestLock();
        SheetStore store(){ActiveObjects ao=(ActiveObjects)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ActiveObjects.class},this);return new SheetStore(ao,name->lock);}
        Revision proxy(Map<String,Object> values){return (Revision)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Revision.class},(p,m,args)->{
            String field=m.getName().substring(3).replaceAll("([a-z])([A-Z])","$1_$2").toUpperCase(Locale.ROOT);return values.get(field);
        });}
        public Object invoke(Object p,Method method,Object[] args) {
            switch(method.getName()){
                case "executeInTransaction":
                    List<Map<String,Object>> previous=new ArrayList<>(committed);
                    try {Object result=((TransactionCallback<?>)args[0]).doInTransaction();if(failCommit)throw new IllegalStateException("Injected commit failure");return result;}
                    catch(RuntimeException e){committed=previous;throw e;}
                case "find":
                    Query q=(Query)args[1];String field=q.getWhereClause().split(" ")[0];
                    return committed.stream().filter(v->Objects.equals(v.get(field),q.getWhereParams()[0]) && (q.getWhereParams().length<2 || (q.getWhereClause().contains("ACTOR = ?") ? Objects.equals(v.get("ACTOR"),q.getWhereParams()[1]) : (q.getWhereClause().contains("SEQUENCE > ?") ? (Integer)v.get("SEQUENCE")>(Integer)q.getWhereParams()[1] && (Integer)v.get("SEQUENCE")<=(Integer)q.getWhereParams()[2] : (Integer)v.get("SEQUENCE")<=(Integer)q.getWhereParams()[1])))).sorted((a,b)->"SEQUENCE ASC".equals(q.getOrderClause())?Integer.compare((Integer)a.get("SEQUENCE"),(Integer)b.get("SEQUENCE")):Integer.compare((Integer)b.get("SEQUENCE"),(Integer)a.get("SEQUENCE"))).limit(q.getLimit()>0?q.getLimit():Long.MAX_VALUE).map(this::proxy).toArray(Revision[]::new);
                case "create":
                    assertTrue("Writes must hold shared lock",lock.isHeldByCurrentThread());Map<String,Object> values=new HashMap<>();for(DBParam v:(DBParam[])args[1])values.put(v.getField(),v.getValue());committed.add(values);return proxy(values);
                default:throw new UnsupportedOperationException(method.getName());
            }
        }
    }
    @Test public void retryAfterLostResponseReturnsReceiptWithoutDuplicate(){Harness h=new Harness();SheetStore a=h.store(),b=h.store();JSONObject create=new JSONObject().put("type","create");JSONObject first=a.write("1:sheet","user","00000000-0000-0000-0000-000000000001",create,()->{});JSONObject retry=b.write("1:sheet","user","00000000-0000-0000-0000-000000000001",create,()->{});assertEquals(first.toString(),retry.toString());assertEquals(1,h.committed.size());}
    @Test public void failedCommitLeavesNoReceiptAndRetryCanSucceed(){Harness h=new Harness();SheetStore s=h.store();h.failCommit=true;try{s.write("1:sheet","user","00000000-0000-0000-0000-000000000001",new JSONObject().put("type","create"),()->{});fail();}catch(IllegalStateException expected){}assertEquals(0,h.committed.size());assertFalse(h.lock.isLocked());h.failCommit=false;s.write("1:sheet","user","00000000-0000-0000-0000-000000000001",new JSONObject().put("type","create"),()->{});assertEquals(1,h.committed.size());}
    @Test public void permissionRecheckPreventsWriteAndReleasesLock(){Harness h=new Harness();try{h.store().write("1:sheet","user","00000000-0000-0000-0000-000000000001",new JSONObject().put("type","create"),()->{throw new SecurityException("revoked");});fail();}catch(SecurityException expected){}assertEquals(0,h.committed.size());assertFalse(h.lock.isLocked());}
    @Test(expected=SheetEngine.Conflict.class) public void reusedRequestWithDifferentPayloadRejected(){Harness h=new Harness();SheetStore s=h.store();String id="00000000-0000-0000-0000-000000000001";s.write("1:sheet","user",id,new JSONObject().put("type","create"),()->{});s.write("1:sheet","user",id,new JSONObject().put("type","addRow").put("expectedRevision",1),()->{});}
    @Test public void twentyConcurrentWritersPreserveIndependentCells() throws Exception {
        Harness h=new Harness();SheetStore store=h.store();JSONObject initial=store.write("1:sheet","admin",UUID.randomUUID().toString(),new JSONObject().put("type","create"),()->{});
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(20);
        java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> jobs=new ArrayList<>();
        try {
            for(int i=0;i<20;i++){final int row=i;jobs.add(pool.submit(()->{
                try{start.await();}catch(InterruptedException e){throw new RuntimeException(e);}
                JSONObject edit=new JSONObject().put("row",initial.getJSONArray("rows").getString(row)).put("column",initial.getJSONArray("columns").getString(0)).put("expectedVersion",0).put("cell",new JSONObject().put("value","writer-"+row).put("type","text"));
                h.store().write("1:sheet","user-"+row,UUID.randomUUID().toString(),new JSONObject().put("type","patch").put("edits",new JSONArray().put(edit)),()->{});
            }));}
            start.countDown();for(java.util.concurrent.Future<?> job:jobs)job.get(20,java.util.concurrent.TimeUnit.SECONDS);
            JSONObject latest=store.read("1:sheet");assertEquals(21,latest.getInt("revision"));assertEquals(20,latest.getJSONObject("cells").length());
            for(int r=0;r<20;r++)assertEquals("writer-"+r,latest.getJSONObject("cells").getJSONObject(initial.getJSONArray("rows").getString(r)+":"+initial.getJSONArray("columns").getString(0)).getString("value"));
        }finally{pool.shutdownNow();}
    }

    private JSONObject patch(JSONObject d,int row,String value,int version){return new JSONObject().put("type","patch").put("edits",new JSONArray().put(new JSONObject().put("row",d.getJSONArray("rows").getString(row)).put("column",d.getJSONArray("columns").getString(0)).put("expectedVersion",version).put("cell",new JSONObject().put("value",value).put("type","text"))));}
    @Test public void checkpointsReplayAndHistoricalReceiptsSurviveCacheLoss(){
        Harness h=new Harness();SheetStore s=h.store();JSONObject initial=s.write("1:sheet","u",UUID.randomUUID().toString(),new JSONObject().put("type","create"),()->{});
        String firstId=UUID.randomUUID().toString();JSONObject firstOp=patch(initial,0,"v0",0);
        JSONObject first=s.write("1:sheet","u",firstId,firstOp,()->{});
        for(int i=1;i<70;i++)s.write("1:sheet","u",UUID.randomUUID().toString(),patch(initial,0,"v"+i,i),()->{});
        SheetStore.clearCache();JSONObject finalDoc=s.read("1:sheet");assertEquals(71,finalDoc.getInt("revision"));
        assertEquals("v69",finalDoc.getJSONObject("cells").getJSONObject(initial.getJSONArray("rows").getString(0)+":"+initial.getJSONArray("columns").getString(0)).getString("value"));
        assertEquals(first.toString(),s.write("1:sheet","u",firstId,firstOp,()->{}).toString());assertEquals(71,h.committed.size());
        assertEquals(71,s.read("1:sheet").getInt("revision"));
        assertEquals(0,s.changes("1:sheet",71).getJSONArray("changes").length());
        assertEquals(6,s.changes("1:sheet",65).getJSONArray("changes").length());
        assertTrue(s.changes("1:sheet",31).has("document"));
    }
    @Test public void failedPatchCommitDoesNotPublishCachedState(){
        Harness h=new Harness();SheetStore s=h.store();JSONObject initial=s.write("1:sheet","u",UUID.randomUUID().toString(),new JSONObject().put("type","create"),()->{});
        h.failCommit=true;try{s.write("1:sheet","u",UUID.randomUUID().toString(),patch(initial,0,"never committed",0),()->{});fail();}catch(IllegalStateException expected){}
        assertEquals(1,s.read("1:sheet").getInt("revision"));assertEquals(0,s.read("1:sheet").getJSONObject("cells").length());
    }
    @Test(expected=IllegalStateException.class) public void missingJournalEntryFailsClosed(){
        Harness h=new Harness();SheetStore s=h.store();JSONObject initial=s.write("1:sheet","u",UUID.randomUUID().toString(),new JSONObject().put("type","create"),()->{});
        s.write("1:sheet","u",UUID.randomUUID().toString(),patch(initial,0,"one",0),()->{});
        s.write("1:sheet","u",UUID.randomUUID().toString(),patch(initial,1,"two",0),()->{});
        h.committed.remove(1);SheetStore.clearCache();s.read("1:sheet");
    }
}
