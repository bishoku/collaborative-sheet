package com.bishokudev.sheets;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;

public class SheetEngineTest {
    private JSONObject patch(JSONObject d,int row,int col,int expected,String text){return new JSONObject().put("type","patch").put("edits",new JSONArray().put(new JSONObject().put("row",d.getJSONArray("rows").getString(row)).put("column",d.getJSONArray("columns").getString(col)).put("expectedVersion",expected).put("cell",new JSONObject().put("type","text").put("value",text))));}
    private String value(JSONObject d,int r,int c){return d.getJSONObject("cells").getJSONObject(d.getJSONArray("rows").getString(r)+":"+d.getJSONArray("columns").getString(c)).getString("value");}
    @Test public void independentConcurrentChangesMerge(){JSONObject d=SheetEngine.empty();JSONObject a=patch(d,0,0,0,"A"),b=patch(d,0,1,0,"B");d=SheetEngine.apply(SheetEngine.apply(d,a),b);assertEquals("A",value(d,0,0));assertEquals("B",value(d,0,1));}
    @Test(expected=SheetEngine.Conflict.class) public void sameCellConflict(){JSONObject d=SheetEngine.empty(),a=patch(d,0,0,0,"A"),b=patch(d,0,0,0,"B");SheetEngine.apply(SheetEngine.apply(d,a),b);}
    @Test public void failedBatchDoesNotMutateOriginal(){JSONObject d=SheetEngine.empty();d=SheetEngine.apply(d,patch(d,0,1,0,"existing"));String before=d.toString();JSONObject op=patch(d,0,0,0,"new");op.getJSONArray("edits").put(patch(d,0,1,0,"stale").getJSONArray("edits").getJSONObject(0));try{SheetEngine.apply(d,op);fail();}catch(SheetEngine.Conflict expected){}assertEquals(before,d.toString());}
    @Test(expected=SheetEngine.Conflict.class) public void staleStructuralChangeRejected(){JSONObject d=SheetEngine.empty();d=SheetEngine.apply(d,patch(d,0,0,0,"A"));SheetEngine.apply(d,new JSONObject().put("type","addRow").put("expectedRevision",0));}
    @Test(expected=SheetEngine.Conflict.class) public void importInvalidatesOldCellIdentities(){JSONObject d=SheetEngine.empty(),old=patch(d,0,0,0,"stale");d=SheetEngine.apply(d,new JSONObject().put("type","replace").put("expectedRevision",0).put("document",SheetEngine.empty()));SheetEngine.apply(d,old);}
    @Test(expected=IllegalArgumentException.class) public void overlappingMergesRejected(){JSONObject d=SheetEngine.empty();JSONObject m=new JSONObject().put("row",0).put("column",0).put("height",2).put("width",2);d.getJSONArray("merges").put(m).put(m);SheetEngine.validate(d);}
    @Test(expected=SheetEngine.Conflict.class) public void abaStillConflicts(){JSONObject d=SheetEngine.empty();d=SheetEngine.apply(d,patch(d,0,0,0,"A"));JSONObject stale=patch(d,0,0,1,"C");d=SheetEngine.apply(d,patch(d,0,0,1,"B"));d=SheetEngine.apply(d,patch(d,0,0,2,"A"));SheetEngine.apply(d,stale);}

    @Test public void dimensionsUseStableIdsAndDoNotMutatePriorRevision(){
        JSONObject d=SheetEngine.empty();String id=d.getJSONArray("columns").getString(0);
        JSONObject sized=SheetEngine.apply(d,new JSONObject().put("type","resize").put("axis","column").put("id",id).put("size",180).put("expectedRevision",0));
        assertFalse(d.has("columnWidths"));assertEquals(180,sized.getJSONObject("columnWidths").getInt(id));
        JSONObject replaced=SheetEngine.apply(sized,new JSONObject().put("type","replace").put("expectedRevision",1).put("document",sized));
        assertEquals(180,replaced.getJSONObject("columnWidths").getInt(replaced.getJSONArray("columns").getString(0)));assertFalse(replaced.getJSONObject("columnWidths").has(id));
    }
    @Test(expected=SheetEngine.Conflict.class) public void staleResizeCannotOverwriteOtherChanges(){JSONObject d=SheetEngine.empty();d=SheetEngine.apply(d,patch(d,0,0,0,"A"));SheetEngine.apply(d,new JSONObject().put("type","resize").put("axis","row").put("id",d.getJSONArray("rows").getString(0)).put("size",40).put("expectedRevision",0));}
    @Test(expected=IllegalArgumentException.class) public void invalidDimensionRejected(){JSONObject d=SheetEngine.empty();SheetEngine.apply(d,new JSONObject().put("type","resize").put("axis","row").put("id",d.getJSONArray("rows").getString(0)).put("size",100000).put("expectedRevision",0));}
    @Test(expected=IllegalArgumentException.class) public void invalidFontSizeRejected(){SheetEngine.validateCell(new JSONObject().put("fontSize",100000));}

    @Test public void insertAndDeleteRowAndColumnMaintainIdentitiesAndMerges(){
        JSONObject d=SheetEngine.empty();
        String r0=d.getJSONArray("rows").getString(0),c0=d.getJSONArray("columns").getString(0);
        d=SheetEngine.apply(d,patch(d,0,0,0,"TopLeft"));
        d.getJSONArray("merges").put(new JSONObject().put("row",0).put("column",0).put("height",2).put("width",2));
        // Insert a row at index 0 -> shifts merge down to row 1
        d=SheetEngine.apply(d,new JSONObject().put("type","insertRow").put("index",0).put("expectedRevision",d.getInt("revision")));
        assertEquals(31,d.getJSONArray("rows").length());
        assertEquals(r0,d.getJSONArray("rows").getString(1));
        assertEquals(1,d.getJSONArray("merges").getJSONObject(0).getInt("row"));
        // Delete inserted row at index 0 -> shifts merge back to row 0 and retains r0 cell
        String insertedRowId=d.getJSONArray("rows").getString(0);
        d=SheetEngine.apply(d,new JSONObject().put("type","deleteRow").put("id",insertedRowId).put("expectedRevision",d.getInt("revision")));
        assertEquals(30,d.getJSONArray("rows").length());
        assertEquals("TopLeft",d.getJSONObject("cells").getJSONObject(r0+":"+c0).getString("value"));
        assertEquals(0,d.getJSONArray("merges").getJSONObject(0).getInt("row"));
        // Insert and delete column
        d=SheetEngine.apply(d,new JSONObject().put("type","insertColumn").put("index",1).put("expectedRevision",d.getInt("revision")));
        assertEquals(9,d.getJSONArray("columns").length());
        assertEquals(3,d.getJSONArray("merges").getJSONObject(0).getInt("width"));
        d=SheetEngine.apply(d,new JSONObject().put("type","deleteColumn").put("index",1).put("expectedRevision",d.getInt("revision")));
        assertEquals(8,d.getJSONArray("columns").length());
        assertEquals(2,d.getJSONArray("merges").getJSONObject(0).getInt("width"));
    }

    @Test public void sanitizeCellClearsEmptyColorAndFormulaFields(){
        JSONObject d=SheetEngine.empty();
        JSONObject edit=new JSONObject().put("row",d.getJSONArray("rows").getString(0)).put("column",d.getJSONArray("columns").getString(0))
            .put("expectedVersion",0).put("cell",new JSONObject().put("type","number").put("value","1250.5").put("background","#ff0000").put("formula","=SUM(B1:B5)"));
        d=SheetEngine.apply(d,new JSONObject().put("type","patch").put("edits",new JSONArray().put(edit)));
        String key=d.getJSONArray("rows").getString(0)+":"+d.getJSONArray("columns").getString(0);
        assertEquals("#ff0000",d.getJSONObject("cells").getJSONObject(key).getString("background"));
        // Clear background and formula by passing empty string
        JSONObject clearEdit=new JSONObject().put("row",d.getJSONArray("rows").getString(0)).put("column",d.getJSONArray("columns").getString(0))
            .put("expectedVersion",1).put("cell",new JSONObject().put("type","number").put("value","1250.5").put("background","").put("formula",""));
        d=SheetEngine.apply(d,new JSONObject().put("type","patch").put("edits",new JSONArray().put(clearEdit)));
        assertFalse(d.getJSONObject("cells").getJSONObject(key).has("background"));
        assertFalse(d.getJSONObject("cells").getJSONObject(key).has("formula"));
    }
}
