package com.bishokudev.sheets;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.beehive.ClusterLockService;
import com.atlassian.confluence.pages.Page;
import com.atlassian.confluence.content.render.xhtml.DefaultConversionContext;
import com.atlassian.confluence.xhtml.api.XhtmlContent;
import com.atlassian.confluence.plugins.index.api.*;
import org.json.JSONObject;
import java.util.*;

/** Index only sheets actually embedded in the page; Confluence applies page search permissions. */
public final class SheetSearchExtractor implements Extractor2 {
    private final SheetStore store;
    private final XhtmlContent xhtml;
    @javax.inject.Inject public SheetSearchExtractor(ActiveObjects ao,ClusterLockService locks,XhtmlContent xhtml){
        store=new SheetStore(ao,locks);this.xhtml=xhtml;
    }
    @Override public StringBuilder extractText(Object searchable){
        StringBuilder text=new StringBuilder();
        if(!(searchable instanceof Page))return text;
        Page page=(Page)searchable;
        if(!page.isCurrent())return text;
        Set<String> keys=new HashSet<>();
        try {
            xhtml.handleMacroDefinitions(page.getBodyAsString(),new DefaultConversionContext(page.toPageContext()),macro->{
                if("collaborative-sheet".equals(macro.getName())){
                    String key=macro.getParameters().getOrDefault("sheet","sheet-1");
                    if(key.matches("[a-zA-Z0-9_-]{1,64}"))keys.add(key);
                }
            });
            for(String key:keys){
                JSONObject doc=store.read(page.getId()+":"+key);
                if(doc==null)continue;
                JSONObject cells=doc.getJSONObject("cells");
                for(String id:cells.keySet())text.append(' ').append(cells.getJSONObject(id).optString("value"));
            }
        }catch(Exception e){throw new IllegalStateException("Cannot index sheets for page "+page.getId(),e);}
        return text;
    }
    @Override public Collection<FieldDescriptor> extractFields(Object searchable){return Collections.emptyList();}
}
