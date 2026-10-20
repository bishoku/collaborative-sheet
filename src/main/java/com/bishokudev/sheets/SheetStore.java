package com.bishokudev.sheets;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.beehive.ClusterLockService;
import com.bishokudev.sheets.ao.Revision;
import net.java.ao.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

public final class SheetStore {
    static final int CHECKPOINT_INTERVAL=32;
    // Cached documents are immutable. Every read still obtains its committed head from the DB.
    // Maximum four documents, never authoritative and never published before commit.
    private static final Map<String,JSONObject> CACHE=new LinkedHashMap<String,JSONObject>(8,.75f,true){
        protected boolean removeEldestEntry(Map.Entry<String,JSONObject> e){return size()>4;}
    };
    private final ActiveObjects ao;
    private final ClusterLockService locks;
    public SheetStore(ActiveObjects ao,ClusterLockService locks) { this.ao=ao; this.locks=locks; }
    static void clearCache(){synchronized(CACHE){CACHE.clear();}}
    private static void cache(String key,JSONObject d){synchronized(CACHE){JSONObject prior=CACHE.get(key);if(prior==null||prior.getInt("revision")<=d.getInt("revision")) CACHE.put(key,d);}}
    private Revision[] latest(String key){return ao.find(Revision.class,Query.select().where("SHEET_KEY = ?",key).order("SEQUENCE DESC").limit(1));}
    public JSONObject read(String key) {Revision[] r=latest(key);return r.length==0?null:at(key,r[0].getSequence());}
    private JSONObject at(String key,int target) {
        JSONObject base;
        synchronized(CACHE){base=CACHE.get(key);}
        if(base!=null&&base.getInt("revision")==target)return base;
        // Bound replay to a checkpoint window. Existing v0.1 rows are plain snapshots and remain readable.
        Revision[] history=ao.find(Revision.class,Query.select().where("SHEET_KEY = ? AND SEQUENCE <= ?",key,target).order("SEQUENCE DESC").limit(CHECKPOINT_INTERVAL));
        List<JSONObject> deltas=new ArrayList<>();JSONObject d=null;
        for(Revision row:history){
            if(base!=null&&row.getSequence()==base.getInt("revision")){d=base;break;}
            JSONObject payload=new JSONObject(row.getDocument());
            if(!payload.optBoolean("journalPatch",false)){d=payload;break;}
            deltas.add(payload);
        }
        if(d==null)throw new IllegalStateException("Revision chain has no checkpoint; refusing partial data");
        Collections.reverse(deltas);
        for(JSONObject delta:deltas)d=replay(d,delta);
        if(d.getInt("revision")!=target)throw new IllegalStateException("Revision chain incomplete");
        cache(key,d);return d;
    }
    private static JSONObject replay(JSONObject base,JSONObject delta){
        if(delta.getInt("revision")!=base.getInt("revision")+1)throw new IllegalStateException("Revision gap");
        JSONObject d=new JSONObject();for(String k:base.keySet())d.put(k,base.get(k));
        JSONObject cells=new JSONObject();for(String k:base.getJSONObject("cells").keySet())cells.put(k,base.getJSONObject("cells").get(k));
        JSONObject changed=delta.getJSONObject("cells");for(String k:changed.keySet())cells.put(k,changed.get(k));
        return d.put("cells",cells).put("revision",delta.getInt("revision"));
    }
    public JSONObject changes(String key,int since){
        Revision[] head=latest(key);if(head.length==0)return new JSONObject().put("document",JSONObject.NULL);
        int target=head[0].getSequence();
        if(since==target)return new JSONObject().put("revision",target).put("changes",new JSONArray());
        if(since<1||since>target||target-since>=CHECKPOINT_INTERVAL)return new JSONObject().put("document",at(key,target));
        Revision[] rows=ao.find(Revision.class,Query.select().where("SHEET_KEY = ? AND SEQUENCE > ? AND SEQUENCE <= ?",key,since,target).order("SEQUENCE ASC"));
        JSONArray deltas=new JSONArray();int expected=since+1;
        for(Revision row:rows){JSONObject p=new JSONObject(row.getDocument());if(row.getSequence()!=expected++||!p.optBoolean("journalPatch",false))return new JSONObject().put("document",at(key,target));deltas.put(p);}
        if(expected!=target+1)throw new IllegalStateException("Incomplete change feed");
        return new JSONObject().put("revision",target).put("changes",deltas);
    }
    private JSONObject historyState(String key,String actor) {
        Revision[] rows=ao.find(Revision.class,Query.select().where("SHEET_KEY = ? AND ACTOR = ?",key,actor).order("SEQUENCE DESC").limit(1));
        return rows.length==0||rows[0].getAction()==null?new JSONObject():new JSONObject(rows[0].getAction());
    }
    public JSONObject history(String key,String actor){JSONObject s=historyState(key,actor);return new JSONObject().put("undoHead",s.optInt("undoHead")).put("redoHead",s.optInt("redoHead"));}
    private JSONObject historyEntry(String key,String actor,int sequence){
        Revision[] rows=ao.find(Revision.class,Query.select().where("REVISION_KEY = ?",key+":"+sequence));
        if(rows.length!=1||!rows[0].getActor().equals(actor)||rows[0].getAction()==null)throw new SheetEngine.Conflict("History entry is unavailable for this user");
        return new JSONObject(rows[0].getAction());
    }
    public JSONObject write(String key,String actor,String requestId,JSONObject op,Runnable permissionCheck) {
        if(!requestId.matches("[a-zA-Z0-9-]{16,64}")) throw new IllegalArgumentException("Invalid request id");
        String requestKey=hash(key+":"+actor+":"+requestId), digest=hash(op.toString());
        Lock lock=locks.getLockForName("com.bishokudev.sheets:"+key);boolean acquired=false;
        try {
            acquired=lock.tryLock(15,TimeUnit.SECONDS);if(!acquired)throw new IllegalStateException("Sheet busy; retry the same request");
            permissionCheck.run();
            JSONObject committed=ao.executeInTransaction(() -> {
                Revision[] receipts=ao.find(Revision.class,Query.select().where("REQUEST_KEY = ?",requestKey));
                if(receipts.length>0){if(!receipts[0].getRequestHash().equals(digest))throw new SheetEngine.Conflict("Request id reused with different content");return at(key,receipts[0].getSequence());}
                JSONObject before=read(key),after,state=historyState(key,actor),action=new JSONObject(),entry=null;
                String type=op.getString("type");boolean reversing=type.equals("undo")||type.equals("redo"),undo=type.equals("undo");
                if(reversing){
                    int head=state.optInt(undo?"undoHead":"redoHead");
                    if(head==0||op.getInt("expectedHead")!=head)throw new SheetEngine.Conflict("Your undo/redo history changed. Reload and try again.");
                    JSONObject source=historyEntry(key,actor,head);entry=source.getJSONObject("entry");
                    if(before==null)throw new SheetEngine.Conflict("Sheet does not exist");
                    after=SheetHistory.apply(before,entry,undo);
                    action.put("kind",type).put("entry",entry);
                    if(undo)action.put("undoHead",source.optInt("undoNext")).put("redoHead",after.getInt("revision")).put("redoNext",state.optInt("redoHead"));
                    else action.put("undoHead",after.getInt("revision")).put("undoNext",state.optInt("undoHead")).put("redoHead",source.optInt("redoNext"));
                }else{
                    if(type.equals("create")){if(before!=null)throw new SheetEngine.Conflict("Sheet already exists");after=SheetEngine.empty().put("revision",1);}
                    else {if(before==null)throw new SheetEngine.Conflict("Sheet does not exist");after=SheetEngine.apply(before,op);entry=SheetHistory.capture(before,after,op);}
                    action.put("kind",type).put("redoHead",0).put("undoHead",entry==null?0:after.getInt("revision"));
                    if(entry!=null)action.put("entry",entry).put("undoNext",state.optInt("undoHead"));
                }
                JSONObject payload=after;
                if(entry!=null&&entry.getString("type").equals("patch")&&after.getInt("revision")%CHECKPOINT_INTERVAL!=0){
                    JSONObject cells=new JSONObject();for(Object value:entry.getJSONArray("cells")){String id=((JSONObject)value).getString("id");cells.put(id,after.getJSONObject("cells").get(id));}
                    payload=new JSONObject().put("journalPatch",true).put("revision",after.getInt("revision")).put("cells",cells);
                }
                ao.create(Revision.class,new DBParam("SHEET_KEY",key),new DBParam("REVISION_KEY",key+":"+after.getInt("revision")),new DBParam("REQUEST_KEY",requestKey),new DBParam("REQUEST_HASH",digest),new DBParam("SEQUENCE",after.getInt("revision")),new DBParam("DOCUMENT",payload.toString()),new DBParam("ACTION",action.toString()),new DBParam("ACTOR",actor),new DBParam("CREATED_AT",System.currentTimeMillis()));
                faultProbe("before-commit",requestId);
                return after;
            });
            faultProbe("after-commit",requestId);
            cache(key,committed);return committed;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted; retry the same request",e);}
        finally{if(acquired)lock.unlock();}
    }
    // The probe class is absent from production artifacts. Only the isolated fault-test JAR supplies it.
    private static void faultProbe(String phase,String request){
        if(System.getProperty("sheets.test.faultDirectory")==null)return;
        try{Class.forName("com.bishokudev.sheets.test.FaultProbe").getMethod("pause",String.class,String.class).invoke(null,phase,request);}
        catch(ClassNotFoundException ignored){/* Production JAR has no probe. */}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Test fault probe failed",e);}
    }
    private static String hash(String s){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte x:b)out.append(String.format("%02x",x));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
