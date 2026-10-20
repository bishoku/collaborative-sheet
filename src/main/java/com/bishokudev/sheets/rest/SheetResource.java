package com.bishokudev.sheets.rest;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.beehive.ClusterLockService;
import com.atlassian.confluence.pages.*;
import com.atlassian.confluence.security.*;
import com.atlassian.confluence.user.*;
import com.atlassian.sal.api.component.ComponentLocator;
import com.bishokudev.sheets.*;
import org.json.*;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.*;

@Path("/sheets")
@Produces(MediaType.APPLICATION_JSON)
public class SheetResource {
    private final SheetStore store;
    @Inject public SheetResource(ActiveObjects ao,ClusterLockService locks) {store=new SheetStore(ao,locks);}
    private static void permission(long pageId,boolean edit) {
        PageManager pages=ComponentLocator.getComponent(PageManager.class);
        PermissionManager permissions=ComponentLocator.getComponent(PermissionManager.class);
        Page p=pages.getPage(pageId);
        ConfluenceUser user=AuthenticatedUserThreadLocal.get();
        if(p==null) throw new WebApplicationException(404);
        if(!permissions.hasPermission(user,Permission.VIEW,p)|| (edit&&(user==null||!permissions.hasPermission(user,Permission.EDIT,p))))
            throw new WebApplicationException(403);
    }
    private static String key(long page,String sheet) {
        if(page<=0||!sheet.matches("[a-zA-Z0-9_-]{1,64}")) throw new WebApplicationException(400);
        return page+":"+sheet;
    }
    private static Response json(JSONObject d){return Response.ok(d.toString()).header("Cache-Control","no-store").build();}
    @GET @Path("/{page}/{sheet}")
    public Response read(@PathParam("page") long page,@PathParam("sheet") String sheet,@QueryParam("since") Integer since) {
        permission(page,false);
        JSONObject result=store.changes(key(page,sheet),since==null?-1:since);
        boolean edit=true;try{permission(page,true);}catch(WebApplicationException e){edit=false;}
        result.put("history",edit?store.history(key(page,sheet),AuthenticatedUserThreadLocal.get().getKey().getStringValue()):new JSONObject().put("undoHead",0).put("redoHead",0));
        return json(result.put("canEdit",edit));
    }
    @POST @Path("/{page}/{sheet}") @Consumes(MediaType.APPLICATION_JSON)
    public Response write(@PathParam("page") long page,@PathParam("sheet") String sheet,
                          @HeaderParam("X-Sheet-Request") String request,@QueryParam("compact") @DefaultValue("false") boolean compact,String body) {
        permission(page,true);
        if(request==null) throw new WebApplicationException(400);
        if(body==null||body.length()>16_000_000) throw new WebApplicationException(413);
        try {
            JSONObject d=store.write(key(page,sheet),AuthenticatedUserThreadLocal.get().getKey().getStringValue(),request,
                new JSONObject(body),()->permission(page,true));
            return json(compact?new JSONObject().put("savedRevision",d.getInt("revision")):new JSONObject().put("document",d));
        }catch(SheetEngine.Conflict e){return Response.status(409).entity(new JSONObject().put("error",e.getMessage()).toString()).build();}
        catch(IllegalArgumentException|JSONException e){return Response.status(400).entity(new JSONObject().put("error",e.getMessage()).toString()).build();}
    }
    @POST @Path("/{page}/{sheet}/preview") @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response preview(@PathParam("page") long page,@PathParam("sheet") String sheet,@QueryParam("format") String format,byte[] bytes) {
        permission(page,true);key(page,sheet);
        if(bytes.length>10_000_000) throw new WebApplicationException(413);
        try{return json(WorkbookIO.read(bytes,format));}
        catch(Exception e){return Response.status(400).entity(new JSONObject().put("error","Import rejected: "+e.getMessage()).toString()).build();}
    }
    @GET @Path("/{page}/{sheet}/export")
    public Response export(@PathParam("page") long page,@PathParam("sheet") String sheet,@QueryParam("format") String format) {
        permission(page,false);JSONObject d=store.read(key(page,sheet));if(d==null) throw new WebApplicationException(404);
        try {
            boolean csv="csv".equals(format);
            return Response.ok(WorkbookIO.write(d,csv),csv?"text/csv; charset=utf-8":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header("Content-Disposition","attachment; filename=\""+sheet+(csv?".csv":".xlsx")+"\"").header("Cache-Control","no-store").build();
        }catch(Exception e){throw new WebApplicationException(e,500);}
    }
}
