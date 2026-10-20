package com.bishokudev.sheets;

import org.json.*;

/** Server-owned inverse operations. Logical change IDs rewind; optimistic cell versions never do. */
final class SheetHistory {
    static JSONObject capture(JSONObject before,JSONObject after,JSONObject op) {
        String type=op.getString("type");JSONObject entry=new JSONObject().put("type",type);
        if(type.equals("patch")) {
            JSONArray cells=new JSONArray();
            for(Object o:op.getJSONArray("edits")) {
                JSONObject edit=(JSONObject)o;String id=edit.getString("row")+":"+edit.getString("column");
                JSONObject prior=before.getJSONObject("cells").optJSONObject(id);
                cells.put(new JSONObject().put("id",id).put("before",prior==null?new JSONObject().put("value","").put("type","text"):prior).put("after",after.getJSONObject("cells").getJSONObject(id)));
            }
            return entry.put("cells",cells);
        }
        if(type.equals("resize")) {
            String field=op.getString("axis").equals("column")?"columnWidths":"rowHeights",id=op.getString("id");
            return entry.put("field",field).put("id",id).put("before",value(before,field,id)).put("after",value(after,field,id))
                .put("beforeToken",token(before,field,id)).put("afterToken",token(after,field,id));
        }
        return null; // Import/append/create are explicit history boundaries, never whole-sheet restores.
    }
    private static Object value(JSONObject d,String field,String id){JSONObject map=d.optJSONObject(field);return map==null?JSONObject.NULL:map.opt(id)==null?JSONObject.NULL:map.get(id);}
    private static String token(JSONObject d,String field,String id){JSONObject map=d.optJSONObject(field+"Changes");return map==null?"":map.optString(id,"");}
    static JSONObject apply(JSONObject current,JSONObject entry,boolean undo) {
        String expected=undo?"after":"before",desired=undo?"before":"after";
        if(entry.getString("type").equals("patch")) {
            JSONArray edits=new JSONArray();
            for(Object o:entry.getJSONArray("cells")) {
                JSONObject change=(JSONObject)o;String id=change.getString("id");String[] parts=id.split(":");
                JSONObject now=current.getJSONObject("cells").optJSONObject(id),want=change.getJSONObject(desired),was=change.getJSONObject(expected);
                if(!SheetEngine.contains(current.getJSONArray("rows"),parts[0])||!SheetEngine.contains(current.getJSONArray("columns"),parts[1])||now==null||!now.optString("changeId").equals(was.optString("changeId")))
                    throw new SheetEngine.Conflict("Cannot "+(undo?"undo":"redo")+": an affected cell changed. Other users' changes were preserved.");
                edits.put(new JSONObject().put("row",parts[0]).put("column",parts[1]).put("expectedVersion",now.getInt("version")).put("cell",want));
            }
            JSONObject result=SheetEngine.apply(current,new JSONObject().put("type","patch").put("edits",edits));
            for(Object o:entry.getJSONArray("cells")){JSONObject c=(JSONObject)o;JSONObject cell=result.getJSONObject("cells").getJSONObject(c.getString("id"));cell.remove("changeId");String id=c.getJSONObject(desired).optString("changeId");if(!id.isEmpty())cell.put("changeId",id);}
            return result;
        }
        String field=entry.getString("field"),id=entry.getString("id");
        if(!SheetEngine.contains(current.getJSONArray(field.equals("columnWidths")?"columns":"rows"),id)||!token(current,field,id).equals(entry.getString(expected+"Token")))throw new SheetEngine.Conflict("Cannot undo/redo: this dimension changed. Other users' changes were preserved.");
        JSONObject d=new JSONObject();for(String k:current.keySet())d.put(k,current.get(k));
        for(String key:new String[]{field,field+"Changes"})d.put(key,new JSONObject(current.optJSONObject(key)==null?"{}":current.getJSONObject(key).toString()));
        if(entry.isNull(desired))d.getJSONObject(field).remove(id);else d.getJSONObject(field).put(id,entry.get(desired));
        String stamp=entry.getString(desired+"Token");if(stamp.isEmpty())d.getJSONObject(field+"Changes").remove(id);else d.getJSONObject(field+"Changes").put(id,stamp);
        d.put("revision",current.getInt("revision")+1);SheetEngine.validate(d);return d;
    }
}
