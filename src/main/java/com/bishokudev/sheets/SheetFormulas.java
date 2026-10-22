package com.bishokudev.sheets;

import org.json.*;
import java.util.*;
import java.util.regex.*;
import java.math.BigDecimal;

/** Bounded, server-authoritative formula subset. Never executes uploaded code. */
final class SheetFormulas {
    static final int MAX_RANGE=10000, MAX_DEPTH=64, MAX_WORK=1000000, MAX_FORMULAS=2000;
    private static final Pattern TOKEN=Pattern.compile("\\s*(\"(?:[^\"]|\"\")*\"|#[A-Z0-9/]+[!?]|\\$?[A-Za-z]+\\$?[1-9][0-9]*|[A-Za-z]+|(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?|>=|<=|<>|[+*/^%=<>()~,:-])");
    private static final Pattern REF=Pattern.compile("(\\$?)([A-Za-z]+)(\\$?)([1-9][0-9]*)");
    interface Node {Object eval(Context c,int depth);}
    static final class ErrorValue {final String text;ErrorValue(String text){this.text=text;}}
    static final class EvalError extends RuntimeException {final String value;EvalError(String value){this.value=value;}}
    static void check(Object v){if(v instanceof ErrorValue)throw new EvalError(((ErrorValue)v).text);}
    static double number(Object v){check(v);if(v==null||v.equals(""))return 0;if(v instanceof Number)return ((Number)v).doubleValue();if(v instanceof Boolean)return (Boolean)v?1:0;try{return Double.parseDouble(v.toString());}catch(Exception e){throw new EvalError("#VALUE!");}}
    static boolean truth(Object v){check(v);return v instanceof Boolean?(Boolean)v:number(v)!=0;}
    static String text(Object v){if(v instanceof ErrorValue)return ((ErrorValue)v).text;if(v instanceof Number)return BigDecimal.valueOf(((Number)v).doubleValue()).stripTrailingZeros().toPlainString();return v==null?"":v.toString();}
    static int col(String s){long n=0;for(char ch:s.toUpperCase(Locale.ROOT).toCharArray()){n=n*26+ch-'A'+1;if(n>SheetEngine.MAX_COLS)return SheetEngine.MAX_COLS;}return (int)n-1;}
    static int row(String s){try{long n=Long.parseLong(s);return n>SheetEngine.MAX_ROWS?SheetEngine.MAX_ROWS:(int)n-1;}catch(NumberFormatException e){return SheetEngine.MAX_ROWS;}}
    static final class Reference implements Node {
        final int r,c;Reference(String t){Matcher m=REF.matcher(t);if(!m.matches())throw new IllegalArgumentException("Invalid reference");r=row(m.group(4));c=col(m.group(2));}
        public Object eval(Context ctx,int depth){return ctx.cell(r,c,depth+1);}
    }
    static final class Parser {
        final List<String> ts=new ArrayList<>();int at,depth;
        Parser(String formula){if(!formula.startsWith("=")||formula.length()>4096)throw new IllegalArgumentException("Invalid formula");String s=formula.substring(1).trim();Matcher m=TOKEN.matcher(s);int p=0;while(p<s.length()){m.region(p,s.length());if(!m.lookingAt())throw new IllegalArgumentException("Unsupported formula syntax near: "+s.substring(p,Math.min(p+20,s.length())));ts.add(m.group(1));p=m.end();if(ts.size()>512)throw new IllegalArgumentException("Formula token limit exceeded");}}
        boolean eat(String s){if(at<ts.size()&&ts.get(at).equals(s)){at++;return true;}return false;}
        void need(String s){if(!eat(s))throw new IllegalArgumentException("Expected "+s+" in formula");}
        Node parse(){Node n=compare();if(at!=ts.size())throw new IllegalArgumentException("Unsupported formula expression");return n;}
        Node compare(){Node a=sum();while(at<ts.size()&&Arrays.asList("=","<>",">","<",">=","<=").contains(ts.get(at))){String op=ts.get(at++);Node left=a,right=sum();a=(c,d)->{c.step(d);Object l=left.eval(c,d+1),r=right.eval(c,d+1);check(l);check(r);int cmp=l instanceof Number&&r instanceof Number?Double.compare(number(l),number(r)):text(l).compareToIgnoreCase(text(r));switch(op){case "=":return cmp==0;case "<>":return cmp!=0;case ">":return cmp>0;case "<":return cmp<0;case ">=":return cmp>=0;default:return cmp<=0;}};}return a;}
        Node sum(){Node a=product();while(at<ts.size()&&(ts.get(at).equals("+")||ts.get(at).equals("-"))){String op=ts.get(at++);Node l=a,r=product();a=binary(l,r,op);}return a;}
        Node product(){Node a=unary();while(at<ts.size()&&(ts.get(at).equals("*")||ts.get(at).equals("/"))){String op=ts.get(at++);Node l=a,r=unary();a=binary(l,r,op);}return a;}
        Node binary(Node l,Node r,String op){return(c,d)->{c.step(d);double a=number(l.eval(c,d+1)),b=number(r.eval(c,d+1)),v;switch(op){case "+":v=a+b;break;case "-":v=a-b;break;case "*":v=a*b;break;default:if(b==0)throw new EvalError("#DIV/0!");v=a/b;}if(!Double.isFinite(v))throw new EvalError("#NUM!");return v;};}
        Node unary(){if(++depth>MAX_DEPTH)throw new IllegalArgumentException("Formula nesting limit exceeded");try{if(eat("+"))return unary();if(eat("-")){Node n=unary();return(c,d)->{c.step(d);return -number(n.eval(c,d+1));};}return primary();}finally{depth--;}}
        Node primary(){
            if(eat("(")){Node n=compare();need(")");return n;}
            if(at>=ts.size())throw new IllegalArgumentException("Incomplete formula");String t=ts.get(at++);
            if(t.startsWith("\"")){String s=t.substring(1,t.length()-1).replace("\"\"","\"");return(c,d)->s;}
            if(t.startsWith("#"))return(c,d)->new ErrorValue(t);
            if(REF.matcher(t).matches()){
                Reference first=new Reference(t);
                if(!eat(":"))return first;
                if(at>=ts.size())throw new IllegalArgumentException("Incomplete range");Reference last=new Reference(ts.get(at++));
                long size=(long)(Math.abs(last.r-first.r)+1)*(Math.abs(last.c-first.c)+1);
                if(first.r>=SheetEngine.MAX_ROWS||last.r>=SheetEngine.MAX_ROWS||first.c>=SheetEngine.MAX_COLS||last.c>=SheetEngine.MAX_COLS||size>MAX_RANGE)throw new IllegalArgumentException("Formula range exceeds limits");
                return(c,d)->{List<Object> values=new ArrayList<>();for(int r=Math.min(first.r,last.r);r<=Math.max(first.r,last.r);r++)for(int col=Math.min(first.c,last.c);col<=Math.max(first.c,last.c);col++)values.add(c.cell(r,col,d+1));return values;};
            }
            if(t.equalsIgnoreCase("TRUE")||t.equalsIgnoreCase("FALSE")){boolean v=Boolean.parseBoolean(t);return(c,d)->v;}
            if(t.matches("[A-Za-z]+")){
                String fn=t.toUpperCase(Locale.ROOT);if(!Arrays.asList("SUM","AVERAGE","COUNT","MIN","MAX","IF").contains(fn))throw new IllegalArgumentException("Unsupported function: "+fn);
                need("(");List<Node> args=new ArrayList<>();if(!eat(")")){do{args.add(compare());}while(eat(","));need(")");}
                if(args.isEmpty()||fn.equals("IF")&&(args.size()<2||args.size()>3))throw new IllegalArgumentException("Invalid function arguments");
                return(c,d)->{c.step(d);if(fn.equals("IF")){if(truth(args.get(0).eval(c,d+1)))return args.get(1).eval(c,d+1);return args.size()==3?args.get(2).eval(c,d+1):false;}
                    List<Double> nums=new ArrayList<>();for(Node n:args){Object v=n.eval(c,d+1);if(v instanceof List){for(Object item:(List<?>)v){check(item);if(item instanceof Number)nums.add(number(item));}}else{check(v);if(v instanceof Number)nums.add(number(v));else if(!(n instanceof Reference)&&v!=null)nums.add(number(v));}}
                    if(fn.equals("COUNT"))return (double)nums.size();if(nums.isEmpty()){if(fn.equals("AVERAGE"))throw new EvalError("#DIV/0!");return 0d;}double sum=0,min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;for(double x:nums){sum+=x;min=Math.min(min,x);max=Math.max(max,x);}double result=fn.equals("MIN")?min:fn.equals("MAX")?max:fn.equals("AVERAGE")?sum/nums.size():sum;if(!Double.isFinite(result))throw new EvalError("#NUM!");return result;};
            }
            try{double v=Double.parseDouble(t);if(!Double.isFinite(v))throw new NumberFormatException();return(c,d)->v;}catch(NumberFormatException e){throw new IllegalArgumentException("Unsupported formula token: "+t);}
        }
    }
    static final class Context {
        final JSONObject doc,cells;final JSONArray rows,cols;final Map<String,Node> nodes=new HashMap<>();final Map<String,Object> memo=new HashMap<>();final Set<String> visiting=new HashSet<>();int work;
        Context(JSONObject d){doc=d;cells=d.getJSONObject("cells");rows=d.getJSONArray("rows");cols=d.getJSONArray("columns");for(String id:cells.keySet()){String f=cells.getJSONObject(id).optString("formula");if(!f.isEmpty()){if(nodes.size()>=MAX_FORMULAS)throw new IllegalArgumentException("Maximum 2,000 formula cells");nodes.put(id,new Parser(f).parse());}}}
        void step(int depth){if(++work>MAX_WORK||depth>MAX_DEPTH)throw new IllegalArgumentException("Formula calculation budget exceeded");}
        Object cell(int r,int c,int depth){step(depth);if(r<0||c<0||r>=rows.length()||c>=cols.length())return new ErrorValue("#REF!");return value(rows.getString(r)+":"+cols.getString(c),depth);}
        Object value(String id,int depth){step(depth);if(memo.containsKey(id))return memo.get(id);JSONObject v=cells.optJSONObject(id);if(v==null)return null;Node n=nodes.get(id);if(n==null){String type=v.optString("type"),s=v.optString("value");return type.equals("number")?Double.valueOf(s):type.equals("boolean")?Boolean.valueOf(s):s;}
            if(!visiting.add(id))return new ErrorValue("#CYCLE!");Object out;try{out=n.eval(this,depth+1);if(out instanceof List)out=new ErrorValue("#VALUE!");}catch(EvalError e){out=new ErrorValue(e.value);}finally{visiting.remove(id);}memo.put(id,out);return out;
        }
    }
    static void recalculate(JSONObject d){Context ctx=new Context(d);for(String id:ctx.nodes.keySet()){Object v=ctx.value(id,0);JSONObject cell=new JSONObject(ctx.cells.getJSONObject(id).toString());cell.put("value",text(v)).put("type",v instanceof Number?"number":v instanceof Boolean?"boolean":"text");ctx.cells.put(id,cell);}}
    /** Adjust positional references outside quoted strings, including absolute refs for structural changes. */
    static void shift(JSONObject d,boolean rowAxis,int index,int delta){JSONObject cells=d.getJSONObject("cells");for(String id:new ArrayList<>(cells.keySet())){JSONObject old=cells.getJSONObject(id);String f=old.optString("formula");if(f.isEmpty())continue;String shifted=transform(f,rowAxis,index,delta);if(!f.equals(shifted)){JSONObject next=new JSONObject(old.toString()).put("formula",shifted).put("version",old.optInt("version")+1).put("changeId",SheetEngine.id());cells.put(id,next);}}}
    static String transform(String formula,boolean rowAxis,int index,int delta){
        Parser p=new Parser(formula);StringBuilder out=new StringBuilder("=");
        for(int i=0;i<p.ts.size();i++){
            String t=p.ts.get(i);Matcher m=REF.matcher(t);
            if(m.matches() && i+2<p.ts.size() && p.ts.get(i+1).equals(":") && REF.matcher(p.ts.get(i+2)).matches()){
                String end=p.ts.get(i+2);Matcher n=REF.matcher(end);n.matches();
                int first=rowAxis?row(m.group(4)):col(m.group(2)),last=rowAxis?row(n.group(4)):col(n.group(2));
                int low=Math.min(first,last),high=Math.max(first,last);
                if(delta<0 && low==index && high==index)t="#REF!";
                else {
                    int newLow=low,newHigh=high;
                    if(delta>0){if(low>=index)newLow++;if(high>=index)newHigh++;}
                    else {if(low>index)newLow--;if(high>=index)newHigh--;}
                    t=reference(m,rowAxis,first<=last?newLow:newHigh)+":"+reference(n,rowAxis,first<=last?newHigh:newLow);
                }
                i+=2;
            } else if(m.matches()){
                int v=rowAxis?row(m.group(4)):col(m.group(2));
                if(delta<0&&v==index)t="#REF!";else if(v>=index)t=reference(m,rowAxis,v+delta);
            }
            out.append(t);
        }
        return out.toString();
    }
    static String reference(Matcher m,boolean rowAxis,int value){
        int r=rowAxis?value:row(m.group(4)),c=rowAxis?col(m.group(2)):value;
        return m.group(1)+letters(c)+m.group(3)+(r+1);
    }
    static String letters(int c){String s="";for(c++;c>0;c=(c-1)/26)s=(char)('A'+(c-1)%26)+s;return s;}
}
