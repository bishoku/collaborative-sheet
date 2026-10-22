package com.bishokudev.sheets;
import org.json.*;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;
public class ReviewRegressionTest {
 SheetStoreTest.Harness h; SheetStore s; JSONObject d;
 @Before public void setup(){SheetStore.clearCache();h=new SheetStoreTest.Harness();s=h.store();d=s.write("1:s","a",UUID.randomUUID().toString(),new JSONObject().put("type","create"),()->{});}
 JSONObject patch(int row,int col,JSONObject value){String r=d.getJSONArray("rows").getString(row),c=d.getJSONArray("columns").getString(col);JSONObject old=d.getJSONObject("cells").optJSONObject(r+":"+c);return new JSONObject().put("type","patch").put("edits",new JSONArray().put(new JSONObject().put("row",r).put("column",c).put("expectedVersion",old==null?0:old.getInt("version")).put("cell",value)));}
 JSONObject value(String v){return new JSONObject().put("value",v).put("type","number");}
 void write(JSONObject op){d=s.write("1:s","a",UUID.randomUUID().toString(),op,()->{});}
 @Test public void retainedReceiptShouldStillReplayAfterAutoPrune(){String request=null;JSONObject operation=null;for(int rev=2;rev<=288;rev++){JSONObject op=patch(0,0,value(""+rev));String id=UUID.randomUUID().toString();d=s.write("1:s","a",id,op,()->{});if(rev==33){request=id;operation=op;}}SheetStore.clearCache();assertEquals(288,s.read("1:s").getInt("revision"));assertEquals(33,s.write("1:s","a",request,operation,()->{}).getInt("revision"));}
 @Test public void insertingAboveFormulaShouldPreserveItsReferencedValue(){write(patch(0,0,value("10")));write(patch(0,1,value("20").put("formula","=A1*2")));write(new JSONObject().put("type","insertRow").put("index",0).put("expectedRevision",d.getInt("revision")));String k=d.getJSONArray("rows").getString(1)+":"+d.getJSONArray("columns").getString(1);assertEquals("=A2*2",d.getJSONObject("cells").getJSONObject(k).getString("formula"));}
 @Test public void csvFormulaResultShouldReflectEditedPrecedent() throws Exception {write(patch(0,0,value("10")));write(patch(0,1,value("20").put("formula","=A1*2")));write(patch(0,0,value("15")));String csv=new String(WorkbookIO.write(d,true),java.nio.charset.StandardCharsets.UTF_8);assertEquals("Actual CSV: "+csv,"30",WorkbookIO.parseCsv(csv.replace("\uFEFF", "")).get(0).get(1));}
 @Test public void staticExportShouldIncludeLastPopulatedRow(){for(int i=0;i<75;i++)write(new JSONObject().put("type","addRow").put("expectedRevision",d.getInt("revision")));write(patch(104,0,new JSONObject().put("value","last-row-marker").put("type","text")));assertTrue(SheetMacro.renderStaticHtml("s",d).contains("last-row-marker"));}

 @Test public void derivedResultsSurviveColdJournalReplay(){write(patch(0,0,value("10")));write(patch(0,1,value("0").put("formula","=A1*2")));write(patch(0,0,value("15")));SheetStore.clearCache();JSONObject restored=s.read("1:s");String k=d.getJSONArray("rows").getString(0)+":"+d.getJSONArray("columns").getString(1);assertEquals("30",restored.getJSONObject("cells").getJSONObject(k).getString("value"));}
}
