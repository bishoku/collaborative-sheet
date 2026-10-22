package com.bishokudev.sheets;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class WorkbookIO {
    static {
        // Protect against decompression (ZIP bomb) attacks on untrusted XLSX uploads.
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(64L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(16L * 1024 * 1024);
    }
    // Stream every entry before POI builds an XML object graph. Limits apply to the
    // complete archive, not just individual entries or declared ZIP sizes.
    static void checkArchive(byte[] bytes) throws IOException {
        long total=0;int entries=0;int[] xmlCounts=new int[4];Set<String> names=new HashSet<>();byte[] buffer=new byte[8192];
        try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))){
            java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                if(++entries>512||!names.add(entry.getName()))throw new IllegalArgumentException("Too many or duplicate ZIP entries");
                long size=0;int n;ByteArrayOutputStream xml=new ByteArrayOutputStream();boolean isXml=entry.getName().endsWith(".xml")||entry.getName().endsWith(".rels");
                while((n=zip.read(buffer))!=-1){size+=n;total+=n;if(size>8L*1024*1024||total>32L*1024*1024)throw new IllegalArgumentException("Expanded workbook exceeds safe limits");if(isXml)xml.write(buffer,0,n);}
                if(isXml)checkXml(xml.toByteArray(),xmlCounts);
                if(size>1024*1024&&entry.getCompressedSize()>0&&size>entry.getCompressedSize()*100L)throw new IllegalArgumentException("Unsafe workbook compression ratio");
            }
        }
        if(entries==0)throw new IllegalArgumentException("Invalid XLSX archive");
    }
    private static void checkXml(byte[] xml,int[] counts) throws IOException {
        javax.xml.stream.XMLInputFactory factory=javax.xml.stream.XMLInputFactory.newDefaultFactory();
        factory.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
        try {
            javax.xml.stream.XMLStreamReader reader=factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            try { while(reader.hasNext()) {
                int event=reader.next();
                if(event==javax.xml.stream.XMLStreamConstants.DTD)throw new IllegalArgumentException("DTD is not allowed in workbooks");
                if(event!=javax.xml.stream.XMLStreamConstants.START_ELEMENT)continue;
                if(++counts[0]>1_000_000)throw new IllegalArgumentException("Workbook XML is too complex");
                String name=reader.getLocalName();
                if(name.equals("c")&&++counts[1]>SheetEngine.MAX_CELLS)throw new IllegalArgumentException("Too many workbook cells");
                if(name.equals("si")&&++counts[2]>SheetEngine.MAX_CELLS)throw new IllegalArgumentException("Too many shared strings");
                if(name.equals("xf")&&++counts[3]>10000)throw new IllegalArgumentException("Too many workbook styles");
            }} finally {reader.close();}
        } catch(javax.xml.stream.XMLStreamException e){throw new IOException("Invalid workbook XML",e);}
    }
    private static JSONObject document(int nr,int nc) {
        if(nr>SheetEngine.MAX_ROWS||nc>SheetEngine.MAX_COLS||(long)nr*nc>SheetEngine.MAX_CELLS) throw new IllegalArgumentException("Workbook exceeds sheet limits");
        JSONObject d=SheetEngine.empty();JSONArray rs=new JSONArray(),cs=new JSONArray();
        for(int r=0;r<Math.max(1,nr);r++)rs.put(SheetEngine.id());
        for(int c=0;c<Math.max(1,nc);c++)cs.put(SheetEngine.id());
        return d.put("rows",rs).put("columns",cs);
    }
    public static JSONObject read(byte[] bytes,String format) throws IOException {
        return read(bytes,format,false);
    }
    public static JSONObject read(byte[] bytes,String format,boolean allowFormulas) throws IOException {
        if(bytes==null||bytes.length>10_000_000)throw new IllegalArgumentException("Maximum upload size: 10 MB");
        if("xlsx".equals(format))checkArchive(bytes);
        JSONArray sheets=new JSONArray(),warnings=new JSONArray();
        if("csv".equals(format)) {
            List<List<String>> records=parseCsv(new String(bytes,StandardCharsets.UTF_8).replaceFirst("^\\uFEFF",""));
            int cols=records.stream().mapToInt(List::size).max().orElse(1);
            JSONObject d=document(records.size(),cols);
            for(int r=0;r<records.size();r++)for(int c=0;c<records.get(r).size();c++) put(d,r,c,new JSONObject().put("value",records.get(r).get(c)).put("type","text"));
            sheets.put(new JSONObject().put("name","CSV").put("document",d));
        } else if("xlsx".equals(format)) {
            try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                if(book.getNumberOfSheets()>20)throw new IllegalArgumentException("Maximum 20 worksheets per import");
                int total=0;
                for(Sheet sheet:book) {
                    int nr=sheet.getPhysicalNumberOfRows()==0?1:sheet.getLastRowNum()+1,nc=1;
                    for(Row row:sheet)nc=Math.max(nc,row.getLastCellNum());
                    for(CellRangeAddress m:sheet.getMergedRegions()){nr=Math.max(nr,m.getLastRow()+1);nc=Math.max(nc,m.getLastColumn()+1);}
                    total+=nr*nc;if(total>SheetEngine.MAX_CELLS)throw new IllegalArgumentException("Import exceeds total cell limit");
                    JSONObject d=document(nr,nc).put("date1904",book.isDate1904());
                    JSONObject widths=new JSONObject(),heights=new JSONObject();
                    for(int c=0;c<nc;c++)widths.put(d.getJSONArray("columns").getString(c),Math.max(40,Math.min(800,Math.round(sheet.getColumnWidthInPixels(c)))));
                    for(Row row:sheet)heights.put(d.getJSONArray("rows").getString(row.getRowNum()),Math.max(20,Math.min(400,Math.round(row.getHeightInPoints()*4/3))));
                    d.put("columnWidths",widths).put("rowHeights",heights);
                    for(Row row:sheet)for(Cell cell:row) {
                        CellType type=cell.getCellType();
                        JSONObject value=new JSONObject().put("type","text").put("value","");
                        if(type==CellType.FORMULA) {
                            if(!allowFormulas) throw new IllegalArgumentException("Formula at "+sheet.getSheetName()+"!"+cell.getAddress()+". Export a values-only workbook before importing.");
                            value.put("formula","="+cell.getCellFormula());
                            CellType cached=cell.getCachedFormulaResultType();
                            if(cached==CellType.NUMERIC&&Double.isFinite(cell.getNumericCellValue()))value.put("type","number").put("value",Double.toString(cell.getNumericCellValue()));
                            else if(cached==CellType.BOOLEAN)value.put("type","boolean").put("value",Boolean.toString(cell.getBooleanCellValue()));
                            else if(cached==CellType.STRING)value.put("value",cell.getStringCellValue());
                            else value.put("value","="+cell.getCellFormula());
                        } else if(type==CellType.ERROR)throw new IllegalArgumentException("Error cell at "+cell.getAddress());
                        else if(type==CellType.NUMERIC)value.put("type","number").put("value",Double.toString(cell.getNumericCellValue()));
                        else if(type==CellType.BOOLEAN)value.put("type","boolean").put("value",Boolean.toString(cell.getBooleanCellValue()));
                        else if(type==CellType.STRING)value.put("value",cell.getStringCellValue());
                        XSSFCellStyle style=(XSSFCellStyle)cell.getCellStyle();
                        value.put("format",style.getDataFormatString());
                        String bg=hex(style.getFillForegroundXSSFColor()),fg=hex(style.getFont().getXSSFColor());
                        if(bg!=null&&style.getFillPattern()==FillPatternType.SOLID_FOREGROUND)value.put("background",bg);
                        if(fg!=null)value.put("foreground",fg);
                        value.put("bold",style.getFont().getBold()).put("italic",style.getFont().getItalic())
                            .put("underline",style.getFont().getUnderline()!=Font.U_NONE).put("fontSize",Math.max(8,Math.min(72,style.getFont().getFontHeightInPoints())))
                            .put("wrap",style.getWrapText());
                        HorizontalAlignment alignment=style.getAlignment();
                        if(alignment==HorizontalAlignment.LEFT||alignment==HorizontalAlignment.CENTER||alignment==HorizontalAlignment.RIGHT)value.put("align",alignment.name().toLowerCase(Locale.ROOT));
                        put(d,row.getRowNum(),cell.getColumnIndex(),value);
                    }
                    for(CellRangeAddress m:sheet.getMergedRegions())d.getJSONArray("merges").put(new JSONObject().put("row",m.getFirstRow()).put("column",m.getFirstColumn()).put("height",m.getLastRow()-m.getFirstRow()+1).put("width",m.getLastColumn()-m.getFirstColumn()+1));
                    SheetFormulas.recalculate(d);
                    SheetEngine.validate(d);
                    sheets.put(new JSONObject().put("name",sheet.getSheetName()).put("document",d));
                }
                warnings.put("Values, number formats, explicit colors, basic text styles, wrapping, alignment, dimensions and merged ranges are imported. Dimensions are approximated in pixels (columns 40–800, rows 20–400); font sizes are limited to 8–72 pt. Charts, images, conditional formatting and other workbook features are not preserved.");
            }
        } else throw new IllegalArgumentException("Use .xlsx or UTF-8 .csv");
        return new JSONObject().put("sheets",sheets).put("warnings",warnings);
    }
    private static String hex(XSSFColor c) {
        if(c==null||c.getRGB()==null)return null;StringBuilder s=new StringBuilder("#");for(byte b:c.getRGB())s.append(String.format("%02x",b));return s.toString();
    }
    private static XSSFColor color(String s) {return new XSSFColor(new byte[]{(byte)Integer.parseInt(s.substring(1,3),16),(byte)Integer.parseInt(s.substring(3,5),16),(byte)Integer.parseInt(s.substring(5,7),16)},new DefaultIndexedColorMap());}
    private static void put(JSONObject d,int r,int c,JSONObject value) {d.getJSONObject("cells").put(d.getJSONArray("rows").getString(r)+":"+d.getJSONArray("columns").getString(c),value.put("version",1));}
    public static byte[] write(JSONObject d,boolean csv) throws IOException {
        d=new JSONObject(d.toString());
        SheetFormulas.recalculate(d);
        JSONArray rs=d.getJSONArray("rows"),cs=d.getJSONArray("columns");JSONObject cells=d.getJSONObject("cells");
        if(csv) {
            StringBuilder s=new StringBuilder("\uFEFF");
            for(int r=0;r<rs.length();r++) {
                for(int c=0;c<cs.length();c++) {
                    if(c>0)s.append(',');JSONObject cell=cells.optJSONObject(rs.getString(r)+":"+cs.getString(c));String v=cell==null?"":cell.optString("value","");
                    // CSV has no text type. Prevent spreadsheet applications from executing text as formulas.
                    if(!v.isEmpty()&&"=+-@\t\r\n".indexOf(v.charAt(0))>=0&&(cell==null||!cell.optString("type").equals("number")))v="'"+v;
                    s.append('"').append(v.replace("\"","\"\"")).append('"');
                }s.append("\r\n");
            }return s.toString().getBytes(StandardCharsets.UTF_8);
        }
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            if(!book.getCTWorkbook().isSetWorkbookPr())book.getCTWorkbook().addNewWorkbookPr();
            book.getCTWorkbook().getWorkbookPr().setDate1904(d.optBoolean("date1904",false));
            Sheet sheet=book.createSheet("Sheet");Map<String,XSSFCellStyle> styles=new HashMap<>();
            JSONObject widths=d.optJSONObject("columnWidths"),heights=d.optJSONObject("rowHeights");
            for(int c=0;c<cs.length();c++)sheet.setColumnWidth(c,(int)Math.round((widths==null?104:widths.optInt(cs.getString(c),104))*256.0/7.0017));
            for(int r=0;r<rs.length();r++) {
                Row row=sheet.createRow(r);row.setHeightInPoints((heights==null?24:heights.optInt(rs.getString(r),24))*3f/4);
                for(int c=0;c<cs.length();c++) {
                    JSONObject v=cells.optJSONObject(rs.getString(r)+":"+cs.getString(c));if(v==null)continue;
                    Cell cell=row.createCell(c);String text=v.optString("value",""),formula=v.optString("formula","");
                    if(formula.startsWith("=")&&formula.length()>1){
                        cell.setCellFormula(formula.substring(1));
                        if("number".equals(v.optString("type")))cell.setCellValue(Double.parseDouble(text));
                        else if("boolean".equals(v.optString("type")))cell.setCellValue(Boolean.parseBoolean(text));
                        else cell.setCellValue(text);
                    } else {
                        switch(v.optString("type","text")){case "number":cell.setCellValue(Double.parseDouble(text));break;case "boolean":cell.setCellValue(Boolean.parseBoolean(text));break;default:cell.setCellValue(text);}
                    }
                    String styleKey=v.optString("background")+"|"+v.optString("foreground")+"|"+v.optBoolean("bold")+"|"+v.optString("format")+"|"+v.optBoolean("italic")+"|"+v.optBoolean("underline")+"|"+v.optDouble("fontSize",11)+"|"+v.optBoolean("wrap")+"|"+v.optString("align");
                    XSSFCellStyle style=styles.get(styleKey);
                    if(style==null){
                        if(styles.size()>10000)throw new IllegalArgumentException("Too many distinct styles");
                        style=book.createCellStyle();XSSFFont font=book.createFont();font.setBold(v.optBoolean("bold"));font.setItalic(v.optBoolean("italic"));font.setUnderline(v.optBoolean("underline")?Font.U_SINGLE:Font.U_NONE);font.setFontHeight((short)Math.round(v.optDouble("fontSize",11)*20));
                        style.setWrapText(v.optBoolean("wrap",false));if(v.has("align"))style.setAlignment(HorizontalAlignment.valueOf(v.getString("align").toUpperCase(Locale.ROOT)));
                        if(v.has("foreground"))font.setColor(color(v.getString("foreground")));style.setFont(font);
                        if(v.has("background")){style.setFillForegroundColor(color(v.getString("background")));style.setFillPattern(FillPatternType.SOLID_FOREGROUND);}
                        if(v.has("format"))style.setDataFormat(book.createDataFormat().getFormat(v.getString("format")));
                        styles.put(styleKey,style);
                    }cell.setCellStyle(style);
                }
            }
            for(Object o:d.getJSONArray("merges")){JSONObject m=(JSONObject)o;sheet.addMergedRegion(new CellRangeAddress(m.getInt("row"),m.getInt("row")+m.getInt("height")-1,m.getInt("column"),m.getInt("column")+m.getInt("width")-1));}
            book.write(out);return out.toByteArray();
        }
    }
    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder value=new StringBuilder();boolean quoted=false,closed=false;
        for(int i=0;i<text.length();i++) {
            char ch=text.charAt(i);
            if(quoted){if(ch=='"'){if(i+1<text.length()&&text.charAt(i+1)=='"'){value.append('"');i++;}else{quoted=false;closed=true;}}else value.append(ch);}
            else if(ch==','||ch=='\r'||ch=='\n') {
                row.add(value.toString());value.setLength(0);closed=false;
                if(row.size()>SheetEngine.MAX_COLS)throw new IllegalArgumentException("Too many columns");
                if(ch!=','){rows.add(row);row=new ArrayList<>();if(ch=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;}
            } else if(ch=='"'&&value.length()==0&&!closed)quoted=true;
            else {if(closed||ch=='"')throw new IllegalArgumentException("Malformed CSV quoting");value.append(ch);}
            if(rows.size()>SheetEngine.MAX_ROWS||value.length()>32767)throw new IllegalArgumentException("CSV exceeds limits");
        }
        if(quoted)throw new IllegalArgumentException("Unclosed CSV quote");
        if(value.length()>0||closed||!row.isEmpty()){row.add(value.toString());rows.add(row);}
        return rows;
    }
}
