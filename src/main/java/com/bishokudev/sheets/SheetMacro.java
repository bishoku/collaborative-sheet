package com.bishokudev.sheets;

import com.atlassian.confluence.macro.*;
import com.atlassian.confluence.content.render.xhtml.ConversionContext;
import java.util.Map;

public class SheetMacro implements Macro {
    public String execute(Map<String,String> parameters,String body,ConversionContext context) throws MacroExecutionException {
        String key=parameters.getOrDefault("sheet","sheet-1");
        if(!key.matches("[a-zA-Z0-9_-]{1,64}")) return "<p>Sheet key must contain only letters, numbers, hyphens and underscores.</p>";
        long page=context.getEntity()==null?0:context.getEntity().getId();
        if(page<=0) return "<p>Publish the page before creating a sheet.</p>";
        return "<div class=\"cs-sheet\" data-page=\""+page+"\" data-sheet=\""+key+"\"><p>Loading sheet…</p></div>";
    }
    public BodyType getBodyType(){return BodyType.NONE;}
    public OutputType getOutputType(){return OutputType.BLOCK;}
}
