package com.bishokudev.sheets;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.beehive.ClusterLockService;
import com.atlassian.confluence.content.render.xhtml.ConversionContext;
import com.atlassian.confluence.macro.*;
import org.json.*;
import java.util.*;

public class SheetMacro implements Macro {
    private final SheetStore store;
    @javax.inject.Inject public SheetMacro(ActiveObjects ao, ClusterLockService locks) { this.store = new SheetStore(ao, locks); }

    public String execute(Map<String,String> parameters,String body,ConversionContext context) throws MacroExecutionException {
        String key=parameters.getOrDefault("sheet","sheet-1");
        if(!key.matches("[a-zA-Z0-9_-]{1,64}")) return "<p>Sheet key must contain only letters, numbers, hyphens and underscores.</p>";
        long page=context==null||context.getEntity()==null?0:context.getEntity().getId();
        if(page<=0) return "<p>Publish the page before creating a sheet.</p>";
        String fallback="<p>Loading sheet…</p>";
        if(store!=null){
            try{
                JSONObject doc=store.read(page+":"+key);
                if(doc!=null) fallback=renderStaticHtml(key,doc);
            }catch(Exception e){throw new MacroExecutionException("Unable to load sheet data; retry the export after resolving the storage error.",e);}
        }
        return "<div class=\"cs-sheet\" data-page=\""+page+"\" data-sheet=\""+key+"\">"+fallback+"</div>";
    }

    static String renderStaticHtml(String sheetName,JSONObject doc) {
        JSONArray rows=doc.optJSONArray("rows"),cols=doc.optJSONArray("columns");
        JSONObject cells=doc.optJSONObject("cells");
        if(rows==null||cols==null||cells==null) return "<p>Loading sheet…</p>";
        int maxRow=-1,maxCol=-1;
        for(int r=0;r<rows.length();r++){
            String rid=rows.getString(r);
            for(int c=0;c<cols.length();c++){
                JSONObject cell=cells.optJSONObject(rid+":"+cols.getString(c));
                if(cell!=null&&!cell.optString("value","").isEmpty()){maxRow=Math.max(maxRow,r);maxCol=Math.max(maxCol,c);}
            }
        }
        if(maxRow<0||maxCol<0) return "<p class=\"cs-static-empty\">Empty sheet ("+escape(sheetName)+")</p>";
        int rowLimit=maxRow+1,colLimit=maxCol+1;
        StringBuilder sb=new StringBuilder("<table class=\"confluenceTable cs-static-table\"><tbody>");
        for(int r=0;r<rowLimit;r++){
            sb.append("<tr>");
            String rid=rows.getString(r);
            for(int c=0;c<colLimit;c++){
                JSONObject cell=cells.optJSONObject(rid+":"+cols.getString(c));
                String val=cell==null?"":cell.optString("value","");
                boolean bold=cell!=null&&cell.optBoolean("bold",false);
                sb.append("<td class=\"confluenceTd\">");
                if(bold) sb.append("<strong>").append(escape(val)).append("</strong>");
                else sb.append(escape(val));
                sb.append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    private static String escape(String s) {
        if(s==null||s.isEmpty()) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }

    public BodyType getBodyType(){return BodyType.NONE;}
    public OutputType getOutputType(){return OutputType.BLOCK;}
}
