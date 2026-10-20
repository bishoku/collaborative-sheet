package com.bishokudev.sheets;
import org.json.*;
import org.junit.*;
import java.util.UUID;
import static org.junit.Assert.*;
public class SheetHistoryTest {
    SheetStoreTest.Harness db;SheetStore store;JSONObject initial;
    @Before public void setup(){SheetStore.clearCache();db=new SheetStoreTest.Harness();store=db.store();initial=write("alice",new JSONObject().put("type","create"));}
    JSONObject write(String actor,JSONObject op){return store.write("1:history",actor,UUID.randomUUID().toString(),op,()->{});}
    String key(int row){return initial.getJSONArray("rows").getString(row)+":"+initial.getJSONArray("columns").getString(0);}
    JSONObject cell(int row){return store.read("1:history").getJSONObject("cells").optJSONObject(key(row));}
    JSONObject edit(int row,String value){JSONObject c=cell(row);return new JSONObject().put("row",initial.getJSONArray("rows").getString(row)).put("column",initial.getJSONArray("columns").getString(0)).put("expectedVersion",c==null?0:c.getInt("version")).put("cell",new JSONObject().put("value",value).put("type","text"));}
    void patch(String actor,int row,String value){write(actor,new JSONObject().put("type","patch").put("edits",new JSONArray().put(edit(row,value))));}
    JSONObject command(String actor,String type){return new JSONObject().put("type",type).put("expectedHead",store.history("1:history",actor).getInt(type+"Head"));}
    void undo(String a){write(a,command(a,"undo"));}void redo(String a){write(a,command(a,"redo"));}
    void conflict(Runnable r){try{r.run();fail("Expected conflict");}catch(SheetEngine.Conflict expected){}}
    @Test public void anotherUsersIndependentCellsSurviveUndoRedo(){patch("alice",0,"A");patch("bob",1,"B");undo("alice");assertEquals("",cell(0).getString("value"));assertEquals("B",cell(1).getString("value"));redo("alice");assertEquals("A",cell(0).getString("value"));assertEquals("B",cell(1).getString("value"));}
    @Test public void anotherUsersSameCellIsNeverOverwritten(){patch("alice",0,"A");patch("bob",0,"B");String before=store.read("1:history").toString();conflict(()->undo("alice"));assertEquals(before,store.read("1:history").toString());}
    @Test public void multipleUndoRedoSameCellAndReloadKeepMonotonicVersions(){patch("alice",0,"A");patch("alice",0,"B");undo("alice");assertEquals("A",cell(0).getString("value"));SheetStore.clearCache();store=db.store();undo("alice");assertEquals("",cell(0).getString("value"));redo("alice");redo("alice");assertEquals("B",cell(0).getString("value"));assertEquals(6,cell(0).getInt("version"));}
    @Test public void staleEditorCannotWriteAfterUndoRestoresSameValue(){patch("alice",0,"A");JSONObject stale=edit(0,"stale");patch("alice",0,"B");undo("alice");conflict(()->write("bob",new JSONObject().put("type","patch").put("edits",new JSONArray().put(stale))));}
    @Test public void batchConflictDoesNotPartiallyUndo(){write("alice",new JSONObject().put("type","patch").put("edits",new JSONArray().put(edit(0,"A")).put(edit(1,"A"))));patch("bob",1,"B");conflict(()->undo("alice"));assertEquals("A",cell(0).getString("value"));assertEquals("B",cell(1).getString("value"));}
    @Test public void otherUsersChangesAfterUndoBlockRedo(){patch("alice",0,"A");undo("alice");patch("bob",0,"B");conflict(()->redo("alice"));assertEquals("B",cell(0).getString("value"));}
    @Test public void anotherUsersHeadCannotBeSubmitted(){patch("alice",0,"A");JSONObject op=command("alice","undo");conflict(()->write("bob",op));assertEquals("A",cell(0).getString("value"));}
    @Test public void retryAfterLostUndoResponseDoesNotUndoAgain(){patch("alice",0,"A");patch("alice",1,"B");JSONObject op=command("alice","undo");String id=UUID.randomUUID().toString();JSONObject result=store.write("1:history","alice",id,op,()->{});JSONObject again=store.write("1:history","alice",id,op,()->{});assertEquals(result.toString(),again.toString());assertEquals("A",cell(0).getString("value"));assertEquals(4,db.committed.size());}
    @Test public void newEditClearsOnlyOwnRedo(){patch("alice",0,"A");undo("alice");patch("bob",1,"B");assertTrue(store.history("1:history","alice").getInt("redoHead")>0);patch("alice",2,"C");assertEquals(0,store.history("1:history","alice").getInt("redoHead"));assertTrue(store.history("1:history","bob").getInt("undoHead")>0);}
    JSONObject resize(int size){return new JSONObject().put("type","resize").put("axis","column").put("id",initial.getJSONArray("columns").getString(0)).put("size",size).put("expectedRevision",store.read("1:history").getInt("revision"));}
    @Test public void resizeUndoPreservesOtherCellsAndRejectsOtherResize(){write("alice",resize(200));patch("bob",0,"B");undo("alice");assertEquals("B",cell(0).getString("value"));redo("alice");write("bob",resize(200));conflict(()->undo("alice"));}
    @Test public void failedUndoCommitLeavesDataAndHistoryUnchanged(){patch("alice",0,"A");JSONObject before=store.history("1:history","alice");db.failCommit=true;try{undo("alice");fail();}catch(IllegalStateException expected){}db.failCommit=false;assertEquals(before.toString(),store.history("1:history","alice").toString());assertEquals("A",cell(0).getString("value"));undo("alice");assertEquals("",cell(0).getString("value"));}
    @Test public void undoRequiresPermissionAndHistorySurvivesCheckpoints(){for(int i=0;i<35;i++)patch("alice",0,"v"+i);try{store.write("1:history","alice",UUID.randomUUID().toString(),command("alice","undo"),()->{throw new SecurityException();});fail();}catch(SecurityException expected){}SheetStore.clearCache();undo("alice");assertEquals("v33",cell(0).getString("value"));redo("alice");assertEquals("v34",cell(0).getString("value"));}
}
