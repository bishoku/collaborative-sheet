package com.bishokudev.sheets;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class SheetFormulasTest {
 JSONObject doc(String formula){JSONObject d=SheetEngine.empty();put(d,0,"10",null);put(d,1,"20",null);put(d,2,"stale",formula);return d;}
 void put(JSONObject d,int c,String value,String f){JSONObject v=new JSONObject().put("value",value).put("type",f==null?"number":"text");if(f!=null)v.put("formula",f);d.getJSONObject("cells").put(d.getJSONArray("rows").getString(0)+":"+d.getJSONArray("columns").getString(c),v);}
 String result(JSONObject d){SheetFormulas.recalculate(d);return d.getJSONObject("cells").getJSONObject(d.getJSONArray("rows").getString(0)+":"+d.getJSONArray("columns").getString(2)).getString("value");}
 @Test public void composedFunctionsAndAbsoluteRefs(){assertEquals("30",result(doc("=SUM($A$1)+SUM(B1)")));assertEquals("20",result(doc("=IF(A1>0,B1,1/0)")));}
 @Test public void errorsAndCycles(){assertEquals("#DIV/0!",result(doc("=1/0")));assertEquals("#CYCLE!",result(doc("=C1")));assertEquals("#REF!",result(doc("=#REF!")));}
 @Test public void rangeDeletionShrinksRatherThanBreakingSyntax(){assertEquals("=SUM(A1:A2)",SheetFormulas.transform("=SUM(A1:A3)",true,0,-1));assertEquals("=SUM(#REF!)",SheetFormulas.transform("=SUM(A1:A1)",true,0,-1));assertEquals("=SUM(A1:A2)",SheetFormulas.transform("=SUM(A1:A3)",true,2,-1));}
 @Test public void stringsAndAbsoluteRefsSurviveStructure(){assertEquals("=IF($A$2>0,\"A1\",B2)",SheetFormulas.transform("=IF($A$1>0,\"A1\",B1)",true,0,1));}
 @Test(expected=IllegalArgumentException.class) public void excessiveRangeRejected(){result(doc("=SUM(A1:GR9999)"));}
 @Test(expected=IllegalArgumentException.class) public void unsupportedFunctionRejected(){result(doc("=INDIRECT(\"A1\")"));}
 @Test public void recalculationDoesNotMutateOriginalCells(){JSONObject d=doc("=A1+B1"),old=d.getJSONObject("cells").getJSONObject(d.getJSONArray("rows").getString(0)+":"+d.getJSONArray("columns").getString(2));assertEquals("30",result(d));assertEquals("stale",old.getString("value"));}
}
