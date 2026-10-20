package com.bishokudev.sheets;

import org.json.*;
import java.util.*;

/** Pure model operations. Coordinates use stable identities, never visible row positions. */
public final class SheetEngine {
    public static final int MAX_ROWS = 9999, MAX_COLS = 200, MAX_CELLS = 200000;
    public static final class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
    }
    public static String id() { return UUID.randomUUID().toString(); }
    public static JSONObject empty() {
        JSONObject d = new JSONObject().put("rows",new JSONArray()).put("columns",new JSONArray())
            .put("cells",new JSONObject()).put("merges",new JSONArray()).put("revision",0);
        for(int c=0;c<8;c++) d.getJSONArray("columns").put(id());
        for(int r=0;r<30;r++) d.getJSONArray("rows").put(id());
        return d;
    }
    public static JSONObject apply(JSONObject original, JSONObject operation) {
        JSONObject d = new JSONObject();
        for(String k:original.keySet()) d.put(k,original.get(k));
        JSONObject copiedCells=new JSONObject();
        for(String k:original.getJSONObject("cells").keySet()) copiedCells.put(k,original.getJSONObject("cells").get(k));
        d.put("cells",copiedCells);
        d.put("rows",new JSONArray(original.getJSONArray("rows").toList()));
        d.put("columns",new JSONArray(original.getJSONArray("columns").toList()));
        String type = operation.getString("type");
        if (type.equals("patch")) {
            JSONArray edits = operation.getJSONArray("edits");
            if(edits.length()==0 || edits.length()>10000) throw new IllegalArgumentException("Invalid batch size");
            Set<String> seen = new HashSet<>(), rowIds=ids(d.getJSONArray("rows")), colIds=ids(d.getJSONArray("columns"));
            for(int i=0;i<edits.length();i++) {
                JSONObject e=edits.getJSONObject(i);
                String row=e.getString("row"), col=e.getString("column"), key=row+":"+col;
                if(!rowIds.contains(row)||!colIds.contains(col))
                    throw new Conflict("The row or column was removed. Reload and review your changes.");
                if(!seen.add(key)) throw new IllegalArgumentException("Duplicate cell in batch");
                JSONObject current=d.getJSONObject("cells").optJSONObject(key);
                int version=current==null?0:current.optInt("version");
                if(e.getInt("expectedVersion")!=version) throw new Conflict("Cell changed by another user: "+key);
                JSONObject next=e.getJSONObject("cell");
                validateCell(next);
                d.getJSONObject("cells").put(key,new JSONObject(next.toString()).put("version",version+1).put("changeId",id()));
            }
        } else {
            if(operation.getInt("expectedRevision")!=d.getInt("revision"))
                throw new Conflict("The sheet changed. Reload before making this structural change.");
            if(type.equals("replace")) {
                d=new JSONObject(operation.getJSONObject("document").toString());
                // Fresh identities prevent stale edits from targeting a replacement workbook.
                rekey(d);
            } else if(type.equals("addRow")) d.getJSONArray("rows").put(id());
            else if(type.equals("addColumn")) d.getJSONArray("columns").put(id());
            else if(type.equals("resize")) {
                String axis=operation.getString("axis"), field=axis.equals("column")?"columnWidths":"rowHeights";
                if(!axis.equals("column")&&!axis.equals("row"))throw new IllegalArgumentException("Invalid resize axis");
                String identity=operation.getString("id");
                if(!contains(d.getJSONArray(axis.equals("column")?"columns":"rows"),identity))throw new Conflict("Dimension no longer exists");
                JSONObject sizes=new JSONObject(d.optJSONObject(field)==null?"{}":d.getJSONObject(field).toString());
                sizes.put(identity,operation.getInt("size"));d.put(field,sizes);
                String tokens=field+"Changes";JSONObject stamps=new JSONObject(d.optJSONObject(tokens)==null?"{}":d.getJSONObject(tokens).toString());stamps.put(identity,id());d.put(tokens,stamps);
            }
            else throw new IllegalArgumentException("Unknown operation");
        }
        d.put("revision",original.getInt("revision")+1);
        if(type.equals("patch")) {
            if(d.toString().length()>16_000_000) throw new IllegalArgumentException("Sheet exceeds 16 MB document limit");
        } else validate(d);
        return d;
    }
    static void rekey(JSONObject d) {
        JSONArray oldR=d.getJSONArray("rows"), oldC=d.getJSONArray("columns"), rows=new JSONArray(), cols=new JSONArray();
        JSONObject cells=new JSONObject(), old=d.getJSONObject("cells");
        for(int r=0;r<oldR.length();r++) rows.put(id());
        for(int c=0;c<oldC.length();c++) cols.put(id());
        for(int r=0;r<oldR.length();r++) for(int c=0;c<oldC.length();c++) {
            JSONObject cell=old.optJSONObject(oldR.getString(r)+":"+oldC.getString(c));
            if(cell!=null) cells.put(rows.getString(r)+":"+cols.getString(c),new JSONObject(cell.toString()).put("version",1).put("changeId",id()));
        }
        for(String field:Arrays.asList("columnWidths","rowHeights")) {
            JSONArray before=field.equals("columnWidths")?oldC:oldR,after=field.equals("columnWidths")?cols:rows;
            JSONObject sizes=d.optJSONObject(field),mapped=new JSONObject();
            if(sizes!=null){for(int i=0;i<before.length();i++)if(sizes.has(before.getString(i)))mapped.put(after.getString(i),sizes.get(before.getString(i)));d.put(field,mapped);}
        }
        d.remove("columnWidthsChanges");d.remove("rowHeightsChanges");
        d.put("rows",rows).put("columns",cols).put("cells",cells);
    }
    static boolean contains(JSONArray a,String id) { for(int i=0;i<a.length();i++) if(a.getString(i).equals(id)) return true; return false; }
    static void validateCell(JSONObject c) {
        if(c.optString("value","").length()>32767) throw new IllegalArgumentException("Cell text exceeds Excel limit");
        if(!Arrays.asList("text","number","boolean").contains(c.optString("type","text"))) throw new IllegalArgumentException("Invalid cell type");
        if(c.optString("type").equals("number")) {
            double n=Double.parseDouble(c.getString("value"));
            if(!Double.isFinite(n)) throw new IllegalArgumentException("Invalid number");
        }
        if(c.optString("type").equals("boolean")&&!Arrays.asList("true","false").contains(c.optString("value"))) throw new IllegalArgumentException("Invalid boolean");
        for(String color:Arrays.asList("background","foreground"))
            if(c.has(color)&&!c.getString(color).matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Invalid color");
        if(c.has("fontSize")&&(c.getDouble("fontSize")<8||c.getDouble("fontSize")>72||!Double.isFinite(c.getDouble("fontSize"))))throw new IllegalArgumentException("Font size must be 8–72 pt");
        for(String flag:Arrays.asList("bold","italic","underline","wrap"))if(c.has(flag)&&!(c.get(flag) instanceof Boolean))throw new IllegalArgumentException("Invalid style flag");
        if(c.has("align")&&!Arrays.asList("left","center","right").contains(c.getString("align")))throw new IllegalArgumentException("Invalid alignment");
        if(c.optString("format","").length()>256) throw new IllegalArgumentException("Invalid format");
    }
    public static void validate(JSONObject d) {
        if(d.has("journalPatch")) throw new IllegalArgumentException("Reserved document field");
        if(d.toString().length()>16_000_000) throw new IllegalArgumentException("Sheet exceeds 16 MB document limit");
        JSONArray rows=d.getJSONArray("rows"), cols=d.getJSONArray("columns");
        if(rows.length()<1||rows.length()>MAX_ROWS||cols.length()<1||cols.length()>MAX_COLS || (long)rows.length()*cols.length()>MAX_CELLS)
            throw new IllegalArgumentException("Limit: 9,999 rows, 200 columns and 200,000 cells");
        Set<String> rs=ids(rows), cs=ids(cols);
        for(String field:Arrays.asList("columnWidths","rowHeights")) {
            if(!d.has(field))continue;
            JSONObject sizes=d.getJSONObject(field);Set<String> valid=field.equals("columnWidths")?cs:rs;
            int min=field.equals("columnWidths")?40:20,max=field.equals("columnWidths")?800:400;
            for(String id:sizes.keySet())if(!valid.contains(id)||!(sizes.get(id) instanceof Number)||sizes.getDouble(id)!=sizes.getInt(id)||sizes.getInt(id)<min||sizes.getInt(id)>max)throw new IllegalArgumentException("Invalid dimension");
        }
        JSONObject cells=d.getJSONObject("cells");
        for(String key:cells.keySet()) {
            String[] parts=key.split(":",-1);
            if(parts.length!=2||!rs.contains(parts[0])||!cs.contains(parts[1])) throw new IllegalArgumentException("Invalid cell identity");
            validateCell(cells.getJSONObject(key));
        }
        Set<Long> covered=new HashSet<>();
        JSONArray merges=d.getJSONArray("merges");
        for(int i=0;i<merges.length();i++) {
            JSONObject m=merges.getJSONObject(i);
            int r=m.getInt("row"),c=m.getInt("column"),h=m.getInt("height"),w=m.getInt("width");
            if(r<0||c<0||h<1||w<1||h>rows.length()||w>cols.length()||r>rows.length()-h||c>cols.length()-w) throw new IllegalArgumentException("Invalid merge");
            for(int y=r;y<r+h;y++) for(int x=c;x<c+w;x++) if(!covered.add((long)y*MAX_COLS+x)) throw new IllegalArgumentException("Overlapping merges");
        }
    }
    private static Set<String> ids(JSONArray a) {
        Set<String> s=new HashSet<>();
        for(int i=0;i<a.length();i++) if(!a.getString(i).matches("[a-zA-Z0-9_-]{1,64}")||!s.add(a.getString(i))) throw new IllegalArgumentException("Invalid identity");
        return s;
    }
}
